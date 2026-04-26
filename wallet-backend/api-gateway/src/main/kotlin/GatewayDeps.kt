package cz.majny.wallet.gateway

import cz.majny.wallet.gateway.clients.*
import cz.majny.wallet.gateway.config.AppConfig
import io.ktor.server.application.*
import io.ktor.util.*

/* Bundle of upstream clients injected into Application attributes so route
 * handlers can pull them via [Application.deps] without constructor wiring. */
data class GatewayDeps(
    val config: AppConfig,
    val auth: AuthClient,
    val registry: RegistryClient,
    val explorer: ExplorerClient,
    val signer: SignerClient,
    val psbt: PsbtClient,
    val blockchain: BlockchainClient,
    val price: PriceClient,
)

private val GatewayDepsKey = AttributeKey<GatewayDeps>("GatewayDeps")

/* Stores the deps bundle on the Application so [deps] can retrieve it later. */
fun Application.installGatewayDeps(deps: GatewayDeps) {
    attributes.put(GatewayDepsKey, deps)
}

val Application.deps: GatewayDeps
    get() = attributes[GatewayDepsKey]
