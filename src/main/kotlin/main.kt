import com.google.common.net.HostAndPort
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.experimental.channels.Channel as AsyncChannel


fun main(args: Array<String>) = runBlocking {

    val bossGroup = NioEventLoopGroup() // (1)
    val workerGroup = NioEventLoopGroup()

    val server = ServerBootstrap().apply {
        group(bossGroup, workerGroup)
        channelFactory(ChannelFactory { NioServerSocketChannel() })
        childHandler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) = handleIncomingConnection(ch)
        })
    }

    val channel = server.bind(InetSocketAddress(8088)).awaitChannel()

    val listenAddress = channel.localAddress() as InetSocketAddress

    log("Proxy started at address: $listenAddress")
}


fun handleIncomingConnection(channel: Channel) {
    launch(Unconfined) {
        val connection = ClientConnection(channel)

        val initialRequest = connection.receiveChannel.receive() as HttpRequest
        val hostAndPort = initialRequest.identifyHostAndPort()

        val remoteAddress = HostAndPort.fromString(hostAndPort).withDefaultPort(80).let {
            InetSocketAddress(InetAddress.getByName(it.host), it.port)
        }

        if (initialRequest.isConnect) {
            connection.write(DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus(200, "Connection established")))
            connection.startTunneling(remoteAddress)
        } else {
            connection.startReading()
            connection.receiveChannel.send(initialRequest)
        }
    }
}

