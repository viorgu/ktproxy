package ktproxy.connections

import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import ktproxy.Config
import ktproxy.util.isKeepAlive
import java.io.IOException
import java.net.InetSocketAddress


class TunnelingConnectionHandler(
        id: Int,
        config: Config,
        connection: ClientConnection,
        val remoteAddress: InetSocketAddress
) : ConnectionHandler(id, config, connection) {

    private val remote: RemoteConnection

    init {
        remote = RemoteConnection(id, 0, config, remoteAddress, tunnel = true)

        connection.job.invokeOnCompletion { e ->
            if (e != null) {
                log.error(e) { e.message }

                if (connection.isConnected) {
                    connection.disconnectAsync()
                }
            }

            remote.disconnectAsync()

            log.info { "client connection closed" }
        }
    }

    fun startTunneling() {
        launch(client.job + Unconfined) {
            try {
                remote.connect()
            } catch (e: IOException) {
                client.writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway") {
                    isKeepAlive = false
                }
                client.disconnectAsync()
                return@launch
            }

            client.writeResponse(HttpResponseStatus(200, "Connection established"))
            client.disableHttpDecoding()

            client.autoRead = true

            client.selectWhileActive {

                remote.read.onReceiveOrNull {
                    if (it == null) {
                        client.disconnectAsync().join()
                    } else {
                        client.write(it)
                    }
                }

                client.read.onReceiveOrNull {
                    if (it != null) {
                        remote.write(it)
                    } else {
                        remote.disconnectAsync().join()
                    }
                }
            }
        }
    }

    override val loggableState: String?
        get() = "$id|c:${client.isConnected}|ra:$remoteAddress|rc:${remote.isConnected}"
}
