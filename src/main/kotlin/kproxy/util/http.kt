package kproxy.util

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.*
import java.nio.charset.StandardCharsets


val HTTP_URL_MATCH = Regex("^(?:https?://)?([^/]*)(.*)$", RegexOption.IGNORE_CASE)

val HttpRequest.isConnect
    get() = method() == HttpMethod.CONNECT

val HttpRequest.hostAndPort: String
    get() {
        val hostAndPort = HTTP_URL_MATCH.matchEntire(uri())?.groupValues?.getOrNull(1)

        return if (hostAndPort.isNullOrBlank()) {
            headers().getAll(HttpHeaderNames.HOST)?.firstOrNull().orEmpty()
        } else {
            hostAndPort.orEmpty()
        }
    }

val HttpRequest.host: String
    get() = hostAndPort.substringBefore(":")

val HttpRequest.path: String
    get() = HTTP_URL_MATCH.matchEntire(uri())?.groupValues?.getOrNull(2).orEmpty()


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
