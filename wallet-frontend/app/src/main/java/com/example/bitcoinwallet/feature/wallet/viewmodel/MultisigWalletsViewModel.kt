package com.example.bitcoinwallet.feature.wallet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.core.api.MultisigWalletSummaryDto
import com.example.bitcoinwallet.core.api.WalletApi
import com.example.bitcoinwallet.core.session.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MultisigWalletItem(
    val walletId: String,
    val label: String,
    val m: Int,
    val n: Int,
    val balanceSats: Long,
    val accountIndex: Int? = null,
    val cosignerAccountIndex: Int? = null
) {
    val mOfN: String get() = "$m of $n"
    val balanceBtc: String get() {
        val btc = balanceSats / 100_000_000.0
        return "%.8f BTC".format(btc)
    }
}

data class MultisigWalletsUiState(
    val wallets: List<MultisigWalletItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class MultisigWalletsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MultisigWalletsUiState())
    val uiState: StateFlow<MultisigWalletsUiState> = _uiState

    init {
        loadWallets()
    }

    fun loadWallets() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val token = SessionStore.session?.accessToken
                    ?: throw IllegalStateException("Not authenticated")

                val dtos = WalletApi.client.listWallets(token)

                // Find the active singlesig wallet's account index
                val activeId = SessionStore.activeWalletId
                val activeAccountIndex = dtos
                    .firstOrNull { (it.walletId.ifBlank { it.id }) == activeId }
                    ?.accountIndex

                // Filter multisig wallets that belong to the active account
                val multisigItems = dtos
                    .filter { it.type.equals("MULTI_SIG", ignoreCase = true) }
                    .filter { dto ->
                        // Show only multisig wallets where the cosigner account index
                        // matches the active singlesig wallet's account index.
                        // null accountIndex = unknown cosigner, show as fallback.
                        activeAccountIndex == null || dto.accountIndex == null || dto.accountIndex == activeAccountIndex
                    }
                    .distinctBy { it.walletId.ifBlank { it.id } }
                    .map { dto ->
                        val balance = try {
                            val walletId = dto.walletId.ifBlank { dto.id }
                            WalletApi.client.getWalletBalance(walletId, token).totalSats
                        } catch (_: Exception) {
                            dto.balanceSats
                        }
                        MultisigWalletItem(
                            walletId = dto.walletId.ifBlank { dto.id },
                            label = dto.label.ifBlank { dto.walletId.ifBlank { dto.id } },
                            m = dto.m ?: 0,
                            n = dto.n ?: 0,
                            balanceSats = balance,
                            accountIndex = dto.accountIndex,
                            cosignerAccountIndex = dto.cosignerAccountIndex
                        )
                    }

                _uiState.value = MultisigWalletsUiState(
                    wallets = multisigItems,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load wallets"
                )
            }
        }
    }
}
