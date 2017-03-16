package kproxy.connections

import com.google.common.net.HostAndPort
import io.netty.channel.Channel
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslHandler
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kproxy.RequestInterceptor
import kproxy.SslEngineSource
import kproxy.util.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLEngine


enum class ConnectionType {
    UNKNOWN, DEFAULT, MITM, TUNNEL
}


class ClientConnection(val id: Int, override val channel: Channel) : ChannelAdapter("client-$id") {

    var type = ConnectionType.UNKNOWN
        private set

    var interceptor: RequestInterceptor? = null
    val serverConnections = ConcurrentHashMap<String, ServerConnection>()

    val nextServerId = AtomicInteger()

    init {
        enableHttpDecoding(channel.pipeline())
        channel.pipeline().addLast("handler", this)

        job.invokeOnCompletion {
            if (it != null) {
                log("error ${it.message}")
                it.printStackTrace()

                if (isConnected) {
                    try {
                        runBlocking {
                            writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Server error") {
                                isKeepAlive = false
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    } finally {
                        disconnectAsync()
                    }
                }
            }

            log("client connection closed")

            serverConnections.forEach {
                log("disconnecting from ${it.key} [connected: ${it.value.isConnected}]")
                it.value.disconnectAsync()
            }
        }
    }

    fun startReading() {
        type = ConnectionType.DEFAULT

        launch(job + Unconfined) {
            var currentServerConnection: ServerConnection? = null

            selectWhileActive {

                serverConnections.values.removeIf { it.readChannel.isClosedForReceive }

                currentServerConnection?.readChannel?.onReceiveOrNull {
                    when (it) {
                        is HttpResponse -> {

                            preprocessResponse(it)

                            val handlerResponse = interceptor?.handleServerResponse(it)
                            if (handlerResponse != null) {
                                if (handlerResponse !== it) {
                                    ReferenceCountUtil.release(it)
                                }
                                write(handlerResponse)
                            } else {
                                write(it)
                            }
                        }
                        null -> {
                            log("server disconnected ${currentServerConnection?.remoteAddress}")
                            currentServerConnection = null
                        }
                        else -> TODO("Got $it")
                    }
                }

                readChannel.onReceiveOrNull {
                    when (it) {
                        is HttpRequest -> {
                            if (it.decoderResult().isFailure) {
                                writeResponse(HttpResponseStatus.BAD_REQUEST, body = "Unable to parse HTTP request") {
                                    isKeepAlive = false
                                }
                                ReferenceCountUtil.release(it)
                                disconnectAsync().join()
                                return@onReceiveOrNull
                            }

                            val directToProxyRequest = !it.isAbsoluteFormUri

                            preprocessRequest(it)

                            val handlerResponse = interceptor?.handleClientRequest(it)
                            if (handlerResponse != null) {
                                ReferenceCountUtil.release(it)
                                write(handlerResponse)
                            } else {
                                if (directToProxyRequest) {
                                    // prevent endless loop in unhandled direct to proxy requests
                                    writeResponse(HttpResponseStatus.BAD_REQUEST,
                                            body = "Bad Request to URI: ${it.uri()}") {
                                        isKeepAlive = false
                                    }
                                    disconnectAsync().join()
                                    return@onReceiveOrNull
                                }

                                currentServerConnection = findServerConnection(it.host)
                                currentServerConnection?.write(it)
                            }
                        }
                        null -> Unit
                        else -> TODO("Got $it")
                    }
                }
            }
        }
    }

    suspend fun findServerConnection(host: String): ServerConnection? {
        var server = serverConnections[host]

        if (server != null) {
            log("Reusing connection $host")

            return server
        } else {
            log("Connecting to $host")

            val remoteAddress = HostAndPort.fromString(host).withDefaultPort(80).let {
                InetSocketAddress(InetAddress.getByName(it.host), it.port)
            }

            server = ServerConnection(id, nextServerId.getAndIncrement(), remoteAddress)
            try {
                server.connect()
            } catch (e: IOException) {
                writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway")
                return null
            }

            serverConnections[host] = server
            return server
        }
    }

    fun startMitm(remoteAddress: InetSocketAddress, sslEngineSource: SslEngineSource) {
        type = ConnectionType.MITM

        channel.config().isAutoRead = false

        val sslEngineServer = sslEngineSource.serverSslEngine(remoteAddress.hostName, remoteAddress.port)
        val server = ServerConnection(id, nextServerId.getAndIncrement(), remoteAddress, sslEngine = sslEngineServer)

        launch(job + Unconfined) {
            try {
                server.connect()
            } catch (e: IOException) {
                writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway") {
                    isKeepAlive = false
                }
                disconnectAsync().join()
                return@launch
            }
            serverConnections[remoteAddress.hostName] = server

            writeResponse(HttpResponseStatus(200, "Connection established"))

            val sslEngineClient = sslEngineSource.clientSslEngine(remoteAddress.hostName, sslEngineServer.session)
            encryptChannel(channel.pipeline(), sslEngineClient)

            selectWhileActive {

                server.readChannel.onReceiveOrNull {
                    when (it) {
                        is HttpResponse -> {
                            val handlerResponse = interceptor?.handleServerResponse(it)
                            if (handlerResponse != null) {
                                if (handlerResponse !== it) {
                                    ReferenceCountUtil.release(it)
                                }
                                write(handlerResponse)
                            } else {
                                write(it)
                            }
                        }
                        null -> disconnectAsync().join()
                        else -> TODO()
                    }
                }

                readChannel.onReceiveOrNull {
                    when (it) {
                        is HttpRequest -> {
                            val handlerResponse = interceptor?.handleClientRequest(it)
                            if (handlerResponse != null) {
                                ReferenceCountUtil.release(it)
                                write(handlerResponse)
                            } else {
                                server.write(it)
                            }
                        }
                        null -> Unit
                        else -> TODO()
                    }
                }

            }
        }
    }

