package cz.majny.wallet.gateway.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val details: Map<String, String>? = null
)

class UpstreamException(
    val upstream: String,
    val status: HttpStatusCode,
    message: String
) : RuntimeException(message)

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
