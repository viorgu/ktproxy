package kproxy

import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession


interface ConnectionHandler {
    fun mitm(initialRequest: HttpRequest, userContext: UserContext): SslEngineSource?
    fun intercept(initialRequest: HttpRequest, userContext: UserContext): RequestInterceptor?
}

interface RequestInterceptor {
    fun handleClientRequest(httpObject: HttpObject): HttpResponse?
    fun handleServerResponse(httpObject: HttpObject): HttpResponse?
}

interface SslEngineSource {
    fun serverSslEngine(peerHost: String, peerPort: Int): SSLEngine
    fun clientSslEngine(hostname: String, serverSslSession: SSLSession): SSLEngine
}