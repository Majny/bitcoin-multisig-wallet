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

/**
 * HTTP client for communicating with explorer-service.
 *
 * Psbt-service uses this for privacy-critical address lookups: finding the next
 * unused change address so every outgoing PSBT writes change to a fresh output.
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

    /**
     * Vrátí první nepoužitou change adresu pro danou peněženku.
     * Explorer zkontroluje on-chain aktivitu všech change adres v DB a vrátí první
     * bez aktivity. Výsledek MUSÍ být použit — jinak hrozí address reuse.
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
