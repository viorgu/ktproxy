package ktproxy

import io.netty.handler.codec.http.*
import kotlinx.coroutines.experimental.runBlocking
import ktproxy.util.*
import net.lightbody.bmp.mitm.KeyStoreFileCertificateSource
import net.lightbody.bmp.mitm.RootCertificateGenerator
import net.lightbody.bmp.mitm.manager.KtProxyImpersonatingMitmManager
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

    val sslEngineSource = KtProxyImpersonatingMitmManager.builder()
            .rootCertificateSource(rootCertificateGenerator)
            .build()

    ProxyServer(
            //authenticator = Authenticator(),
            clientConnectionHandler = Interceptor(sslEngineSource)).start()
}

class Authenticator : ProxyAuthenticator {
    override fun authenticate(clientAddress: InetSocketAddress, username: String, password: String): UserContext? {
        return UserContext(clientAddress)
    }
}

class Interceptor(val sslEngineSource: SslEngineSource?) : ClientConnectionHandler {
    override fun sslEngineSource(initialRequest: HttpRequest, userContext: UserContext) = sslEngineSource

    override fun intercept(initialRequest: HttpRequest, userContext: UserContext): RequestInterceptor? {
        if (!initialRequest.isAbsoluteFormUri) {
            if (initialRequest.originFormUri.startsWith("/r/")) {
                return Reverse()
            } else {
                return ProxyHttpRequestHandler()
            }
        } else {
            return Handler(userContext)
        }
    }
}

class Handler(val userContext: UserContext) : RequestInterceptor {
    val log by kLogger()

    override fun handleClientRequest(httpObject: HttpObject): HttpResponse? {
        if (httpObject is FullHttpRequest) {
            log.info { "request from ${userContext.address} for ${httpObject.hostname}${httpObject.originFormUri}" }
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
    val log by kLogger()

    override fun handleClientRequest(httpObject: HttpObject): HttpResponse? {
        if (httpObject is FullHttpRequest) {
            log.info { "direct request for ${httpObject.originFormUri} $httpObject" }
        }
        return buildResponse(body = "Hello")
    }
}


class Reverse : RequestInterceptor {
    val log by kLogger()

    override fun handleClientRequest(httpObject: HttpObject): HttpResponse? {
        if (httpObject is HttpRequest && httpObject.originFormUri.startsWith("/r/")) {
            val remoteUri = httpObject.originFormUri.substringAfter("/r/")

            log.info { "rewriting ${httpObject.uri()} to $remoteUri" }

            httpObject.uri = "/" + remoteUri.substringAfter("/", missingDelimiterValue = "")
            httpObject.headers().set(HttpHeaderNames.HOST, remoteUri.substringBefore("/"))
        }

        return null
    }

    override fun handleServerResponse(httpObject: HttpObject): HttpResponse? {
        if (httpObject is HttpResponse) {
            if (httpObject.isRedirect()) {
                val location = httpObject.headers()[HttpHeaderNames.LOCATION]

                val locationParts = HTTP_URL_MATCH.matchEntire(location) ?: return null
                val newLocation = buildString {
                    append("http://localhost:8088/r/")
                    append(locationParts.groupValues[2])
                    if (locationParts.groupValues[1].toLowerCase() == "https") {
                        append(":443")
                    }
                    append(locationParts.groupValues[3])
                }

                httpObject.headers()[HttpHeaderNames.LOCATION] = newLocation

                log.info { "rewriting location $location to $newLocation" }
            }
        }

        log.info { httpObject }

        return super.handleServerResponse(httpObject)
    }

    fun HttpResponse.isRedirect(): Boolean {
        return status() == HttpResponseStatus.FOUND || status() == HttpResponseStatus.MOVED_PERMANENTLY
    }
}