package cz.majny.wallet.blockchain.config

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
                testnetMempoolUrl = env("TESTNET_MEMPOOL_URL", "https://blockstream.info/testnet/api"),
            )
        }
    }
}
