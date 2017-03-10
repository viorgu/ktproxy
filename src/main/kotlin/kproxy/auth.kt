package kproxy

import io.netty.handler.codec.http.HttpRequest

interface Authenticator {
    fun authenticate(request: HttpRequest): UserContext?
}

open class UserContext