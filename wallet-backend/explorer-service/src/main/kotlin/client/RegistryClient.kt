package cz.majny.wallet.explorer.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("RegistryClient")

/**
 * HTTP client pro wallet-registry.
 * Získává seznam adres, detail peněženky atd.
 */
class RegistryClient(
    private val baseUrl: String,
    private val client: HttpClient
) {

    /**
     * Vrátí detail peněženky včetně cosignerů a descriptorů.
     */
    suspend fun getWallet(walletId: String): WalletDetail {
        log.debug("Getting wallet detail: {}", walletId)
        return client.get("$baseUrl/registry/wallets/$walletId").body()
    }

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
     * Vrátí konkrétní adresu na daném indexu.
     */
    suspend fun getAddress(walletId: String, type: String, index: Int): WalletAddress {
        return client.get("$baseUrl/registry/wallets/$walletId/addresses") {
            parameter("type", type)
            parameter("index", index)
        }.body()
    }
}

// ============ DTOs (mirror wallet-registry responses) ============

@Serializable
data class WalletDetail(
    val walletId: String,
    val network: String,
    val type: String,
    val scriptType: String,
    val m: Int? = null,
    val n: Int? = null,
    val accountIndex: Int = 0,
    val birthHeight: Int? = null,
    val label: String? = null,
    val receiveDescriptor: String = "",
    val changeDescriptor: String = "",
    val cosigners: List<CosignerInWallet> = emptyList(),
    val members: List<MemberAttach> = emptyList()
)

@Serializable
data class CosignerInWallet(
    val idx: Int,
    val cosignerId: String,
    val fingerprint: String,
    val originPath: String,
    val xpubRoot: String
)

@Serializable
data class MemberAttach(
    val deviceId: String,
    val cosignerIdx: Int? = null
)

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
