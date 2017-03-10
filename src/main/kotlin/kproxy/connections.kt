package kproxy

import com.google.common.net.HostAndPort
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslHandler
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.selects.select
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLEngine
import kotlinx.coroutines.experimental.channels.Channel as AsyncChannel


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
                    readChannel.onReceiveOrNull {
                        when (it) {
                            is HttpRequest -> {
                                if (it.decoderResult().isFailure) {
                                    write(buildResponse(HttpResponseStatus.BAD_REQUEST, body = "Unable to parse HTTP request") {
                                        HttpUtil.setKeepAlive(this, false)
                                    })
                                    disconnect()
                                }

                                val hostAndPort = it.identifyHostAndPort()

                                val server = findServerConnection(hostAndPort)
                                server?.write(it)
                            }
                            null -> Unit
                            else -> TODO("Got $it")
                        }
                    }

                    serverConnections.forEach { hostAndPort, server ->
                        server.readChannel.onReceiveOrNull {
                            if (it == null) {
                                log("server disconnected $hostAndPort")
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
            serverConnections.forEach { _, server ->
                //server.disconnect()
            }
        }
    }

    suspend fun findServerConnection(hostAndPort: String): ServerConnection? {
        var server = serverConnections[hostAndPort]

        if (server != null) {
            return server
        } else {
            log(hostAndPort)

            val remoteAddress = HostAndPort.fromString(hostAndPort).withDefaultPort(80).let {
                InetSocketAddress(InetAddress.getByName(it.host), it.port)
            }

            server = ServerConnection(remoteAddress)
            try {
                server.connect()
            } catch (e: IOException) {
                write(buildResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway"))
                return null
            }

            serverConnections[hostAndPort] = server
            return server
        }
    }

    fun startMitm(remoteAddress: InetSocketAddress, mitmManager: MitmManager) {
        channel.config().isAutoRead = false

        job = launch(Unconfined) {
            val sslEngineServer = mitmManager.serverSslEngine(remoteAddress.hostName, remoteAddress.port)
            val server = ServerConnection(remoteAddress, sslEngine = sslEngineServer)
            try {
                server.connect()
            } catch (e: IOException) {
                write(buildResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway"))
                return@launch
            }

            val sslEngineClient = mitmManager.clientSslEngineFor(remoteAddress.hostName, sslEngineServer.session)
            encryptChannel(channel.pipeline(), sslEngineClient)

            while (channel.isActive) {
                select<Unit> {
                    readChannel.onReceiveOrNull {
                        when (it) {
                            is HttpRequest -> {
                                server.write(it)
                            }
                            null -> server.disconnect()
                            else -> TODO()
                        }
                    }

                    server.readChannel.onReceiveOrNull {
                        when (it) {
                            is HttpResponse -> {
                                write(it)
                            }
                            null -> disconnect()
                            else -> TODO()
                        }
                    }
                }
            }
        }

        job?.invokeOnCompletion {
            log("client-mitm connection closed")
        }
    }

    private suspend fun encryptChannel(pipeline: ChannelPipeline, sslEngine: SSLEngine) {
        sslEngine.useClientMode = false
        sslEngine.needClientAuth = false

        val handler = SslHandler(sslEngine)
        pipeline.addFirst("ssl", handler)

        channel.config().isAutoRead = true

        handler.handshakeFuture().awaitComplete()
    }

    fun startTunneling(remoteAddress: InetSocketAddress) {
        disableHttpPipeline(channel.pipeline())
        job = launch(Unconfined) {
            val server = ServerConnection(remoteAddress, tunnel = true)
            try {
                server.connect()
            } catch (e: IOException) {
                write(buildResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway") {
                    HttpUtil.setKeepAlive(this, false)
                })
                disconnect()
                return@launch
            }

            while (channel.isActive) {
                select<Unit> {
                    readChannel.onReceiveOrNull {
                        if (it == null) {
                            server.disconnect()
                        } else {
                            server.write(it)
                        }
                    }

                    server.readChannel.onReceiveOrNull {
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

    override fun channelInactive(ctx: ChannelHandlerContext) {
        super.channelInactive(ctx)

        job?.cancel()
    }
}

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
            pipeline.addLast("aggregator", HttpObjectAggregator(10 * 1024 * 1024))
        }

        pipeline.addLast("handler", this)
    }

    private suspend fun encryptChannel(pipeline: ChannelPipeline, sslEngine: SSLEngine) {
        sslEngine.useClientMode = true
        sslEngine.needClientAuth = false

        val handler = SslHandler(sslEngine)
        pipeline.addFirst("ssl", handler)

        handler.handshakeFuture().awaitComplete()
    }
}

abstract class ChannelAdapter(val name: String) : ChannelInboundHandlerAdapter() {
    abstract val channel: Channel
    val readChannel = kotlinx.coroutines.experimental.channels.Channel<Any>()

    suspend fun write(msg: Any, flush: Boolean = true) {
        if (flush) {
            channel.writeAndFlush(msg).awaitComplete()
        } else {
            channel.write(msg).awaitComplete()
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

        //ReferenceCountUtil.release(msg)
    }


    suspend fun disconnect() {
        if (channel.isOpen) {
            write(Unpooled.EMPTY_BUFFER, flush = true)
            channel.disconnect().awaitComplete()
        }
    }


    @Suppress("OverridingDeprecatedMember")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        runBlocking {
            disconnect()
        }
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

        readChannel.close()
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        //kproxy.log("$name -- channelReadComplete")
        super.channelReadComplete(ctx)
    }
}