package com.example.bitcoinwallet.core.api

/**
 * API configuration - base URLs and other settings.
 */
object ApiConfig {
    
    /**
     * Base URL for API Gateway.
     * All API calls go through the gateway which routes to appropriate microservices.
     * 
     * Development: http://100.91.223.40:8080/api/v1
     * Production: https://api.example.com/api/v1
     */
    const val API_GATEWAY_BASE_URL = "http://100.91.223.40:8080/api/v1"
    
}

/**
 * Singleton instance of WalletApiClient.
 * Use this for API calls throughout the app.
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
