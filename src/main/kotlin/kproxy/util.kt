package kproxy

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.*
import java.nio.charset.StandardCharsets


fun log(message: String) = println("${Thread.currentThread().name} -- $message")


