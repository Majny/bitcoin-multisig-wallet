package com.example.bitcoinwallet.feature.wallet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.feature.wallet.model.Transaction
import com.example.bitcoinwallet.feature.wallet.model.TransactionType
import com.example.bitcoinwallet.feature.wallet.model.WalletBalance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

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
 * Manages wallet balance and transaction history.
 */
class WalletDashboardViewModel : ViewModel() {
    
    private val _uiState = MutableStateFlow(WalletDashboardUiState())
    val uiState: StateFlow<WalletDashboardUiState> = _uiState.asStateFlow()
    
    init {
        loadWalletData()
    }
    
    /**
     * Load wallet data including balance and transaction history.
     */
    fun loadWalletData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                // TODO: Replace with actual API calls to blockchain-service
                // This is mock data for UI development
                val balance = fetchBalance()
                val transactions = fetchTransactions()
                
                _uiState.value = WalletDashboardUiState(
                    balance = balance,
                    transactions = transactions,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error occurred"
                )
            }
        }
    }
    
    /**
     * Refresh wallet data.
     */
    fun refresh() {
        loadWalletData()
    }
    
    /**
     * Clear any error state.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    // ============ Mock Data (to be replaced with API calls) ============
    
    private suspend fun fetchBalance(): WalletBalance {
        // TODO: Call blockchain-service API
        // GET /api/blockchain/address/{address}/balance
        
        // Mock data for now
        return WalletBalance(
            balanceSats = 123450000, // 1.2345 BTC
            balanceFiat = 75000.0,
            fiatCurrency = "CZK"
        )
    }
    
    private suspend fun fetchTransactions(): List<Transaction> {
        // TODO: Call blockchain-service API
        // GET /api/blockchain/address/{address}/txs
        
        // Mock data for now
        return listOf(
            Transaction(
                id = "1",
                txid = "abc123def456789...",
                type = TransactionType.RECEIVED,
                amount = 1500000, // 0.015 BTC
                date = LocalDate.of(2024, 4, 25)
            ),
            Transaction(
                id = "2",
                txid = "def456ghi789012...",
                type = TransactionType.SENT,
                amount = 250000, // 0.0025 BTC
                date = LocalDate.of(2024, 4, 24)
            ),
            Transaction(
                id = "3",
                txid = "ghi789jkl012345...",
                type = TransactionType.RECEIVED,
                amount = 10000000, // 0.1 BTC
                date = LocalDate.of(2024, 4, 23)
            ),
            Transaction(
                id = "4",
                txid = "jkl012mno345678...",
                type = TransactionType.SENT,
                amount = 500000, // 0.005 BTC
                date = LocalDate.of(2024, 4, 22)
            ),
            Transaction(
                id = "5",
                txid = "mno345pqr678901...",
                type = TransactionType.RECEIVED,
                amount = 2000000, // 0.02 BTC
                date = LocalDate.of(2024, 4, 21)
            ),
            Transaction(
                id = "6",
                txid = "pqr678stu901234...",
                type = TransactionType.SENT,
                amount = 120000, // 0.0012 BTC
                date = LocalDate.of(2024, 4, 20)
            )
        )
    }
}
