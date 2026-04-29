package cz.majny.wallet.blockchain.config

/*
 * Runtime config. Defaults point at Blockstream for mainnet and
 * Mempool.space/testnet4 for testnet - both expose the same Esplora API,
 * so only the base URL differs. Overridable via env for local mocking.
 */
data class AppConfig(
    val port: Int,
    val mainnetMempoolUrl: String,
    val testnetMempoolUrl: String,
    // Fee oracle URLs are intentionally separate from the general blockchain
    // URLs: Blockstream's /fee-estimates endpoint collapses every confirmation
    // target to ~1 sat/vB whenever the mempool is empty, which makes the
    // Low/Medium/High priority selector pointless. Mempool.space exposes a
    // smarter /v1/fees/recommended endpoint that produces distinct values
    // even under low congestion. Default both networks to mempool.space for
    // fees while leaving the rest of the data on Blockstream (mainnet) /
    // mempool.space (testnet4) per the architecture in the thesis.
    val mainnetFeesUrl: String,
    val testnetFeesUrl: String,
) {
    companion object {
        fun fromEnv(): AppConfig {
            fun env(name: String, default: String) = System.getenv(name) ?: default

            return AppConfig(
                port = env("PORT", "8086").toInt(),
                mainnetMempoolUrl = env("MAINNET_MEMPOOL_URL", "https://blockstream.info/api"),
                testnetMempoolUrl = env("TESTNET_MEMPOOL_URL", "https://mempool.space/testnet4/api"),
                mainnetFeesUrl = env("MAINNET_FEES_URL", "https://mempool.space/api"),
                testnetFeesUrl = env("TESTNET_FEES_URL", "https://mempool.space/testnet4/api"),
            )
        }
    }
}
