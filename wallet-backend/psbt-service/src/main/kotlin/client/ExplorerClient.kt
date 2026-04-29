package cz.majny.wallet.psbt.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * HTTP client for explorer-service. psbt-service uses this for the single
 * privacy-critical lookup - fetching the next unused change address so every
 * outgoing PSBT writes change to a fresh output, never a reused one.
 */
class ExplorerClient(private val baseUrl: String) {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    /*
     * Returns the first change address with no on-chain activity. Explorer
     * walks the pre-derived change pool and derives a new one past the gap
     * limit if every stored address is already used. Caller must consume the
     * result - skipping it and re-calling would return the same address and
     * break the no-reuse invariant.
     */
    suspend fun getNextChangeAddress(walletId: String): NextAddressDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/change-address").body()
    }
}

@Serializable
data class NextAddressDto(
    val walletId: String,
    val address: String,
    val index: Int,
    val isNew: Boolean = true
)
