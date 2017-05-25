package ktproxy.connections

import com.google.common.net.HostAndPort
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import ktproxy.Config
import ktproxy.RequestInterceptor
import ktproxy.SslEngineSource
import ktproxy.util.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger


class HttpConnectionHandler(
        id: Int,
        config: Config,
        connection: ClientConnection,
        val interceptor: RequestInterceptor?,
        val sslEngineSource: SslEngineSource?
) : ConnectionHandler(id, config, connection) {

    val remoteConnections = ConcurrentHashMap<String, RemoteConnection>()

    private val nextServerId = AtomicInteger()

    init {
        connection.job.invokeOnCompletion { e ->
            if (e != null) {
                log.error(e) { e.message }

                if (connection.isConnected) {
                    try {
                        runBlocking {
                            connection.writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Server error") {
                                isKeepAlive = false
                            }
                        }
                    } catch (e: Throwable) {
                        log.warn(e) { e.message }
                    } finally {
                        connection.disconnectAsync()
                    }
                }
            }

            log.info { "client connection closed" }

            remoteConnections.forEach {
                log.info { "disconnecting from ${it.key}, was connected: ${it.value.isConnected}" }
                it.value.disconnectAsync()
            }
        }
    }

    fun startReading() {
        launch(client.job + Unconfined) {
            client.autoRead = true

            var currentRemoteConnection: RemoteConnection? = null

            client.selectWhileActive {

                remoteConnections.values.removeIf { it.read.isClosedForReceive }

                currentRemoteConnection?.read?.onReceiveOrNull {
                    when (it) {
                        is HttpResponse -> {

                            preprocessResponse(it)

                            val handlerResponse = interceptor?.handleServerResponse(it)
                            if (handlerResponse != null) {
                                if (handlerResponse !== it) {
                                    ReferenceCountUtil.release(it)
                                }
                                client.write(handlerResponse)
                            } else {
                                client.write(it)
                            }
                        }
                        null -> {
                            log.info { "server disconnected ${currentRemoteConnection?.remoteAddress}" }
                            currentRemoteConnection = null
                        }
                        else -> TODO("Got $it")
                    }
                }

                client.read.onReceiveOrNull {
                    when (it) {
                        is HttpRequest -> {
                            if (it.decoderResult().isFailure) {
                                client.writeResponse(HttpResponseStatus.BAD_REQUEST, body = "Unable to parse HTTP request") {
                                    isKeepAlive = false
                                }
                                ReferenceCountUtil.release(it)
                                client.disconnectAsync().join()
                                return@onReceiveOrNull
                            }

                            val directToProxyRequest = !it.isAbsoluteFormUri
                            val hostBefore = it.host

                            preprocessRequest(it)

                            val handlerResponse = interceptor?.handleClientRequest(it)
                            if (handlerResponse != null) {
                                ReferenceCountUtil.release(it)
                                client.write(handlerResponse)
                            } else {
                                if (directToProxyRequest && hostBefore == it.host) {
                                    // prevent endless loop in unhandled direct to proxy requests
                                    client.writeResponse(HttpResponseStatus.BAD_REQUEST,
                                            body = "Bad Request to URI: ${it.uri()}") {
                                        isKeepAlive = false
                                    }
                                    client.disconnectAsync().join()
                                    return@onReceiveOrNull
                                }

                                currentRemoteConnection = findServerConnection(it.host, sslEngineSource)
                                currentRemoteConnection?.write(it)
                            }
                        }
                        null -> Unit
                        else -> TODO("Got $it")
                    }
                }
            }
        }
    }

    private suspend fun findServerConnection(host: String, sslEngineSource: SslEngineSource?): RemoteConnection? {
        var remote = remoteConnections[host]

        if (remote != null) {
            log.debug { "Reusing connection $host" }

            return remote
        } else {
            log.debug { "Connecting to $host" }

            val remoteAddress = HostAndPort.fromString(host).withDefaultPort(80).let {
                InetSocketAddress(InetAddress.getByName(it.host), it.port)
            }

            val sslEngine = if (remoteAddress.port == 443) {
                sslEngineSource?.serverSslEngine(remoteAddress.hostName, remoteAddress.port)
            } else {
                null
            }

            remote = RemoteConnection(id, nextServerId.getAndIncrement(), config, remoteAddress, sslEngine = sslEngine)
            try {
                remote.connect()
            } catch (e: IOException) {
                client.writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway")
                return null
            }

            remoteConnections[host] = remote
            return remote
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
        addVia(headers, config.proxyName)

        request.isKeepAlive = true
    }

    private fun preprocessResponse(response: HttpResponse) {
        val headers = response.headers()

        stripConnectionTokens(headers)
        stripHopByHopHeaders(headers)
        addVia(headers, config.proxyName)

        // RFC2616 Section 14.18
        if (!headers.contains(HttpHeaderNames.DATE)) {
            headers.set(HttpHeaderNames.DATE, Date())
        }

        response.isKeepAlive = true
    }

    override val loggableState: String?
        get() = "$id|c:${client.isConnected}|rs:${remoteConnections.size}"
}
