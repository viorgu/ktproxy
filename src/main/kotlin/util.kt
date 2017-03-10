import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.handler.codec.http.*
import java.nio.charset.StandardCharsets
import kotlin.coroutines.experimental.suspendCoroutine


fun log(message: String) = println("${Thread.currentThread().name} -- $message")


private val HTTP_PREFIX = Regex("^https?://.*", RegexOption.IGNORE_CASE)

val HttpRequest.isConnect get() = method() == HttpMethod.CONNECT

fun HttpRequest.identifyHostAndPort(): String {
    val hostAndPort = if (!HTTP_PREFIX.matches(uri())) {
        // Browsers particularly seem to send requests in this form when
        // they use CONNECT.
        uri()
    } else {
        uri().substringAfter("://")
    }.substringBefore("/")

    return if (hostAndPort.isNullOrBlank()) {
        headers().getAll(HttpHeaderNames.HOST)?.firstOrNull().orEmpty()
    } else {
        hostAndPort.orEmpty()
    }
}


fun buildResponse(status: HttpResponseStatus,
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


suspend fun ChannelFuture.awaitChannel(): Channel = suspendCoroutine { c ->
    addListener { future -> if (future.isSuccess) c.resume(channel()) else c.resumeWithException(future.cause()) }
}

suspend fun ChannelFuture.awaitComplete(): Unit = suspendCoroutine { c ->
    addListener { future -> if (future.isSuccess) c.resume(Unit) else c.resumeWithException(future.cause()) }
}