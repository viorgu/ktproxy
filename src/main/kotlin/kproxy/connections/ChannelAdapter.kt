package kproxy.connections

import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.selects.SelectBuilder
import kotlinx.coroutines.experimental.selects.select
import kproxy.util.LoggerMetadata
import kproxy.util.buildResponse
import kproxy.util.join
import kproxy.util.kLogger
import kotlinx.coroutines.experimental.channels.Channel as AsyncChannel


abstract class ChannelAdapter : ChannelInboundHandlerAdapter(), LoggerMetadata {
    protected val log by kLogger()

    abstract val channel: Channel

    val job = Job()
    val read = AsyncChannel<Any>()

    var autoRead: Boolean
        get() = channel.config().isAutoRead
        set(value) {
            channel.config().isAutoRead = value
        }

    suspend fun write(msg: Any, flush: Boolean = true) {
        log.debug { "write" }
        if (flush) {
            channel.writeAndFlush(msg).join()
        } else {
            channel.write(msg).join()
        }
    }

    suspend fun writeResponse(status: HttpResponseStatus,
                              httpVersion: HttpVersion = HttpVersion.HTTP_1_1,
                              contentType: String = "text/html; charset=utf-8",
                              body: String? = null,
                              block: (FullHttpResponse.() -> Unit)? = null) {
        log.debug { "writing $status" }
        write(buildResponse(status, httpVersion, contentType, body, block))
    }

    suspend fun selectWhileActive(builder: SelectBuilder<Unit>.() -> Unit) {
        while (channel.isActive) {
            select(builder)
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        launch(job + Unconfined) {
            log.debug { "read" }
            log.trace {
                """
got:
---------------
$msg
---------------
"""
            }

            read.send(msg)
        }
    }

    val isConnected get() = channel.isOpen

    fun disconnectAsync() = launch(Unconfined) {
        if (isConnected) {
            try {
                write(Unpooled.EMPTY_BUFFER, flush = true)
            } catch (e: Throwable) {
                e.printStackTrace()
            } finally {
                channel.disconnect().join()
            }
        }
    }

    @Suppress("OverridingDeprecatedMember")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error(cause) { "exceptionCaught ${cause.message}" }
        //disconnectAsync()
        job.cancel(cause)
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        //log("channelWritabilityChanged")
        super.channelWritabilityChanged(ctx)
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        log.debug { "channelActive" }
        super.channelActive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        log.debug { "channelInactive" }
        super.channelInactive(ctx)

        read.poll()?.let { ReferenceCountUtil.release(it) }
        read.close()
        job.cancel()
    }
}