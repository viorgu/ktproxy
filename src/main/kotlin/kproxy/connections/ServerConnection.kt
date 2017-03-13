package kproxy.connections

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.HttpContentDecompressor
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequestEncoder
import io.netty.handler.codec.http.HttpResponseDecoder
import io.netty.handler.ssl.SslHandler
import kproxy.EventLoops
import kproxy.util.awaitChannel
import kproxy.util.join
import java.net.InetSocketAddress
import javax.net.ssl.SSLEngine


class ServerConnection(
        val remoteAddress: InetSocketAddress,
        val sslEngine: SSLEngine? = null,
        val tunnel: Boolean = false) : ChannelAdapter("server") {

    override lateinit var channel: Channel

    suspend fun connect() {
        val bootstrap = Bootstrap().apply {
            group(EventLoops.serverConnectionsGroup)
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
            pipeline.addLast("decoder", HttpResponseDecoder(8192, 8192 * 2, 8192 * 2))

            pipeline.addLast("inflater", HttpContentDecompressor())
            pipeline.addLast("aggregator", HttpObjectAggregator(50 * 1024 * 1024))
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
}