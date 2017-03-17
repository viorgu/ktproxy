package kproxy.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty


fun kLogger() = LoggerProvider

object LoggerProvider {
    operator fun provideDelegate(thisRef: Any, property: KProperty<*>): ReadOnlyProperty<Any, KLogger> {
        val metadata = thisRef as? LoggerMetadata
        val logger = LoggerFactory.getLogger(metadata?.loggableName ?: thisRef::class.java.name)

        return KLogger(logger, metadata)
    }
}

interface LoggerMetadata {
    val loggableName: String
        get() = this::class.java.name

    val loggableState: String?
        get() = null
}

class KLogger(val logger: Logger, val metadata: LoggerMetadata?) : ReadOnlyProperty<Any, KLogger> {

    override fun getValue(thisRef: Any, property: KProperty<*>) = this

    fun prepareMessage(message: Any?) = buildString {
        metadata?.loggableState?.let { append("[$it]: ") }
        append(message.toString())
    }

    inline fun trace(throwable: Throwable? = null, block: () -> Any?) {
        if (logger.isTraceEnabled) {
            logger.trace(prepareMessage(block()), throwable)
        }
    }

    inline fun debug(throwable: Throwable? = null, block: () -> Any?) {
        if (logger.isDebugEnabled) {
            logger.debug(prepareMessage(block()), throwable)
        }
    }

    inline fun info(throwable: Throwable? = null, block: () -> Any?) {
        if (logger.isInfoEnabled) {
            logger.info(prepareMessage(block()), throwable)
        }
    }

    inline fun warn(throwable: Throwable? = null, block: () -> Any?) {
        if (logger.isWarnEnabled) {
            logger.warn(prepareMessage(block()), throwable)
        }
    }

    inline fun error(throwable: Throwable? = null, block: () -> Any?) {
        if (logger.isErrorEnabled) {
            logger.error(prepareMessage(block()), throwable)
        }
    }

}