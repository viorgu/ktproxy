package ktproxy

import java.net.InetSocketAddress


open class UserContext(val address: InetSocketAddress,
                       val username: String = "anonymous",
                       val authenticated: Boolean = false,
                       val metadata: MutableMap<String, Any?> = mutableMapOf())

interface ProxyAuthenticator {
    fun authenticate(clientAddress: InetSocketAddress, username: String, password: String): UserContext?
}