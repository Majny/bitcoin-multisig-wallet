package org.example.nodeproxy.dto

/*
 * Allowlist of bitcoind JSON-RPC methods this proxy is willing to forward.
 * Everything wallet- or node-mutating (e.g. importdescriptors, stop,
 * walletprocesspsbt) is deliberately missing — the proxy is read-only plus
 * a narrow broadcast path.
 */
object Allowlist {
    // Pure reads used for balance/fee/tx display + account discovery.
    private val read = setOf(
        "getblockchaininfo",
        "getblockhash",
        "getblock",
        "getrawtransaction",
        "getrawmempool",
        "gettxout",
        "estimatesmartfee",
        "getdescriptorinfo",
        "deriveaddresses",
        "decoderawtransaction",
        "decodepsbt",
        "finalizepsbt",
        "scantxoutset"
    )

    // Limited write surface — just enough to validate and broadcast a tx
    // that was signed client-side. Neither touches on-node wallet state.
    private val write = setOf(
        "testmempoolaccept",
        "sendrawtransaction"
    )

    fun isAllowed(method: String) = method.lowercase() in read || method.lowercase() in write
}
