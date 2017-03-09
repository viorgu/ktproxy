import com.google.common.net.HostAndPort
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.selects.select
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.experimental.channels.Channel as AsyncChannel


object EventLoops {
    val serverConnections = NioEventLoopGroup()
}


class ClientConnection(override val channel: Channel) : ChannelAdapter("client") {

    var job: Job? = null
    val serverConnections = ConcurrentHashMap<String, ServerConnection>()

    init {
        enableHttpPipeline(channel.pipeline())
        channel.pipeline().addLast("handler", this)
    }

    fun startReading() {
        job = launch(Unconfined) {
            while (channel.isActive) {
                select<Unit> {
                    receiveChannel.onReceiveOrNull {
                        when (it) {
                            is HttpRequest -> {
                                val hostAndPort = it.identifyHostAndPort()

                                val server = findServerConnection(hostAndPort)
                                server.write(it)
                            }
                            is ByteBuf -> {

                            }
                            null -> {

                            }
                        }
                    }

                    serverConnections.forEach { hostAndPort, server ->
                        server.receiveChannel.onReceiveOrNull {
                            if (it == null) {
                                serverConnections.remove(hostAndPort)
                            } else {
                                write(it)
                            }
                        }
                    }
                }
            }
        }

        job?.invokeOnCompletion {
            log("client connection closed")
        }
    }


    fun startTunneling(remoteAddress: InetSocketAddress) {
        disableHttpPipeline(channel.pipeline())
        job = launch(Unconfined) {
            val server = ServerConnection(remoteAddress, tunnel = true)
            server.connect()

            while (channel.isActive) {
                select<Unit> {
                    receiveChannel.onReceiveOrNull {
                        if (it == null) {
                            server.disconnect()
                        } else {
                            server.write(it)
                        }
                    }

                    server.receiveChannel.onReceiveOrNull {
                        if (it == null) {
                            disconnect()
                        } else {
                            write(it)
                        }
                    }
                }
            }
        }

        job?.invokeOnCompletion {
            log("client-tunnel connection closed")
        }
    }

    private fun enableHttpPipeline(pipeline: ChannelPipeline) {
        pipeline.addLast("encoder", HttpResponseEncoder())
        pipeline.addLast("decoder", HttpRequestDecoder(8192, 8192 * 2, 8192 * 2))

        pipeline.addLast("inflater", HttpContentDecompressor())
        pipeline.addLast("aggregator", HttpObjectAggregator(10 * 1024 * 1024))
    }

    private fun disableHttpPipeline(pipeline: ChannelPipeline) {
        pipeline.remove("encoder")
        pipeline.remove("decoder")

        pipeline.remove("inflater")
        pipeline.remove("aggregator")
    }

    suspend fun findServerConnection(hostAndPort: String): ServerConnection {
        var server = serverConnections[hostAndPort]

        if (server != null) {
            return server
        } else {
            val remoteAddress = HostAndPort.fromString(hostAndPort).withDefaultPort(80).let {
                InetSocketAddress(InetAddress.getByName(it.host), it.port)
            }

            server = ServerConnection(remoteAddress)
            server.connect()
            serverConnections[hostAndPort] = server
            return server
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        super.channelInactive(ctx)

        job?.cancel()
    }
}

class ServerConnection(
        val remoteAddress: InetSocketAddress,
        val tunnel: Boolean = false) : ChannelAdapter("server") {
    override lateinit var channel: Channel

    suspend fun connect() {
        val bootstrap = Bootstrap().apply {
            group(EventLoops.serverConnections)
            channelFactory(ChannelFactory { NioSocketChannel() })
            //option(ChannelOption.CONNECT_TIMEOUT_MILLIS, proxyServer.connectTimeout)
        }

        bootstrap.handler(object : ChannelInitializer<io.netty.channel.Channel>() {
            override fun initChannel(ch: io.netty.channel.Channel) {
                initPipeline(ch.pipeline())
            }
        })

        channel = bootstrap.connect(remoteAddress).awaitChannel()
    }

    private fun initPipeline(pipeline: ChannelPipeline) {
        if (!tunnel) {
            pipeline.addLast("encoder", HttpRequestEncoder())
            pipeline.addLast("decoder", HttpResponseDecoder(8192, 8192 * 2, 8192 * 2))

            pipeline.addLast("inflater", HttpContentDecompressor())
            pipeline.addLast("aggregator", HttpObjectAggregator(10 * 1024 * 1024))
        }

        pipeline.addLast("handler", this)
    }
}

abstract class ChannelAdapter(val name: String) : ChannelInboundHandlerAdapter() {
    abstract val channel: Channel
    val receiveChannel = AsyncChannel<Any>()

    suspend fun write(msg: Any, flush: Boolean = true) {
        if (flush) {
            channel.writeAndFlush(msg).awaitComplete()
        } else {
            channel.write(msg).awaitComplete()
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) = runBlocking<Unit> {
        log("$name -- got $msg ${msg is HttpObject}")
        receiveChannel.send(msg)
        log("$name -- processed $msg")
        //ReferenceCountUtil.release(msg)
    }


    suspend fun disconnect() {
        write(Unpooled.EMPTY_BUFFER, flush = true)
        channel.disconnect().awaitComplete()
    }


    @Suppress("OverridingDeprecatedMember")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
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

        receiveChannel.close()
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        //log("$name -- channelReadComplete")
        super.channelReadComplete(ctx)
    }
}