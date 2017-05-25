package ktproxy.util

import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaders


@Suppress("DEPRECATION")
val hopByHopHeaders = listOf(
        HttpHeaderNames.CONNECTION,
        HttpHeaderNames.KEEP_ALIVE,
        HttpHeaderNames.PROXY_AUTHENTICATE,
        HttpHeaderNames.PROXY_AUTHORIZATION,
        HttpHeaderNames.TE,
        HttpHeaderNames.TRAILER,
        HttpHeaderNames.TRANSFER_ENCODING, //TODO
        HttpHeaderNames.UPGRADE).map { it.toLowerCase() }

// RFC2616 Section 14.10
fun stripConnectionTokens(headers: HttpHeaders) {
    headers.getAll(HttpHeaderNames.CONNECTION)
            .map { it.split(',').map(String::trim) }
            .flatten()
            .forEach {
                //TODO: Transfer-Encoding
                headers.remove(it)
            }
}

// RFC2616 Section 13.5.1
fun stripHopByHopHeaders(headers: HttpHeaders) {
    hopByHopHeaders.forEach {
        headers.remove(it)
    }
}

// RFC7230 Section 5.7.1
fun addVia(headers: HttpHeaders, pseudonym: String) {
    val via = headers.getAll(HttpHeaderNames.VIA).toMutableList()
    via += "1.1 $pseudonym"

    headers.set(HttpHeaderNames.VIA, via)
}