package cz.majny.wallet.explorer.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("RegistryClient")

/**
 * HTTP client pro wallet-registry.
 * Získává seznam adres peněženky.
 */
class RegistryClient(
    private val baseUrl: String,
    private val client: HttpClient
) {

    /**
     * Vrátí všechny odvozené adresy peněženky.
     * Volitelně filtrováno podle typu (receive/change).
     */
    suspend fun getAddresses(walletId: String, type: String? = null): List<WalletAddress> {
        log.debug("Getting addresses for wallet: {} type: {}", walletId, type)
        val response: WalletAddressesResponse = client.get("$baseUrl/registry/wallets/$walletId/addresses") {
            type?.let { parameter("type", it) }
        }.body()
        return response.addresses
    }

    /**
     * Požádá registry o odvození nové adresy za existujícím gap limitem a zápis do DB.
     * Používá se v privacy-critical flow (getNext*Address): když jsou všechny předem
     * odvozené adresy spotřebované, musíme rozšířit okno bez reusování.
     *
     * Idempotentní — pokud už adresa na daném indexu existuje, registry ji vrátí.
     */
    suspend fun deriveAdditionalAddress(
        walletId: String,
        type: String,
        index: Int
    ): WalletAddress {
        log.info("Requesting derivation: wallet={} type={} index={}", walletId, type, index)
        return client.post("$baseUrl/registry/wallets/$walletId/addresses/derive") {
            contentType(ContentType.Application.Json)
            setBody(DeriveAdditionalAddressRequest(type = type, index = index))
        }.body()
    }

}

@Serializable
data class DeriveAdditionalAddressRequest(
    val type: String,
    val index: Int
)

// ============ DTOs (mirror wallet-registry responses) ============

@Serializable
data class WalletAddress(
    val walletId: String,
    val address: String,
    val index: Int,
    val type: String    // "receive" or "change"
)

@Serializable
data class WalletAddressesResponse(
    val walletId: String,
    val addresses: List<WalletAddress>
)
