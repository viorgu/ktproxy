package kproxy

import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse


interface RequestInterceptor {
    fun getMitmManager(request: HttpRequest, userContext: UserContext): MitmManager?

    fun intercept(request: HttpRequest, userContext: UserContext): RequestHandler?
}

interface RequestHandler {
    fun onClientRequest(httpObject: HttpObject): HttpResponse?

    fun onServerResponse(httpObject: HttpObject): HttpResponse?
}
