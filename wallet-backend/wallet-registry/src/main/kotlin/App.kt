package cz.majny.wallet.registry

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun Application.module() {
    installCommonPlugins()

    val dbCfg = Db.loadConfig()
    Db.init(dbCfg)

    val repo = RegistryRepository()
    configureRegistryRoutes(repo)
}

private fun Application.installCommonPlugins() {
    install(CallLogging) {
        level = Level.INFO
    }

    install(StatusPages) {
        exception<ContentTransformationException> { call, cause ->
            val raw = runCatching { call.receiveText() }.getOrNull()
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "invalid_json",
                    "message" to (cause.message ?: cause::class.simpleName),
                    "rawBody" to (raw ?: "<unavailable>")
                )
            )
        }

        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to "internal_error",
                    "message" to (cause.message ?: cause::class.simpleName)
                )
            )
            throw cause
        }
    }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
            }
        )
    }
}
