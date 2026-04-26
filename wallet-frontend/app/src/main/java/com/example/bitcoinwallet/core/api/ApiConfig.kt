package com.example.bitcoinwallet.core.api

import com.example.bitcoinwallet.BuildConfig

/*
 * API gateway configuration.
 *
 * Every network call from the app goes through a single gateway URL,
 * which fans out to the individual backend microservices.
 *
 * The URL is injected at build time from `local.properties` in the
 * project root via the `api.gateway.base.url` key. If the file or the
 * key is missing, the build falls back to `http://10.0.2.2:8080/api/v1`,
 * which is the Android emulator's alias for the host machine's localhost.
 * With the backend running through `docker compose up`, that default
 * makes the app reach the API out-of-the-box without any code edits.
 *
 * To override (real device on the same LAN, Tailscale tailnet, ngrok
 * tunnel, ...), add a single line to `local.properties` in the project
 * root, for example:
 *
 *     api.gateway.base.url=http://192.168.0.100:8080/api/v1
 *
 * `local.properties` is in `.gitignore`, so the override never leaks
 * into the repository. See `wallet-frontend/app/build.gradle.kts` for
 * how the value is wired into `BuildConfig`.
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
