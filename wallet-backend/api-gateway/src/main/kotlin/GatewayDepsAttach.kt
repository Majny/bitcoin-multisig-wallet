package cz.majny.wallet.gateway

import cz.majny.wallet.gateway.clients.AuthClientImpl
import cz.majny.wallet.gateway.clients.BlockchainClientImpl
import cz.majny.wallet.gateway.clients.ExplorerClientImpl
import cz.majny.wallet.gateway.clients.PsbtClientImpl
import cz.majny.wallet.gateway.clients.RegistryClientImpl
import cz.majny.wallet.gateway.plugins.httpClient
import io.ktor.server.application.Application

/* Late-binds the shared Ktor HttpClient into each upstream client. Separate from
 * construction because the HttpClient is configured by a Ktor plugin that only
 * becomes available after the Application is started. */
fun GatewayDeps.attachHttpClients(application: Application) {
    val http = application.httpClient

    (auth as? AuthClientImpl)?.attach(http)
    (registry as? RegistryClientImpl)?.attach(http)
    (explorer as? ExplorerClientImpl)?.attach(http)
    (psbt as? PsbtClientImpl)?.attach(http)
    (blockchain as? BlockchainClientImpl)?.attach(http)
}
