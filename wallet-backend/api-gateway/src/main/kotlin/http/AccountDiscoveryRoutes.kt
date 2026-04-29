package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.clients.BlockchainClient
import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.dto.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/* Standalone account-discovery endpoints. The login flow embeds discovery
 * inline in /auth/trezor/login; these routes exist for clients that want to
 * re-scan without going through the full login. */
fun Route.accountDiscoveryRoutes() {
    authenticate("auth-jwt") {

    /* POST /api/v1/accounts/scan - bulk scan for a list of (xpub, derivationPath)
     * pairs, returning per-account activity metadata. Used during onboarding to
     * surface which BIP-44 accounts are worth importing. */
    post("/accounts/scan") {
        val req = call.receive<ScanAccountsRequest>()

        val scannedAccounts = req.accounts.map { account ->
            scanSingleAccount(
                blockchain = call.application.deps.blockchain,
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

    /* POST /api/v1/accounts/scan-single - one-shot variant, fingerprint via
     * ?fingerprint= query param. */
    post("/accounts/scan-single") {
        val account = call.receive<AccountToScan>()
        val fingerprint = call.request.queryParameters["fingerprint"] ?: ""

        val result = scanSingleAccount(
            blockchain = call.application.deps.blockchain,
            fingerprint = fingerprint,
            xpub = account.xpub,
            derivationPath = account.derivationPath
        )

        call.respond(result)
    }
    
    /* GET /api/v1/accounts/check-address/{address} - activity probe for a
     * single derived address. Used by the frontend when it derives addresses
     * itself and wants per-address checks. */
    get("/accounts/check-address/{address}") {
        val address = call.parameters["address"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address parameter")
        
        val result = call.application.deps.blockchain.hasActivity(address)
        call.respond(result)
    }
    }
}

/* Returns account metadata without actually deriving + scanning addresses on
 * the server. The real per-address scan happens client-side (frontend derives
 * addresses from xpub and calls /accounts/check-address for each), so this
 * function is essentially a derivation-path parser today. */
private suspend fun scanSingleAccount(
    blockchain: BlockchainClient,
    fingerprint: String,
    xpub: String,
    derivationPath: String
): ScannedAccount {
    val (scriptType, network) = parseDerivationPath(derivationPath)

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

/* Maps a BIP-44 path to (scriptType, network). Purpose 84 → WPKH, 86 → TR,
 * 49 → SH_WPKH, 44 → PKH. Coin type 1 means testnet, anything else mainnet. */
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
