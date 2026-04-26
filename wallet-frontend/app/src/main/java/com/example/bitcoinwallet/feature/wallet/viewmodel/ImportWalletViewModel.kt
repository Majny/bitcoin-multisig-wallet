package com.example.bitcoinwallet.feature.wallet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.core.api.ImportWalletRequestDto
import com.example.bitcoinwallet.core.api.WalletApi
import com.example.bitcoinwallet.core.session.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ImportWalletUiState(
    val descriptor: String = "",
    val walletName: String = "",
    val isImporting: Boolean = false,
    val success: Boolean = false,
    val error: String? = null
) {
    val canImport: Boolean get() = descriptor.isNotBlank() && !isImporting
}

class ImportWalletViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ImportWalletUiState())
    val uiState: StateFlow<ImportWalletUiState> = _uiState

    fun onDescriptorChanged(value: String) {
        _uiState.value = _uiState.value.copy(descriptor = value, error = null)
    }

    fun onWalletNameChanged(value: String) {
        _uiState.value = _uiState.value.copy(walletName = value)
    }

    /*
     * Set the descriptor from a file or QR scan result.
     */
    fun onDescriptorScanned(content: String) {
        _uiState.value = _uiState.value.copy(descriptor = content.trim(), error = null)
    }

    fun importWallet(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (!state.canImport) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, error = null)
            try {
                val token = SessionStore.session?.accessToken
                    ?: throw IllegalStateException("Not authenticated")

                // Detect network from active wallet ID (e.g. "wallet-400209115-testnet-WPKH-0")
                // Fallback to derivation path if available
                val activeId = SessionStore.activeWalletId ?: ""
                val network = when {
                    activeId.contains("-testnet-") -> "testnet"
                    activeId.contains("-mainnet-") -> "mainnet"
                    else -> {
                        val derivationPath = SessionStore.pendingIdentity?.derivationPath ?: ""
                        if (derivationPath.contains("'/1'/")) "testnet" else "mainnet"
                    }
                }

                val accountIndex = SessionStore.activeAccountIndex

                val request = ImportWalletRequestDto(
                    descriptor = state.descriptor.trim(),
                    network = network,
                    label = state.walletName.ifBlank { null },
                    accountIndex = accountIndex
                )

                val response = WalletApi.client.importWallet(token, request)

                if (response.success) {
                    _uiState.value = _uiState.value.copy(isImporting = false, success = true)
                    onSuccess()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        error = response.error ?: "Import failed"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isImporting = false,
                    error = e.message ?: "Import failed"
                )
            }
        }
    }
}
