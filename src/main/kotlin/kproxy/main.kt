package kproxy

import io.netty.handler.codec.http.HttpRequest
import kotlinx.coroutines.experimental.runBlocking
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
            interceptor = Interceptor(mitmManager)).start()
}

class Authenticator : ProxyAuthenticator {
    override fun authenticate(clientAddress: InetSocketAddress, username: String, password: String): UserContext? {
        return UserContext(clientAddress)
    }
}

class Interceptor(val mitmManager: MitmManager?) : RequestInterceptor {
    override fun getMitmManager(request: HttpRequest, userContext: UserContext) = mitmManager

    override fun intercept(request: HttpRequest, userContext: UserContext) = null
}
