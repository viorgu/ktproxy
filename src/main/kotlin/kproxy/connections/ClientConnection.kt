package kproxy.connections

import io.netty.channel.Channel
import io.netty.handler.codec.http.*
import kproxy.Config
import kotlinx.coroutines.experimental.channels.Channel as AsyncChannel

class ClientConnection(
        val id: Int,
        override val channel: Channel,
        val config: Config
) : ChannelAdapter() {

    init {
        enableHttpDecoding()
        channel.pipeline().addLast("handler", this)
    }

    fun enableHttpDecoding() {
        channel.pipeline().apply {
            addLast("encoder", HttpResponseEncoder())
            addLast("decoder", HttpRequestDecoder(config.maxInitialLineLength, config.maxHeaderSize, config.maxChunkSize))

            addLast("decompressor", HttpContentDecompressor())
            addLast("compressor", HttpContentCompressor())
            addLast("aggregator", HttpObjectAggregator(config.maxRequestBufferSize))
        }
    }

    fun disableHttpDecoding() {
        channel.pipeline().apply {
            remove("encoder")
            remove("decoder")

            remove("decompressor")
            remove("compressor")
            remove("aggregator")
        }
    }

    override val loggableState: String?
        get() = "$id"
}