package kproxy.connections

import kproxy.Config
import kproxy.util.LoggerMetadata
import kproxy.util.kLogger


abstract class ConnectionHandler(
        val id: Int,
        val config: Config,
        val client: ClientConnection) : LoggerMetadata {

    protected val log by kLogger()

}
