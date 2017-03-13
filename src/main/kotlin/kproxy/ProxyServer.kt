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
import io.netty.handler.codec.http.HttpUtil
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kproxy.connections.ClientConnection
import kproxy.util.awaitChannel
import kproxy.util.host
import kproxy.util.isConnect
import kproxy.util.log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.nio.charset.Charset


object EventLoops {
    val bossGroup = NioEventLoopGroup()
    val workerGroup = NioEventLoopGroup()
    val serverConnectionsGroup = NioEventLoopGroup()
}

class ProxyServer(val port: Int = 8088,
                  val authenticator: ProxyAuthenticator? = null,
                  val connectionHandler: ConnectionHandler? = null) {

    fun start() {
        runBlocking {
            val server = ServerBootstrap().apply {
                group(EventLoops.bossGroup, EventLoops.workerGroup)
                channelFactory(ChannelFactory { NioServerSocketChannel() })
                childHandler(object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel) = handleIncomingConnection(ch)
                })
            }

            val channel = server.bind(InetSocketAddress(port)).awaitChannel()

            val listenAddress = channel.localAddress() as InetSocketAddress

            log("Proxy started at address: $listenAddress")
        }
    }

    private fun handleIncomingConnection(channel: Channel) {
        launch(Unconfined) {
            val connection = ClientConnection(channel)

            val initialRequest = connection.readChannel.receive() as? HttpRequest

            if (initialRequest == null || initialRequest.decoderResult().isFailure) {
                connection.writeResponse(HttpResponseStatus.BAD_GATEWAY,
                        body = "Unable to parse response from server") {
                    HttpUtil.setKeepAlive(this, false)
                }
                ReferenceCountUtil.release(initialRequest)
                connection.disconnectAsync()
                return@launch
            }

            val clientAddress = connection.channel.remoteAddress() as InetSocketAddress

            log("New connection from $clientAddress for ${initialRequest.uri()}")

            val userContext = if (authenticator != null) {
                if (!initialRequest.headers().contains(HttpHeaderNames.PROXY_AUTHORIZATION)) {
                    null
                } else {
                    val header = initialRequest.headers().get(HttpHeaderNames.PROXY_AUTHORIZATION)
                    val value = header.substringAfter("Basic ").trim()

                    try {
                        val decodedString = String(BaseEncoding.base64().decode(value), Charset.forName("UTF-8"))

                        val username = decodedString.substringBefore(":")
                        val password = decodedString.substringAfter(":")

                        authenticator.authenticate(
                                clientAddress = clientAddress,
                                username = username,
                                password = password)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
            } else {
                UserContext(clientAddress)
            }

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
                    HttpUtil.setKeepAlive(this, false)
                }

                ReferenceCountUtil.release(initialRequest)
                connection.disconnectAsync()
                return@launch
            }

            if (initialRequest.isConnect) {
                val mitmManager = connectionHandler?.mitm(initialRequest, userContext)

                ReferenceCountUtil.release(initialRequest)

                connection.writeResponse(HttpResponseStatus(200, "Connection established"))
                if (mitmManager == null) {
                    connection.startTunneling(remoteAddress)
                } else {
                    connection.startMitm(remoteAddress, mitmManager)
                }
            } else {
                connection.startReading()
                connection.readChannel.send(initialRequest)
            }
        }
    }
}