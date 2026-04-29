package cz.majny.wallet.explorer.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("RegistryClient")

/*
 * HTTP client for wallet-registry from explorer-service's side. Only needs
 * address lookups and on-demand derivation - we don't go near wallet
 * creation from here.
 */
class RegistryClient(
    private val baseUrl: String,
    private val client: HttpClient
) {

    /* All pre-derived addresses of the wallet, optionally filtered by
     * type (receive/change). */
    suspend fun getAddresses(walletId: String, type: String? = null): List<WalletAddress> {
        log.debug("Getting addresses for wallet: {} type: {}", walletId, type)
        val response: WalletAddressesResponse = client.get("$baseUrl/registry/wallets/$walletId/addresses") {
            type?.let { parameter("type", it) }
        }.body()
        return response.addresses
    }

    /*
     * Asks registry to derive (and persist) an address past the existing
     * gap limit. Drives the privacy-critical getNext*Address flow: once
     * every pre-derived address has on-chain activity we must extend the
     * window instead of reusing. Idempotent - existing rows are returned
     * as-is. */
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

// DTOs (mirror wallet-registry responses)

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
