package kproxy

import io.netty.handler.codec.http.HttpRequest
import kotlinx.coroutines.experimental.runBlocking
import net.lightbody.bmp.mitm.manager.KProxyImpersonatingMitmManager


fun main(args: Array<String>) = runBlocking {
    val mitmManager = KProxyImpersonatingMitmManager.builder().build()


    ProxyServer(interceptor = object : RequestInterceptor {
        override fun getMitmManager(request: HttpRequest, userContext: UserContext) = mitmManager

        override fun intercept(request: HttpRequest, userContext: UserContext) = null
    }).start()
}



