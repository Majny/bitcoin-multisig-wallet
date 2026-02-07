package cz.majny.wallet.blockchain.config

data class AppConfig(
    val port: Int,
    val mempoolBaseUrl: String,
) {
    companion object {
        fun fromEnv(): AppConfig {
            fun env(name: String, default: String) = System.getenv(name) ?: default

            return AppConfig(
                port = env("PORT", "8086").toInt(),
                mempoolBaseUrl = env("MEMPOOL_BASE_URL", "https://mempool.space/api"),
            )
        }
    }
}
