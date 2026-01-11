package cz.majny.wallet.gateway

import cz.majny.wallet.gateway.clients.AuthClientImpl
import cz.majny.wallet.gateway.clients.ExplorerClientImpl
import cz.majny.wallet.gateway.clients.PsbtClientImpl
import cz.majny.wallet.gateway.clients.RegistryClientImpl
import cz.majny.wallet.gateway.clients.SignerClientImpl
import cz.majny.wallet.gateway.plugins.httpClient
import io.ktor.server.application.Application


fun GatewayDeps.attachHttpClients(application: Application) {
    val http = application.httpClient

    (auth as? AuthClientImpl)?.attach(http)
    (registry as? RegistryClientImpl)?.attach(http)
    (explorer as? ExplorerClientImpl)?.attach(http)
    (signer as? SignerClientImpl)?.attach(http)
    (psbt as? PsbtClientImpl)?.attach(http)
}
