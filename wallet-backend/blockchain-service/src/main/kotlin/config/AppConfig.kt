package cz.majny.wallet.blockchain.config

/*
 * Runtime config. Defaults point at Blockstream for mainnet and
 * Mempool.space/testnet4 for testnet — both expose the same Esplora API,
 * so only the base URL differs. Overridable via env for local mocking.
 */
data class AppConfig(
    val port: Int,
    val mainnetMempoolUrl: String,
    val testnetMempoolUrl: String,
) {
    companion object {
        fun fromEnv(): AppConfig {
            fun env(name: String, default: String) = System.getenv(name) ?: default

            return AppConfig(
                port = env("PORT", "8086").toInt(),
                mainnetMempoolUrl = env("MAINNET_MEMPOOL_URL", "https://blockstream.info/api"),
                testnetMempoolUrl = env("TESTNET_MEMPOOL_URL", "https://mempool.space/testnet4/api"),
            )
        }
    }
}
