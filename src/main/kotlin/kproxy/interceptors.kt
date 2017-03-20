package kproxy

import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession


interface ConnectionHandler {
    fun sslEngineSource(initialRequest: HttpRequest, userContext: UserContext): SslEngineSource? = null
    fun intercept(initialRequest: HttpRequest, userContext: UserContext): RequestInterceptor? = null
}

interface RequestInterceptor {
    fun handleClientRequest(httpObject: HttpObject): HttpResponse? = null
    fun handleServerResponse(httpObject: HttpObject): HttpResponse? = null
}

interface SslEngineSource {
    fun serverSslEngine(peerHost: String, peerPort: Int): SSLEngine
    fun clientSslEngine(hostname: String, serverSslSession: SSLSession): SSLEngine
}