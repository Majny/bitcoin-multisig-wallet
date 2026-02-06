package org.example.nodeproxy.dto

object Allowlist {
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
        "scantxoutset"  // for account discovery
    )

    private val write = setOf(
        "testmempoolaccept",
        "sendrawtransaction"
    )

    fun isAllowed(method: String) = method.lowercase() in read || method.lowercase() in write
}
