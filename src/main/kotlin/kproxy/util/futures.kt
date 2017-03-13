package kproxy.util

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.util.concurrent.Future
import kotlin.coroutines.experimental.suspendCoroutine

suspend fun ChannelFuture.awaitChannel(): Channel = suspendCoroutine { c ->
    addListener { future -> if (future.isSuccess) c.resume(channel()) else c.resumeWithException(future.cause()) }
}

suspend fun Future<*>.join(): Unit = suspendCoroutine { c ->
    addListener { future -> if (future.isSuccess) c.resume(Unit) else c.resumeWithException(future.cause()) }
}