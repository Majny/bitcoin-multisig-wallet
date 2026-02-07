package cz.majny.wallet.gateway

import cz.majny.wallet.gateway.clients.*
import cz.majny.wallet.gateway.config.AppConfig
import io.ktor.server.application.*
import io.ktor.util.*

data class GatewayDeps(
    val config: AppConfig,
    val auth: AuthClient,
    val registry: RegistryClient,
    val explorer: ExplorerClient,
    val signer: SignerClient,
    val psbt: PsbtClient,
    val nodeProxy: NodeProxyClient,  // DEPRECATED - kept for compatibility
    val mempool: MempoolClient,      // NEW - primary blockchain data source
)

private val GatewayDepsKey = AttributeKey<GatewayDeps>("GatewayDeps")

fun Application.installGatewayDeps(deps: GatewayDeps) {
    attributes.put(GatewayDepsKey, deps)
}

val Application.deps: GatewayDeps
    get() = attributes[GatewayDepsKey]
