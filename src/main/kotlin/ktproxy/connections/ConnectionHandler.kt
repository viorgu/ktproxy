package ktproxy.connections

import ktproxy.Config
import ktproxy.util.LoggerMetadata
import ktproxy.util.kLogger


abstract class ConnectionHandler(
        val id: Int,
        val config: Config,
        val client: ClientConnection
) : LoggerMetadata {

    protected val log by kLogger()

}
