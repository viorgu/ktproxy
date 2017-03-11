package kproxy

import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession

interface MitmManager {

    fun serverSslEngine(peerHost: String, peerPort: Int): SSLEngine

    fun clientSslEngine(hostname: String, serverSslSession: SSLSession): SSLEngine

}