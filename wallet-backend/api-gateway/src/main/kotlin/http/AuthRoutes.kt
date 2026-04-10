package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.dto.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("AuthRoutes")

fun Route.authRoutes() {

    post("/auth/trezor/login") {
        val req = call.receive<TrezorLoginRequest>()

        // 1) auth-service: gives us JWT
        val upstream = call.application.deps.auth.trezorLogin(req)

        val deviceId = JwtClaimExtractor.deviceIdFromJwt(upstream.accessToken)

        call.application.deps.auth.upsertDevice(
            UpsertDeviceRequest(
                deviceId = deviceId,
                fingerprint = req.fingerprint,
                model = req.deviceModel,
                label = req.deviceLabel
            )
        )

        // Build list of accounts to scan (backwards compat: fallback to single xpub)
        val accountsToScan: List<AccountToScan> = if (!req.accounts.isNullOrEmpty()) {
            req.accounts
        } else if (req.xpub.isNotBlank()) {
            listOf(AccountToScan(xpub = req.xpub, derivationPath = req.derivationPath))
        } else {
            emptyList()
        }

        // 2) BIP-44 account discovery: scan accounts sequentially, stop at first gap.
        //
        // Network errors MUST NOT fail the whole login — discovery is best-effort.
        // If mempool.space blinks, we log and skip to the next account. The user
        // still gets a valid session; any missing wallets can be recovered via a
        // refresh once the network is stable again.
        for (account in accountsToScan) {
            try {
                val walletCreate = buildSingleSigWalletCreate(
                    deviceId = deviceId,
                    fingerprint = req.fingerprint,
                    xpub = account.xpub,
                    derivationPath = account.derivationPath,
                    deviceLabel = req.deviceLabel
                )

                // Derive first 5 receive addresses and check blockchain activity
                val hasActivity = checkAccountActivity(
                    registry = call.application.deps.registry,
                    blockchain = call.application.deps.blockchain,
                    descriptor = walletCreate.receiveDescriptor,
                    network = walletCreate.network
                )

                if (hasActivity) {
                    log.info("Account {} has activity, creating wallet {}", account.derivationPath, walletCreate.walletId)
                    try {
                        call.application.deps.registry.createWallet(walletCreate)
                    } catch (_: Exception) {
                        // wallet already exists from previous login — OK
                    }
                    try {
                        call.application.deps.registry.attachMember(
                            walletId = walletCreate.walletId,
                            req = MemberAttach(deviceId = deviceId)
                        )
                    } catch (_: Exception) {
                        // member already attached — OK
                    }
                } else {
                    log.info("Account {} has no activity — BIP-44 gap limit reached, stopping discovery", account.derivationPath)
                    break
                }
            } catch (e: Exception) {
                // Network error or upstream failure — log and skip. Do not break,
                // do not propagate: a Connection reset on one account should not
                // stop the user from logging in.
                log.warn("Account {} scan failed ({}), skipping", account.derivationPath, e.message)
            }
        }

        // 3) source-of-truth wallets
        val wallets = call.application.deps.registry.listWallets(deviceId)

        call.respond(
            TrezorLoginResponse(
                accessToken = upstream.accessToken,
                refreshToken = upstream.refreshToken,
                user = upstream.user.copy(wallets = wallets.map { it.toGatewayWalletSummary() })
            )
        )
    }

    post("/auth/token/refresh") {
        val req = call.receive<RefreshTokenRequest>()
        val upstream = call.application.deps.auth.refresh(req)

        // rotation of refresh token
        call.respond(
            RefreshTokenResponse(
                accessToken = upstream.accessToken,
                refreshToken = upstream.refreshToken
            )
        )
    }
}

/**
 * Derive first N receive addresses and check if any have blockchain activity.
 * Stops early on first active address found.
 */
private suspend fun checkAccountActivity(
    registry: cz.majny.wallet.gateway.clients.RegistryClient,
    blockchain: cz.majny.wallet.gateway.clients.BlockchainClient,
    descriptor: String,
    network: String
): Boolean {
    val addresses = registry.deriveAddresses(descriptor, network, count = 5)
    for (addr in addresses) {
        val resp = blockchain.hasActivity(addr, network)
        if (resp.hasActivity) return true
    }
    return false
}

/**
 * Builds a CreateWalletRequest for a single-sig wallet from xpub + derivationPath.
 */
private fun buildSingleSigWalletCreate(
    deviceId: String,
    fingerprint: String,
    xpub: String,
    derivationPath: String,
    deviceLabel: String?
): CreateWalletRequest {
    val parts = derivationPath
        .removePrefix("m/")
        .split("/")
        .map { it.trim() }

    val purpose = parts.getOrNull(0)?.removeSuffix("'")?.toIntOrNull()
    val coinType = parts.getOrNull(1)?.removeSuffix("'")?.toIntOrNull()
    val accountIndex = parts.getOrNull(2)?.removeSuffix("'")?.toIntOrNull() ?: 0

    val network = when (coinType) {
        1 -> "testnet"
        else -> "mainnet"
    }

    val scriptType = when (purpose) {
        86 -> "TR"
        else -> "WPKH" // default 84
    }

    // descriptor origin: [fp/84h/0h/0h]
    val origin = derivationPath
        .removePrefix("m/")
        .split("/")
        .joinToString("/") { seg ->
            if (seg.endsWith("'")) seg.removeSuffix("'") + "h" else seg
        }

    val descPrefix = when (scriptType) {
        "TR" -> "tr"
        else -> "wpkh"
    }

    val receiveDescriptor = "$descPrefix([$fingerprint/$origin]$xpub/0/*)"
    val changeDescriptor = "$descPrefix([$fingerprint/$origin]$xpub/1/*)"

    val walletId = "wallet-${fingerprint.lowercase()}-$network-$scriptType-$accountIndex"

    return CreateWalletRequest(
        walletId = walletId,
        network = network,
        type = "SINGLE_SIG",
        scriptType = scriptType,
        accountIndex = accountIndex,
        label = deviceLabel ?: "Account #${accountIndex + 1}",
        receiveDescriptor = receiveDescriptor,
        changeDescriptor = changeDescriptor,
        members = listOf(MemberAttach(deviceId = deviceId))
    )
}
