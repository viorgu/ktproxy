package kproxy.util

import org.slf4j.LoggerFactory


fun log(message: String) = println("[${Thread.currentThread().name}]: $message")


inline fun <reified T> loggerFor() = LoggerFactory.getLogger(T::class.java)
