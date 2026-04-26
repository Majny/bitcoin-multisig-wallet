package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.plugins.UpstreamException
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

private fun rootCause(t: Throwable): Throwable =
    generateSequence(t) { it.cause }.last()

/* Recognises common transport-level failures so we can translate them to a
 * 502 Bad Gateway instead of letting them surface as a generic 500. Checks both
 * the thrown exception and the root cause because Ktor likes to wrap. */
private fun isNetworkFailure(e: Throwable): Boolean {
    val r = rootCause(e)
    return e is ConnectException ||
            e is SocketTimeoutException ||
            e is UnknownHostException ||
            e is UnresolvedAddressException ||
            e is IOException ||
            r is ConnectException ||
            r is SocketTimeoutException ||
            r is UnknownHostException ||
            r is UnresolvedAddressException ||
            r is IOException
}

/* Wraps a downstream HTTP call. Network-level failures become
 * UpstreamException(502); other throwables propagate unchanged for StatusPages
 * to translate. Keeps each client call site free of boilerplate. */
suspend fun upstreamRequest(
    upstream: String,
    block: suspend () -> HttpResponse
): HttpResponse {
    return try {
        block()
    } catch (e: Throwable) {
        if (isNetworkFailure(e)) {
            throw UpstreamException(
                upstream = upstream,
                status = HttpStatusCode.BadGateway,
                message = e.message ?: "$upstream unreachable"
            )
        }
        throw e
    }
}


/* Throws UpstreamException for non-2xx responses, carrying the upstream's body
 * text as the message. Uses runCatching to avoid ever failing while reading the
 * body (the response may have been partially consumed). */
suspend fun HttpResponse.ensureSuccess(upstream: String): HttpResponse {
    if (!status.isSuccess()) {
        val text = runCatching { bodyAsText() }
            .recoverCatching { body<String>() }
            .getOrElse { "" }

        throw UpstreamException(upstream, status, text)
    }
    return this
}

/* Guard for clients that need the shared HttpClient attached at boot. Throws a
 * clear message if attachHttpClients was forgotten. */
fun requireAttached(isInitialized: Boolean, upstream: String) {
    if (!isInitialized) {
        throw IllegalStateException("HttpClient not attached for '$upstream' (deps.attachHttpClients(application) missing?)")
    }
}
