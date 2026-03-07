package com.example.bitcoinwallet.feature.wallet.viewmodel

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.core.api.WalletApi
import com.example.bitcoinwallet.core.session.SessionStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ReceiveBtcUiState(
    val address: String = "",
    val addressIndex: Int = 0,
    val derivationPath: String = "",
    val qrBitmap: Bitmap? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val copiedToClipboard: Boolean = false
) {
    /** Truncated address for display (first 18 chars + "...") */
    val displayAddress: String
        get() = if (address.length > 20) "${address.take(18)}…" else address
}

class ReceiveBtcViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiveBtcUiState())
    val uiState: StateFlow<ReceiveBtcUiState> = _uiState

    init {
        loadReceiveAddress()
    }

    private fun loadReceiveAddress() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val walletId = SessionStore.activeWalletId
                    ?: throw IllegalStateException("No wallet selected")
                val token = SessionStore.session?.accessToken
                    ?: throw IllegalStateException("Not authenticated")

                val dto = WalletApi.client.getReceiveAddress(walletId, token)

                if (dto.needsDerivation) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Address derivation pending – please try again in a moment"
                    )
                    return@launch
                }

                // Build derivation path for "Show on Trezor" verification
                val wallet = SessionStore.session?.user?.wallets?.find { it.id == walletId }
                val purpose = when (wallet?.scriptType?.uppercase()) {
                    "P2TR", "TAPROOT" -> 86
                    "P2SH-P2WPKH", "NESTED_SEGWIT" -> 49
                    else -> 84  // P2WPKH default
                }
                val coinType = if (wallet?.network == "testnet") 1 else 0
                val derivationPath = "m/$purpose'/$coinType'/0'/0/${dto.index}"

                val qr = generateQrBitmap("bitcoin:${dto.address}", size = 512)

                _uiState.value = _uiState.value.copy(
                    address = dto.address,
                    addressIndex = dto.index,
                    derivationPath = derivationPath,
                    qrBitmap = qr,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load address"
                )
            }
        }
    }

    fun onCopied() {
        _uiState.value = _uiState.value.copy(copiedToClipboard = true)
    }

    /**
     * Generate a QR code Bitmap using ZXing.
     */
    private fun generateQrBitmap(content: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
