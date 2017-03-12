package kproxy.connections

import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.selects.SelectBuilder
import kotlinx.coroutines.experimental.selects.select
import kproxy.log
import kproxy.util.awaitComplete
import kproxy.util.buildResponse
import kotlinx.coroutines.experimental.channels.Channel as AsyncChannel


abstract class ChannelAdapter(val name: String) : ChannelInboundHandlerAdapter() {

    abstract val channel: Channel
    val readChannel = AsyncChannel<Any>()

    suspend fun write(msg: Any, flush: Boolean = true) {
        if (flush) {
            channel.writeAndFlush(msg).awaitComplete()
        } else {
            channel.write(msg).awaitComplete()
        }
    }

    suspend fun writeResponse(status: HttpResponseStatus,
                              httpVersion: HttpVersion = HttpVersion.HTTP_1_1,
                              contentType: String = "text/html; charset=utf-8",
                              body: String? = null,
                              block: (FullHttpResponse.() -> Unit)? = null) {
        write(buildResponse(status, httpVersion, contentType, body, block))
    }

    suspend fun selectWhileActive(builder: SelectBuilder<Unit>.() -> Unit) {
        while (channel.isActive) {
            select(builder)
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) = runBlocking<Unit> {
        log("""
$name -- got:
---------------
$msg
---------------""")

        readChannel.send(msg)

        log("$name -- processed message")
    }

    fun disconnect() = launch(Unconfined) {
        if (channel.isOpen) {
            write(Unpooled.EMPTY_BUFFER, flush = true)
            channel.disconnect().awaitComplete()
        }
    }

    @Suppress("OverridingDeprecatedMember")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        disconnect()
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        log("$name -- channelWritabilityChanged")
        super.channelWritabilityChanged(ctx)
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        log("$name -- channelActive")
        super.channelActive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        log("$name -- channelInactive")
        super.channelInactive(ctx)

        readChannel.poll()?.let { ReferenceCountUtil.release(it) }

        readChannel.close()
    }
}