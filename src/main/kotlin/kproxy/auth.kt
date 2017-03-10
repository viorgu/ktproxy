package kproxy

import java.net.InetSocketAddress

interface ProxyAuthenticator {
    fun authenticate(clientAddress: InetSocketAddress, username: String, password: String): UserContext?
}

open class UserContext(val address: InetSocketAddress)

class AnonymousUserContext(address: InetSocketAddress): UserContext(address)