package kproxy

import io.netty.handler.codec.http.*
import kotlinx.coroutines.experimental.runBlocking
import kproxy.util.*
import net.lightbody.bmp.mitm.KeyStoreFileCertificateSource
import net.lightbody.bmp.mitm.RootCertificateGenerator
import net.lightbody.bmp.mitm.manager.KProxyImpersonatingMitmManager
import java.io.File
import java.net.InetSocketAddress


fun main(args: Array<String>) = runBlocking {

    val keystoreFile = File("./cert/keystore.p12")

    val rootCertificateGenerator = if (keystoreFile.exists()) {
        KeyStoreFileCertificateSource("PKCS12", keystoreFile, "privateKeyAlias", "password")
    } else {
        RootCertificateGenerator.builder().build().apply {
            saveRootCertificateAsPemFile(File("./cert/certificate.cer"))
            savePrivateKeyAsPemFile(File("./cert/private-key.pem"), "password")
            saveRootCertificateAndKey("PKCS12", keystoreFile, "privateKeyAlias", "password")
        }
    }

    val mitmManager = KProxyImpersonatingMitmManager.builder()
            .rootCertificateSource(rootCertificateGenerator)
            .build()

    ProxyServer(
            //authenticator = Authenticator(),
            connectionHandler = Interceptor(mitmManager)).start()
}

class Authenticator : ProxyAuthenticator {
    override fun authenticate(clientAddress: InetSocketAddress, username: String, password: String): UserContext? {
        return UserContext(clientAddress)
    }
}

class Interceptor(val sslEngineSource: SslEngineSource?) : ConnectionHandler {
    override fun mitm(initialRequest: HttpRequest, userContext: UserContext) = sslEngineSource

    override fun intercept(initialRequest: HttpRequest, userContext: UserContext): RequestInterceptor? {
        if(!initialRequest.isAbsoluteFormUri) {
            return ProxyHttpRequestHandler()
        } else {
            return Handler(userContext)
        }
    }
}

class Handler(val userContext: UserContext) : RequestInterceptor {
    override fun handleClientRequest(httpObject: HttpObject): HttpResponse? {
        if (httpObject is FullHttpRequest) {
            log("request from ${userContext.address} for ${httpObject.hostname}${httpObject.originFormUri}")
        }

        //return buildResponse(body = "Hello world")
        return null
    }

    override fun handleServerResponse(httpObject: HttpObject): HttpResponse? {
        if (httpObject is FullHttpResponse) {
            return httpObject.apply {
                headers().add("Hello", "World")
            }
        }
        return null
    }
}


class ProxyHttpRequestHandler : RequestInterceptor {
    override fun handleClientRequest(httpObject: HttpObject): HttpResponse? {
        return buildResponse(body = "Hello")
    }
}