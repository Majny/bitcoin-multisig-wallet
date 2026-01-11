package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.dto.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes() {

    post("/auth/trezor/login") {
        val req = call.receive<TrezorLoginRequest>()

        // 1) auth-service: gives us JWT
        val upstream = call.application.deps.auth.trezorLogin(req)

        val deviceId = JwtClaimExtractor.deviceIdFromJwt(upstream.accessToken)

        call.application.deps.registry.upsertDevice(
            UpsertDeviceRequest(
                deviceId = deviceId,
                fingerprint = req.fingerprint,
                model = req.deviceModel,
                label = req.deviceLabel
            )
        )

        val walletCreate = buildSingleSigWalletCreate(
            deviceId = deviceId,
            fingerprint = req.fingerprint,
            xpub = req.xpub,
            derivationPath = req.derivationPath,
            deviceLabel = req.deviceLabel
        )

        // create wallet
        call.application.deps.registry.createWallet(walletCreate)

        // attach membership
        call.application.deps.registry.attachMember(
            walletId = walletCreate.walletId,
            req = MemberAttach(deviceId = deviceId)
        )

        // source-of-truth wallets
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

// TODO: more wallets
/**
 * DEV version single-sig of import: creates wallets + descriptors.
 * derivationPath "m/84'/0'/0'" apod.
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
