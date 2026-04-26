package com.example.bitcoinwallet.core.api

/*
 * API configuration. Every network call from the app goes through the
 * gateway which fans out to the individual microservices.
 *
 * The base URL points at the author's Tailscale IP — the backend runs on
 * a development laptop and is only reachable from devices on the same
 * tailnet. Swap this for a real hostname when deploying.
 */
object ApiConfig {
    const val API_GATEWAY_BASE_URL = "http://100.91.223.40:8080/api/v1"
}

/*
 * App-wide singleton for the API client + repository pair. Lazy-init so
 * construction doesn't happen until the first screen actually calls out.
 */
object WalletApi {

    private val apiClient by lazy {
        WalletApiClient(baseUrl = ApiConfig.API_GATEWAY_BASE_URL)
    }

    val repository by lazy {
        WalletRepository(apiClient = apiClient)
    }

    val client: WalletApiClient
        get() = apiClient
}
