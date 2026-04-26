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

private const val TAG = "MultisigDetailVM"

/*
 * UI State for the Multisig Wallet Detail screen.
 */
data class MultisigDetailUiState(
    val walletId: String = "",
    val walletName: String = "",
    val m: Int = 0,
    val n: Int = 0,
    val balance: WalletBalance = WalletBalance(0L, 0.0, "CZK"),
    val transactions: List<Transaction> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
) {
    val mOfN: String get() = "Multisig: $m of $n"
}

/*
 * ViewModel for the Multisig Wallet Detail screen.
 * Loads balance and transaction history for a specific multisig wallet.
 */
class MultisigDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MultisigDetailUiState())
    val uiState: StateFlow<MultisigDetailUiState> = _uiState.asStateFlow()

    private val repository = WalletApi.repository

    /*
     * Load multisig wallet detail data.
     *
     * @param walletId Wallet identifier
     * @param walletName Display name of the wallet
     * @param m Required signatures
     * @param n Total cosigners
     */
    fun loadWallet(walletId: String, walletName: String, m: Int, n: Int) {
        _uiState.value = _uiState.value.copy(
            walletId = walletId,
            walletName = walletName,
            m = m,
            n = n
        )
        loadWalletData(walletId)
    }

    private fun loadWalletData(walletId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val accessToken = SessionStore.session?.accessToken
            if (accessToken == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Not signed in"
                )
                return@launch
            }

            try {
                Log.d(TAG, "Loading multisig wallet data for: $walletId")

                // Fetch balance and transactions in parallel
                val balanceDeferred = async {
                    repository.getWalletBalance(walletId, accessToken)
                }
                val txDeferred = async {
                    repository.getTransactionHistory(walletId, accessToken)
                }

                val balance = balanceDeferred.await()
                val transactions = txDeferred.await()

                Log.d(TAG, "Balance: ${balance.balanceSats} sats, Transactions: ${transactions.size}")

                _uiState.value = _uiState.value.copy(
                    balance = balance,
                    transactions = transactions,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading multisig wallet data", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    fun refresh() {
        val walletId = _uiState.value.walletId
        if (walletId.isNotBlank()) {
            loadWalletData(walletId)
        }
    }
}
