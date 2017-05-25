package ktproxy.util

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.*
import java.nio.charset.StandardCharsets


val HTTP_URL_MATCH = Regex("^(?:(https?)://)?([^/]*)(.*)$", RegexOption.IGNORE_CASE)

val HttpRequest.isConnect
    get() = method() == HttpMethod.CONNECT

val HttpRequest.host: String
    get() {
        val hostAndPort = HTTP_URL_MATCH.matchEntire(uri())?.groupValues?.getOrNull(2)

        return if (hostAndPort.isNullOrBlank()) {
            headers().get(HttpHeaderNames.HOST).orEmpty()
        } else {
            hostAndPort.orEmpty()
        }
    }

val HttpRequest.hostname: String
    get() = host.substringBefore(":")

val HttpRequest.originFormUri: String
    get() = HTTP_URL_MATCH.matchEntire(uri())?.groupValues?.getOrNull(3).orEmpty()

val HttpRequest.isAbsoluteFormUri: Boolean
    get() = !HTTP_URL_MATCH.matchEntire(uri())?.groupValues?.getOrNull(2).isNullOrEmpty()


var HttpRequest.isKeepAlive: Boolean
    get() = HttpUtil.isKeepAlive(this)
    set(value) = HttpUtil.setKeepAlive(this, value)

var HttpResponse.isKeepAlive: Boolean
    get() = HttpUtil.isKeepAlive(this)
    set(value) = HttpUtil.setKeepAlive(this, value)



fun buildResponse(status: HttpResponseStatus = HttpResponseStatus.OK,
                  httpVersion: HttpVersion = HttpVersion.HTTP_1_1,
                  contentType: String = "text/html; charset=utf-8",
                  body: String? = null,
                  block: (FullHttpResponse.() -> Unit)? = null): FullHttpResponse {

    val response = if (body != null) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val content = Unpooled.copiedBuffer(bytes)

        DefaultFullHttpResponse(httpVersion, status, content).apply {
            headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
            headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
        }
    } else {
        DefaultFullHttpResponse(httpVersion, status)
    }


    block?.invoke(response)

    return response
}
