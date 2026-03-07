package com.example.bitcoinwallet.feature.wallet.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.core.api.WalletApi
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.feature.wallet.model.Transaction
import com.example.bitcoinwallet.feature.wallet.model.WalletBalance
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "WalletDashboardVM"

/**
 * UI State for the Wallet Dashboard.
 */
data class WalletDashboardUiState(
    val balance: WalletBalance = WalletBalance(0L, 0.0, "CZK"),
    val transactions: List<Transaction> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

/**
 * ViewModel for the Wallet Dashboard screen.
 * Fetches wallet-level data via explorer-service through the API Gateway.
 */
class WalletDashboardViewModel : ViewModel() {
    
    private val _uiState = MutableStateFlow(WalletDashboardUiState())
    val uiState: StateFlow<WalletDashboardUiState> = _uiState.asStateFlow()
    
    private val repository = WalletApi.repository
    
    init {
        loadWalletData()
    }
    
    /**
     * Load wallet data – balance and transaction history – in parallel.
     * Uses explorer-service wallet-level endpoints (no address lookup needed).
     */
    fun loadWalletData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            val accessToken = SessionStore.session?.accessToken
            if (accessToken == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Není přihlášen uživatel"
                )
                return@launch
            }
            
            val walletId = SessionStore.activeWalletId
            if (walletId == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Není vybrán wallet"
                )
                return@launch
            }
            
            try {
                Log.d(TAG, "Loading wallet data for wallet: $walletId")
                
                // Fetch balance and transactions in parallel
                val balanceDeferred = async {
                    repository.getWalletBalance(walletId, accessToken)
                }
                val txDeferred = async {
                    repository.getTransactionHistory(walletId, accessToken)
                }
                
                val balance = balanceDeferred.await()
                val transactions = txDeferred.await()
                
                Log.d(TAG, "Balance: ${balance.balanceSats} sats (${balance.formatBtc()}), Transactions: ${transactions.size}")
                
                _uiState.value = WalletDashboardUiState(
                    balance = balance,
                    transactions = transactions,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading wallet data", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Neznámá chyba"
                )
            }
        }
    }
    
    /**
     * Refresh wallet data (pull-to-refresh).
     */
    fun refresh() {
        loadWalletData()
    }
}
