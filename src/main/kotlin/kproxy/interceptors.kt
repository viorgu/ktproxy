package kproxy

import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse


interface RequestInterceptor {
    fun mitm(request: HttpRequest, userContext: UserContext): MitmManager?
    fun intercept(request: HttpRequest, userContext: UserContext): RequestHandler?
}

interface RequestHandler {
    fun handleClientRequest(httpObject: HttpObject): HttpResponse?
    fun handleServerResponse(httpObject: HttpObject): HttpResponse?
}
