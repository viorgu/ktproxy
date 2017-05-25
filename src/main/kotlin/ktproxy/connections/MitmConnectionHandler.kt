package ktproxy.connections

import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.ssl.SslHandler
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import ktproxy.Config
import ktproxy.RequestInterceptor
import ktproxy.SslEngineSource
import ktproxy.util.isKeepAlive
import ktproxy.util.join
import java.io.IOException
import java.net.InetSocketAddress
import javax.net.ssl.SSLEngine


class MitmConnectionHandler(
        id: Int,
        config: Config,
        connection: ClientConnection,
        val remoteAddress: InetSocketAddress,
        val interceptor: RequestInterceptor?,
        val sslEngineSource: SslEngineSource
) : ConnectionHandler(id, config, connection) {

    private val sslEngineServer: SSLEngine
    private val remote: RemoteConnection

    init {
        sslEngineServer = sslEngineSource.serverSslEngine(remoteAddress.hostName, remoteAddress.port)
        remote = RemoteConnection(id, 0, config, remoteAddress, sslEngine = sslEngineServer)

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

            remote.disconnectAsync()

            log.info { "client connection closed" }
        }
    }

    fun startMitm() {
        launch(client.job + Unconfined) {
            try {
                remote.connect()
            } catch (e: IOException) {
                client.writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway") {
                    isKeepAlive = false
                }
                client.disconnectAsync().join()
                return@launch
            }
            client.writeResponse(HttpResponseStatus(200, "Connection established"))

            val sslEngineClient = sslEngineSource.clientSslEngine(remoteAddress.hostName, sslEngineServer.session)
            encryptConnection(client, sslEngineClient)

            client.selectWhileActive {

                remote.read.onReceiveOrNull {
                    when (it) {
                        is HttpResponse -> {
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
                        null -> client.disconnectAsync().join()
                        else -> TODO()
                    }
                }

                client.read.onReceiveOrNull {
                    when (it) {
                        is HttpRequest -> {
                            val handlerResponse = interceptor?.handleClientRequest(it)
                            if (handlerResponse != null) {
                                ReferenceCountUtil.release(it)
                                client.write(handlerResponse)
                            } else {
                                remote.write(it)
                            }
                        }
                        null -> Unit
                        else -> TODO()
                    }
                }

            }
        }
    }

    private suspend fun encryptConnection(connection: ClientConnection, sslEngine: SSLEngine) {
        sslEngine.useClientMode = false
        sslEngine.needClientAuth = false

        val handler = SslHandler(sslEngine)
        connection.channel.pipeline().addFirst("ssl", handler)

        connection.autoRead = true

        handler.handshakeFuture().join()
    }

    override val loggableState: String?
        get() = "$id|c:${client.isConnected}|ra:$remoteAddress|rc:${remote.isConnected}"
}
