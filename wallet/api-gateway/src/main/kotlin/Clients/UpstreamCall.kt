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


suspend fun HttpResponse.ensureSuccess(upstream: String): HttpResponse {
    if (!status.isSuccess()) {
        // co nejrobustnější získání textu těla (ať to nikdy nespadne na další výjimce)
        val text = runCatching { bodyAsText() }
            .recoverCatching { body<String>() }
            .getOrElse { "" }

        throw UpstreamException(upstream, status, text)
    }
    return this
}

fun requireAttached(isInitialized: Boolean, upstream: String) {
    if (!isInitialized) {
        throw IllegalStateException("HttpClient not attached for '$upstream' (deps.attachHttpClients(application) missing?)")
    }
}
