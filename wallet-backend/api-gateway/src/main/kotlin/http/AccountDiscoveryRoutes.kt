package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.clients.MempoolClient
import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.dto.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Account discovery routes - scan blockchain for wallet activity using Mempool.space API.
 * 
 * Uses address activity check (tx_count > 0) instead of scantxoutset RPC.
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
                mempool = call.application.deps.mempool,
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
            mempool = call.application.deps.mempool,
            fingerprint = fingerprint,
            xpub = account.xpub,
            derivationPath = account.derivationPath
        )

        call.respond(result)
    }
    
    /**
     * GET /api/v1/accounts/check-address/{address}
     * 
     * Check if a single address has any activity.
     * Simple wrapper around Mempool hasActivity check.
     */
    get("/accounts/check-address/{address}") {
        val address = call.parameters["address"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address parameter")
        
        val hasActivity = call.application.deps.mempool.hasActivity(address)
        call.respond(mapOf(
            "address" to address,
            "hasActivity" to hasActivity
        ))
    }
}

/**
 * Scans a single account by checking if addresses have activity via Mempool.space API.
 * 
 * NOTE: This is a simplified implementation. For full account discovery with xpub,
 * the frontend should derive addresses and check them individually, or use a 
 * dedicated xpub indexer service.
 * 
 * Current implementation returns metadata about the account without scanning
 * (since we cannot derive addresses server-side without the xpub derivation library).
 */
private suspend fun scanSingleAccount(
    mempool: MempoolClient,
    fingerprint: String,
    xpub: String,
    derivationPath: String
): ScannedAccount {
    val (scriptType, network) = parseDerivationPath(derivationPath)

    // NOTE: Full xpub scanning requires address derivation from xpub.
    // This would need a Bitcoin library like bitcoinj or BitcoinKit.
    // For now, we return the account info and let the frontend
    // derive addresses and call /accounts/check-address for each.
    
    return ScannedAccount(
        derivationPath = derivationPath,
        xpub = xpub,
        hasActivity = false,  // Unknown without address derivation
        utxoCount = 0,
        totalSats = 0,
        scriptType = scriptType,
        network = network
    )
}

/**
 * Parse derivation path to extract script type and network.
 * Examples:
 *   m/84'/0'/0' -> (WPKH, mainnet)
 *   m/84'/1'/0' -> (WPKH, testnet)
 *   m/86'/0'/0' -> (TR, mainnet)
 *   m/49'/0'/0' -> (SH_WPKH, mainnet)
 */
private fun parseDerivationPath(path: String): Pair<String, String> {
    val parts = path.removePrefix("m/").split("/")
    val purpose = parts.getOrNull(0)?.removeSuffix("'")?.toIntOrNull() ?: 84
    val coinType = parts.getOrNull(1)?.removeSuffix("'")?.toIntOrNull() ?: 0

    val network = if (coinType == 1) "testnet" else "mainnet"

    val scriptType = when (purpose) {
        86 -> "TR"
        49 -> "SH_WPKH"
        44 -> "PKH"
        else -> "WPKH"  // 84 is default
    }

    return Pair(scriptType, network)
}
