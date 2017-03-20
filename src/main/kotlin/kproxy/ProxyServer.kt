package kproxy

import com.google.common.io.BaseEncoding
import com.google.common.net.HostAndPort
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.util.ReferenceCountUtil
import io.netty.util.ResourceLeakDetector
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kproxy.connections.ClientConnection
import kproxy.util.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.atomic.AtomicInteger


object EventLoops {
    val bossGroup = NioEventLoopGroup()
    val workerGroup = NioEventLoopGroup()
    val remoteConnectionsGroup = NioEventLoopGroup()
}


object Config {
    val proxyName = "KtProxy"

    val maxRequestBufferSize = 50 * 1024 * 1024
    val maxResponseBufferSize = 50 * 1024 * 1024
    val maxInitialLineLength = 8192
    val maxHeaderSize = 2 * 8192
    val maxChunkSize = 2 * 8192
}


class ProxyServer(val port: Int = 8088,
                  val config: Config = Config,
                  val authenticator: ProxyAuthenticator? = null,
                  val connectionHandler: ConnectionHandler? = null) {

    val log by kLogger()

    val activeClients: MutableList<ClientConnection> = Collections.synchronizedList(mutableListOf())

    val nextConnectionId = AtomicInteger()

    fun start() {
        runBlocking {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)

            val server = ServerBootstrap().apply {
                group(EventLoops.bossGroup, EventLoops.workerGroup)
                channelFactory(ChannelFactory { NioServerSocketChannel() })
                childHandler(object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel) = handleIncomingConnection(ch)
                })
            }

            val channel = server.bind(InetSocketAddress(port)).awaitChannel()

            val listenAddress = channel.localAddress() as InetSocketAddress

            log.info { "Proxy started at address: $listenAddress" }
        }
    }

    private fun handleIncomingConnection(channel: Channel) {
        launch(Unconfined) {

            val clientId = nextConnectionId.getAndIncrement()

            val connection = ClientConnection(clientId, config, channel)

            val initialRequest = connection.readChannel.receive() as? HttpRequest

            if (initialRequest == null || initialRequest.decoderResult().isFailure) {
                connection.writeResponse(HttpResponseStatus.BAD_GATEWAY,
                        body = "Unable to parse response from server") {
                    isKeepAlive = false
                }
                ReferenceCountUtil.release(initialRequest)
                connection.disconnectAsync()
                return@launch
            }

            val clientAddress = connection.channel.remoteAddress() as InetSocketAddress

            log.debug { "Active connections: ${activeClients.size} -- ${activeClients.joinToString { it.id.toString() }}" }
            log.info { "[$clientId] New connection from $clientAddress for ${initialRequest.uri()}" }

            val userContext = authenticateUser(initialRequest, clientAddress)

            // Unable to authenticate user, disconnect
            if (userContext == null) {
                connection.writeResponse(HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED,
                        body = "Proxy Authentication Required") {
                    headers().set(HttpHeaderNames.PROXY_AUTHENTICATE, "Basic realm=\"Restricted Files\"")
                }

                ReferenceCountUtil.release(initialRequest)
                connection.disconnectAsync()
                return@launch
            }

            val interceptor = connectionHandler?.intercept(initialRequest, userContext)
            connection.interceptor = interceptor

            val remoteAddress = try {
                HostAndPort.fromString(initialRequest.host).withDefaultPort(80).let {
                    InetSocketAddress(InetAddress.getByName(it.host), it.port)
                }
            } catch (e: UnknownHostException) {
                connection.writeResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway") {
                    isKeepAlive = false
                }

                ReferenceCountUtil.release(initialRequest)
                connection.disconnectAsync()
                return@launch
            }

            val sslEngineSource = connectionHandler?.sslEngineSource(initialRequest, userContext)

            if (initialRequest.isConnect) {
                ReferenceCountUtil.release(initialRequest)

                if (sslEngineSource == null) {
                    connection.startTunneling(remoteAddress)
                } else {
                    connection.startMitm(remoteAddress, sslEngineSource)
                }
            } else {
                connection.startReading(sslEngineSource)
                connection.readChannel.send(initialRequest)
            }

            activeClients += connection
            connection.job.invokeOnCompletion {
                activeClients -= connection
            }
        }
    }

    private fun authenticateUser(initialRequest: HttpRequest, clientAddress: InetSocketAddress): UserContext? {
        if (authenticator != null) {
            if (!initialRequest.headers().contains(HttpHeaderNames.PROXY_AUTHORIZATION)) {
                return null
            } else {
                val header = initialRequest.headers().get(HttpHeaderNames.PROXY_AUTHORIZATION)
                val value = header.substringAfter("Basic ").trim()

                try {
                    val decodedString = String(BaseEncoding.base64().decode(value), Charset.forName("UTF-8"))

                    val username = decodedString.substringBefore(":")
                    val password = decodedString.substringAfter(":")

                    return authenticator.authenticate(
                            clientAddress = clientAddress,
                            username = username,
                            password = password)
                } catch (e: Exception) {
                    log.error(e) { e.message }
                    return null
                }
            }
        } else {
            return UserContext(clientAddress)
        }
    }

}