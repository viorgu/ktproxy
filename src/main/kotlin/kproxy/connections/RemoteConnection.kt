package kproxy.connections

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslHandler
import kproxy.Config
import kproxy.EventLoops
import kproxy.util.awaitChannel
import kproxy.util.join
import java.net.InetSocketAddress
import javax.net.ssl.SSLEngine


class RemoteConnection(
        val clientId: Int,
        val remoteId: Int,
        val config: Config,
        val remoteAddress: InetSocketAddress,
        val sslEngine: SSLEngine? = null,
        val tunnel: Boolean = false) : ChannelAdapter() {

    override lateinit var channel: Channel

    suspend fun connect() {
        val bootstrap = Bootstrap().apply {
            group(EventLoops.remoteConnectionsGroup)
            channelFactory(ChannelFactory { NioSocketChannel() })
            //option(ChannelOption.CONNECT_TIMEOUT_MILLIS, proxyServer.connectTimeout)
        }

        bootstrap.handler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                initPipeline(ch.pipeline())
            }
        })

        channel = bootstrap.connect(remoteAddress).awaitChannel()
        if (sslEngine != null) {
            encryptChannel(channel.pipeline(), sslEngine)
        }
    }

    private fun initPipeline(pipeline: ChannelPipeline) {
        if (!tunnel) {
            pipeline.addLast("encoder", HttpRequestEncoder())
            pipeline.addLast("decoder", HttpResponseDecoder(config.maxInitialLineLength, config.maxHeaderSize, config.maxChunkSize))

            pipeline.addLast("decompressor", HttpContentDecompressor())
            pipeline.addLast("aggregator", HttpObjectAggregator(config.maxResponseBufferSize))
        }

        pipeline.addLast("handler", this)
    }

    private suspend fun encryptChannel(pipeline: ChannelPipeline, sslEngine: SSLEngine) {
        sslEngine.useClientMode = true
        sslEngine.needClientAuth = false

        val handler = SslHandler(sslEngine)
        pipeline.addFirst("ssl", handler)

        handler.handshakeFuture().join()
    }

    override val loggableState: String?
        get() = "$clientId/$remoteId|${remoteAddress.hostString}|$isConnected"
}