    private suspend fun encryptChannel(pipeline: ChannelPipeline, sslEngine: SSLEngine) {
        sslEngine.useClientMode = false
        sslEngine.needClientAuth = false

        val handler = SslHandler(sslEngine)
        pipeline.addFirst("ssl", handler)

        channel.config().isAutoRead = true

        handler.handshakeFuture().join()
    }

    fun startTunneling(remoteAddress: InetSocketAddress) {
        type = ConnectionType.TUNNEL

        val server = ServerConnection(id, nextServerId.getAndIncrement(), remoteAddress, tunnel = true)

        launch(job + Unconfined) {
            try {
                server.connect()
            } catch (e: IOException) {
                writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway") {
                    isKeepAlive = false
                }
                disconnectAsync()
                return@launch
            }
            serverConnections[remoteAddress.hostName] = server

            writeResponse(HttpResponseStatus(200, "Connection established"))
            disableHttpDecoding(channel.pipeline())

            selectWhileActive {

                server.readChannel.onReceiveOrNull {
                    if (it == null) {
                        disconnectAsync().join()
                    } else {
                        write(it)
                    }
                }

                readChannel.onReceiveOrNull {
                    if (it != null) {
                        server.write(it)
                    }
                }
            }
        }
    }

    private fun preprocessRequest(request: HttpRequest) {
        val headers = request.headers()

        // RFC7230 Section 5.3.1 / 5.4
        if (request.isAbsoluteFormUri) {
            headers[HttpHeaderNames.HOST] = request.host
            request.uri = request.originFormUri
        }

        stripConnectionTokens(headers)
        stripHopByHopHeaders(headers)
        addVia(headers, "kproxy")

        request.isKeepAlive = true
    }

    private fun preprocessResponse(response: HttpResponse) {
        val headers = response.headers()

        stripConnectionTokens(headers)
        stripHopByHopHeaders(headers)
        addVia(headers, "kproxy")

        // RFC2616 Section 14.18
        if (!headers.contains(HttpHeaderNames.DATE)) {
            headers.set(HttpHeaderNames.DATE, Date())
        }

        response.isKeepAlive = true
    }

    private fun enableHttpDecoding(pipeline: ChannelPipeline) {
        pipeline.addLast("encoder", HttpResponseEncoder())
        pipeline.addLast("decoder", HttpRequestDecoder(8192, 8192 * 2, 8192 * 2))

        pipeline.addLast("decompressor", HttpContentDecompressor())
        pipeline.addLast("compressor", HttpContentCompressor())
        pipeline.addLast("aggregator", HttpObjectAggregator(50 * 1024 * 1024))
    }

    private fun disableHttpDecoding(pipeline: ChannelPipeline) {
        pipeline.remove("encoder")
        pipeline.remove("decoder")

        pipeline.remove("decompressor")
        pipeline.remove("compressor")
        pipeline.remove("aggregator")
    }
}
