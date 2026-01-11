package cz.majny.wallet.gateway

import cz.majny.wallet.gateway.clients.*
import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.http.configureRouting as configureHttpRouting
import cz.majny.wallet.gateway.plugins.configureAuth
import cz.majny.wallet.gateway.plugins.configureErrorHandling
import cz.majny.wallet.gateway.plugins.configureHttpClient
import cz.majny.wallet.gateway.plugins.configureSerialization
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val cfg = AppConfig.fromEnv()

    embeddedServer(Netty, host = "0.0.0.0", port = cfg.port) {
        configureSerialization()
        configureErrorHandling()
        configureHttpClient()

        val deps = GatewayDeps(
            config = cfg,
            auth = AuthClientImpl(cfg),
            registry = RegistryClientImpl(baseUrl = cfg.registryBaseUrl),
            explorer = ExplorerClientImpl(cfg),
            signer = SignerClientImpl(cfg),
            psbt = PsbtClientImpl(cfg),
        )

        installGatewayDeps(deps)
        deps.attachHttpClients(application = this)

        configureAuth(cfg)
        configureHttpRouting()
    }.start(wait = true)
}
