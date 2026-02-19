package cz.majny.wallet.psbt

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cz.majny.wallet.psbt.api.psbtRoutes
import cz.majny.wallet.psbt.client.BlockchainClient
import cz.majny.wallet.psbt.client.RegistryClient
import cz.majny.wallet.psbt.db.PsbtRepository
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

internal val log = LoggerFactory.getLogger("PsbtService")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8085
    
    // Database setup
    val ds = createDataSource()
    runMigrations(ds)
    Database.connect(ds)
    
    // External service clients
    val blockchainUrl = System.getenv("BLOCKCHAIN_SERVICE_URL") ?: "http://localhost:8086"
    val registryUrl = System.getenv("REGISTRY_URL") ?: "http://localhost:8082"
    
    val blockchainClient = BlockchainClient(blockchainUrl)
    val registryClient = RegistryClient(registryUrl)
    val repository = PsbtRepository()
    
    log.info("Starting PSBT Service on port {}", port)
    log.info("Blockchain service: {}", blockchainUrl)
    log.info("Registry service: {}", registryUrl)
    
    embeddedServer(Netty, port = port) {
        configureApp(repository, blockchainClient, registryClient)
    }.start(wait = true)
}

fun Application.configureApp(
    repository: PsbtRepository,
    blockchainClient: BlockchainClient,
    registryClient: RegistryClient
) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }
    
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            cz.majny.wallet.psbt.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Unknown error"))
            )
        }
    }
    
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "psbt-service"))
        }
        
        psbtRoutes(repository, blockchainClient, registryClient)
    }
}

private fun createDataSource(): HikariDataSource {
    val config = HikariConfig().apply {
        jdbcUrl = System.getenv("DATABASE_URL") 
            ?: "jdbc:postgresql://localhost:5432/wallet_psbt"
        username = System.getenv("DATABASE_USER") ?: "wallet"
        password = System.getenv("DATABASE_PASSWORD") ?: "wallet"
        maximumPoolSize = 5
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
    }
    return HikariDataSource(config)
}

private fun runMigrations(ds: HikariDataSource) {
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .load()
        .migrate()
}
