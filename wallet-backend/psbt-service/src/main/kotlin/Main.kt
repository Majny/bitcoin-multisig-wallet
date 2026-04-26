package cz.majny.wallet.psbt

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cz.majny.wallet.psbt.api.psbtRoutes
import cz.majny.wallet.psbt.client.BlockchainClient
import cz.majny.wallet.psbt.client.ExplorerClient
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

internal val log = LoggerFactory.getLogger("PsbtService")

/*
 * psbt-service entry point. Boots the database, wires the upstream service
 * clients (blockchain, registry, explorer) and starts the Ktor server on
 * port 8085 (default). Also kicks off the background stale-PSBT cleanup job.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8085

    val ds = createDataSource()
    runMigrations(ds)
    Database.connect(ds)

    val blockchainUrl = System.getenv("BLOCKCHAIN_SERVICE_URL") ?: "http://localhost:8086"
    val registryUrl = System.getenv("REGISTRY_URL") ?: "http://localhost:8082"
    val explorerUrl = System.getenv("EXPLORER_SERVICE_URL") ?: "http://localhost:8083"

    val blockchainClient = BlockchainClient(blockchainUrl)
    val registryClient = RegistryClient(registryUrl)
    val explorerClient = ExplorerClient(explorerUrl)
    val repository = PsbtRepository()

    log.info("Starting PSBT Service on port {}", port)
    log.info("Blockchain service: {}", blockchainUrl)
    log.info("Registry service: {}", registryUrl)
    log.info("Explorer service: {}", explorerUrl)

    startStalePsbtCleanup(repository)

    embeddedServer(Netty, port = port) {
        configureApp(repository, blockchainClient, registryClient, explorerClient)
    }.start(wait = true)
}

/*
 * Background coroutine that periodically deletes pending/signed PSBTs older
 * than PSBT_TTL_DAYS (default 7d). Without this, an abandoned multisig draft
 * would lock its UTXOs forever via the reservation set. Cleanup can be turned
 * off with PSBT_CLEANUP_ENABLED=false.
 */
private fun startStalePsbtCleanup(repository: PsbtRepository) {
    val enabled = (System.getenv("PSBT_CLEANUP_ENABLED") ?: "true").equals("true", true)
    if (!enabled) {
        log.info("PSBT cleanup disabled via PSBT_CLEANUP_ENABLED")
        return
    }
    val ttlDays = (System.getenv("PSBT_TTL_DAYS") ?: "7").toLongOrNull() ?: 7L
    val intervalMinutes = (System.getenv("PSBT_CLEANUP_INTERVAL_MINUTES") ?: "60").toLongOrNull() ?: 60L
    CoroutineScope(Dispatchers.IO).launch {
        while (true) {
            try {
                val cutoff = OffsetDateTime.now().minusDays(ttlDays)
                val removed = repository.deleteStale(cutoff)
                if (removed > 0) log.info("Cleaned up {} stale pending/signed PSBTs older than {}", removed, cutoff)
            } catch (e: Exception) {
                // Single-iteration failure shouldn't kill the cleanup loop —
                // log and try again on the next tick.
                log.warn("PSBT cleanup failed", e)
            }
            delay(intervalMinutes * 60_000L)
        }
    }
}

fun Application.configureApp(
    repository: PsbtRepository,
    blockchainClient: BlockchainClient,
    registryClient: RegistryClient,
    explorerClient: ExplorerClient
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
        
        psbtRoutes(repository, blockchainClient, registryClient, explorerClient)
    }
}

/*
 * Hikari pool with REPEATABLE_READ isolation — needed because the
 * sign-trezor flow does a "read current_sigs, decide status, write back"
 * sequence that must observe a stable snapshot.
 */
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
