package kproxy.connections

import com.google.common.net.HostAndPort
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslHandler
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kproxy.MitmManager
import kproxy.RequestHandler
import kproxy.log
import kproxy.util.awaitComplete
import kproxy.util.hostAndPort
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLEngine


class ClientConnection(override val channel: Channel) : ChannelAdapter("client") {

    var job: Job? = null
    var handler: RequestHandler? = null
    val serverConnections = ConcurrentHashMap<String, ServerConnection>()

    init {
        enableHttpDecoding(channel.pipeline())
        channel.pipeline().addLast("handler", this)
    }

    fun startReading() {
        job = launch(Unconfined) {

            var currentServerConnection: ServerConnection? = null

            selectWhileActive {

                serverConnections.values.removeIf { it.readChannel.isClosedForReceive }

                currentServerConnection?.readChannel?.onReceiveOrNull {
                    when (it) {
                        is HttpResponse -> {
                            val handlerResponse = handler?.handleServerResponse(it)
                            if (handlerResponse != null) {
                                if (handlerResponse !== it) {
                                    ReferenceCountUtil.release(it)
                                }
                                write(handlerResponse)
                            } else {
                                HttpUtil.setKeepAlive(it, true)
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
                                    HttpUtil.setKeepAlive(this, false)
                                }
                                ReferenceCountUtil.release(it)
                                disconnect().join()
                                return@onReceiveOrNull
                            }

                            val handlerResponse = handler?.handleClientRequest(it)
                            if (handlerResponse != null) {
                                ReferenceCountUtil.release(it)
                                write(handlerResponse)
                            } else {
                                currentServerConnection = findServerConnection(it.hostAndPort)
                                currentServerConnection?.write(it)
                            }
                        }
                        null -> Unit
                        else -> TODO("Got $it")
                    }
                }

            }
        }

        job?.invokeOnCompletion {
            log("client connection closed")
            serverConnections.forEach { _, server ->
                server.disconnect()
            }
        }
    }

    suspend fun findServerConnection(hostAndPort: String): ServerConnection? {
        var server = serverConnections[hostAndPort]

        if (server != null) {
            return server
        } else {
            log("Connecting to $hostAndPort")

            val remoteAddress = HostAndPort.fromString(hostAndPort).withDefaultPort(80).let {
                InetSocketAddress(InetAddress.getByName(it.host), it.port)
            }

            server = ServerConnection(remoteAddress)
            try {
                server.connect()
            } catch (e: IOException) {
                writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway")
                return null
            }

            serverConnections[hostAndPort] = server
            return server
        }
    }

    fun startMitm(remoteAddress: InetSocketAddress, mitmManager: MitmManager) {
        channel.config().isAutoRead = false

        job = launch(Unconfined) {
            val sslEngineServer = mitmManager.serverSslEngine(remoteAddress.hostName, remoteAddress.port)
            val server = ServerConnection(remoteAddress, sslEngine = sslEngineServer)
            try {
                server.connect()
            } catch (e: IOException) {
                writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway")
                return@launch
            }

            val sslEngineClient = mitmManager.clientSslEngine(remoteAddress.hostName, sslEngineServer.session)
            encryptChannel(channel.pipeline(), sslEngineClient)

            selectWhileActive {

                server.readChannel.onReceiveOrNull {
                    when (it) {
                        is HttpResponse -> {
                            val handlerResponse = handler?.handleServerResponse(it)
                            if (handlerResponse != null) {
                                if (handlerResponse !== it) {
                                    ReferenceCountUtil.release(it)
                                }
                                write(handlerResponse)
                            } else {
                                write(it)
                            }
                        }
                        null -> disconnect().join()
                        else -> TODO()
                    }
                }

                readChannel.onReceiveOrNull {
                    when (it) {
                        is HttpRequest -> {
                            val handlerResponse = handler?.handleClientRequest(it)
                            if (handlerResponse != null) {
                                ReferenceCountUtil.release(it)
                                write(handlerResponse)
                            } else {
                                server.write(it)
                            }
                        }
                        null -> server.disconnect().join()
                        else -> TODO()
                    }
                }

            }
        }

        job?.invokeOnCompletion {
            log("client-mitm connection closed")
        }
    }

    private suspend fun encryptChannel(pipeline: ChannelPipeline, sslEngine: SSLEngine) {
        sslEngine.useClientMode = false
        sslEngine.needClientAuth = false

        val handler = SslHandler(sslEngine)
        pipeline.addFirst("ssl", handler)

        channel.config().isAutoRead = true

        handler.handshakeFuture().awaitComplete()
    }

    fun startTunneling(remoteAddress: InetSocketAddress) {
        disableHttpDecoding(channel.pipeline())
        job = launch(Unconfined) {
            val server = ServerConnection(remoteAddress, tunnel = true)
            try {
                server.connect()
            } catch (e: IOException) {
                writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway") {
                    HttpUtil.setKeepAlive(this, false)
                }
                disconnect()
                return@launch
            }

            selectWhileActive {

                server.readChannel.onReceiveOrNull {
                    if (it == null) {
                        disconnect().join()
                    } else {
                        write(it)
                    }
                }

                readChannel.onReceiveOrNull {
                    if (it == null) {
                        server.disconnect()
                    } else {
                        server.write(it)
                    }
                }

            }
        }

        job?.invokeOnCompletion {
            log("client-tunnel connection closed")
        }
    }

    private fun enableHttpDecoding(pipeline: ChannelPipeline) {
        pipeline.addLast("encoder", HttpResponseEncoder())
        pipeline.addLast("decoder", HttpRequestDecoder(8192, 8192 * 2, 8192 * 2))

        pipeline.addLast("inflater", HttpContentDecompressor())
        pipeline.addLast("aggregator", HttpObjectAggregator(10 * 1024 * 1024))
    }

    private fun disableHttpDecoding(pipeline: ChannelPipeline) {
        pipeline.remove("encoder")
        pipeline.remove("decoder")

        pipeline.remove("inflater")
        pipeline.remove("aggregator")
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        super.channelInactive(ctx)

        job?.cancel()
    }
}
