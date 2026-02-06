package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.dto.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * Account discovery routes - scan blockchain for wallet activity.
 */
fun Route.accountDiscoveryRoutes() {

    /**
     * POST /api/v1/accounts/scan
     *
     * Scans multiple derivation paths to find accounts with activity.
     * This is used during initial Trezor connection to discover all used accounts.
     *
     * Body: {
     *   "fingerprint": "abcd1234",
     *   "accounts": [
     *     { "xpub": "xpub...", "derivationPath": "m/84'/0'/0'" },
     *     { "xpub": "xpub...", "derivationPath": "m/84'/0'/1'" },
     *     { "xpub": "xpub...", "derivationPath": "m/86'/0'/0'" }
     *   ]
     * }
     */
    post("/accounts/scan") {
        val req = call.receive<ScanAccountsRequest>()

        val scannedAccounts = req.accounts.map { account ->
            scanSingleAccount(
                call.application.deps,
                fingerprint = req.fingerprint,
                xpub = account.xpub,
                derivationPath = account.derivationPath
            )
        }

        call.respond(
            ScanAccountsResponse(
                fingerprint = req.fingerprint,
                accounts = scannedAccounts
            )
        )
    }

    /**
     * POST /api/v1/accounts/scan-single
     *
     * Scan a single account for activity.
     * Useful for checking individual derivation paths.
     */
    post("/accounts/scan-single") {
        val account = call.receive<AccountToScan>()
        val fingerprint = call.request.queryParameters["fingerprint"] ?: ""

        val result = scanSingleAccount(
            call.application.deps,
            fingerprint = fingerprint,
            xpub = account.xpub,
            derivationPath = account.derivationPath
        )

        call.respond(result)
    }
}

/**
 * Scans a single account by:
 * 1. Building descriptor from xpub + derivation path
 * 2. Calling scantxoutset on Bitcoin Core via Node Proxy
 * 3. Returning activity summary
 */
private suspend fun scanSingleAccount(
    deps: cz.majny.wallet.gateway.GatewayDeps,
    fingerprint: String,
    xpub: String,
    derivationPath: String
): ScannedAccount {
    val (scriptType, network, descPrefix) = parseDerivationPath(derivationPath)

    // Build descriptor origin: [fingerprint/84h/0h/0h]
    val origin = derivationPath
        .removePrefix("m/")
        .split("/")
        .joinToString("/") { seg ->
            if (seg.endsWith("'")) seg.removeSuffix("'") + "h" else seg
        }

    // Build full descriptor for receive addresses (external chain)
    // Format: wpkh([fingerprint/84h/0h/0h]xpub.../0/*)
    val receiveDescriptor = "$descPrefix([$fingerprint/$origin]$xpub/0/*)"

    // For scantxoutset, we need to use the "range" action with descriptor
    // Format: scantxoutset "start" ["desc(descriptor)#checksum"]
    // The descriptor needs to specify the range we want to scan

    val rpcRequest = JsonRpcRequest(
        method = "scantxoutset",
        params = listOf(
            "start",
            listOf(mapOf(
                "desc" to receiveDescriptor,
                "range" to 100  // scan first 100 addresses
            ))
        )
    )

    return try {
        val response = deps.nodeProxy.rpcCall(rpcRequest)

        if (response.error != null) {
            // If scan fails, return empty account
            ScannedAccount(
                derivationPath = derivationPath,
                xpub = xpub,
                hasActivity = false,
                utxoCount = 0,
                totalSats = 0,
                scriptType = scriptType,
                network = network
            )
        } else {
            // Parse result
            val result = response.result
            val utxoCount = extractInt(result, "txouts") ?: 0
            val totalBtc = extractDouble(result, "total_amount") ?: 0.0
            val totalSats = (totalBtc * 100_000_000).toLong()

            ScannedAccount(
                derivationPath = derivationPath,
                xpub = xpub,
                hasActivity = utxoCount > 0,
                utxoCount = utxoCount,
                totalSats = totalSats,
                scriptType = scriptType,
                network = network
            )
        }
    } catch (e: Exception) {
        // On error, return empty account
        ScannedAccount(
            derivationPath = derivationPath,
            xpub = xpub,
            hasActivity = false,
            utxoCount = 0,
            totalSats = 0,
            scriptType = scriptType,
            network = network
        )
    }
}

/**
 * Parse derivation path to extract script type and network.
 * Examples:
 *   m/84'/0'/0' -> (WPKH, mainnet, wpkh)
 *   m/84'/1'/0' -> (WPKH, testnet, wpkh)
 *   m/86'/0'/0' -> (TR, mainnet, tr)
 *   m/49'/0'/0' -> (SH_WPKH, mainnet, sh(wpkh))
 */
private fun parseDerivationPath(path: String): Triple<String, String, String> {
    val parts = path.removePrefix("m/").split("/")
    val purpose = parts.getOrNull(0)?.removeSuffix("'")?.toIntOrNull() ?: 84
    val coinType = parts.getOrNull(1)?.removeSuffix("'")?.toIntOrNull() ?: 0

    val network = if (coinType == 1) "testnet" else "mainnet"

    val (scriptType, descPrefix) = when (purpose) {
        86 -> "TR" to "tr"
        49 -> "SH_WPKH" to "sh(wpkh"
        44 -> "PKH" to "pkh"
        else -> "WPKH" to "wpkh"  // 84 is default
    }

    return Triple(scriptType, network, descPrefix)
}

/**
 * Helper to extract Int from Any? (JSON result).
 */
private fun extractInt(obj: Any?, key: String): Int? {
    return when (obj) {
        is Map<*, *> -> (obj[key] as? Number)?.toInt()
        is JsonObject -> obj[key]?.jsonPrimitive?.intOrNull
        else -> null
    }
}

/**
 * Helper to extract Double from Any? (JSON result).
 */
private fun extractDouble(obj: Any?, key: String): Double? {
    return when (obj) {
        is Map<*, *> -> (obj[key] as? Number)?.toDouble()
        is JsonObject -> obj[key]?.jsonPrimitive?.doubleOrNull
        else -> null
    }
}
