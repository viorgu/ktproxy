package kproxy

import com.google.common.net.HostAndPort
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException

object EventLoops {
    val bossGroup = NioEventLoopGroup()
    val workerGroup = NioEventLoopGroup()
    val serverConnectionsGroup = NioEventLoopGroup()
}

class ProxyServer(val port: Int = 8088,
                  val authenticator: Authenticator? = null,
                  val interceptor: RequestInterceptor? = null) {

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

            val initialRequest = connection.readChannel.receive() as HttpRequest

            val userContext = authenticator?.authenticate(initialRequest) ?: UserContext()
            val requestHandler = interceptor?.intercept(initialRequest, userContext)

            val hostAndPort = initialRequest.identifyHostAndPort()

            val remoteAddress = try {
                HostAndPort.fromString(hostAndPort).withDefaultPort(80).let {
                    InetSocketAddress(InetAddress.getByName(it.host), it.port)
                }
            } catch (e: UnknownHostException) {
                connection.write(buildResponse(HttpResponseStatus.BAD_GATEWAY, body = "Bad Gateway") {
                    HttpUtil.setKeepAlive(this, false)
                })
                connection.disconnect()
                return@launch
            }

            if (initialRequest.isConnect) {
                val mitmManager = interceptor?.getMitmManager(initialRequest, userContext)

                connection.write(buildResponse(HttpResponseStatus(200, "Connection established")))
                if(mitmManager == null) {
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