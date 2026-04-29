package com.example.bitcoinwallet.core.api

import com.example.bitcoinwallet.BuildConfig

/*
 * API gateway base URL. Every network call goes through it; the gateway
 * fans out to the individual microservices.
 *
 * Injected at build time from `local.properties` (key `api.gateway.base.url`).
 * If missing, defaults to 10.0.2.2:8080, which is the emulator's alias for
 * the host's localhost and reaches the docker compose stack.
 *
 * For a real device, set the line in local.properties to a reachable host
 * (LAN IP, Tailscale, ngrok). See app/build.gradle.kts for the wiring.
 */
object ApiConfig {
    const val API_GATEWAY_BASE_URL: String = BuildConfig.API_GATEWAY_BASE_URL
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
