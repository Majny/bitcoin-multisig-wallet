package cz.majny.wallet.gateway.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable

/* Uniform error body returned by every 4xx/5xx response from the gateway. */
@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val details: Map<String, String>? = null
)

/* Thrown by upstream clients when a downstream service replies with a non-2xx.
 * Preserves the upstream name and original status so the client handler can
 * translate it to a meaningful ErrorResponse. */
class UpstreamException(
    val upstream: String,
    val status: HttpStatusCode,
    message: String
) : RuntimeException(message)

/* Installs StatusPages with a small catch-all matrix: IllegalArgumentException
 * → 400, UpstreamException → upstream's original status, anything else → 500. */
fun Application.configureErrorHandling() {
    install(StatusPages) {

        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_request", cause.message ?: "Invalid request")
            )
        }

        exception<UpstreamException> { call, cause ->
            call.respond(
                cause.status,
                ErrorResponse(
                    error = "upstream_error",
                    message = "${cause.upstream}: ${cause.message}",
                    details = mapOf("upstream" to cause.upstream)
                )
            )
        }

        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", cause.message ?: "Unexpected server error")
            )
        }

    }
}
