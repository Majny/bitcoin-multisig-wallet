package com.example.bitcoinwallet.feature.wallet.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitcoinwallet.feature.wallet.viewmodel.ImportWalletUiState
import com.example.bitcoinwallet.ui.components.PrimaryButton
import com.example.bitcoinwallet.ui.theme.*

/**
 * Import Multisig Wallet screen.
 * Matches mockup: X close, title, descriptor text area,
 * Import from file / Scan QR buttons, wallet name input, Import button.
 */
@Composable
fun ImportWalletScreen(
    state: ImportWalletUiState,
    onClose: () -> Unit,
    onDescriptorChanged: (String) -> Unit,
    onWalletNameChanged: (String) -> Unit,
    onFileContent: (String) -> Unit,
    onScanQr: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Read file content — done in the composable context for simplicity
                // The caller should handle this via onFileContent callback
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 20.dp)
    ) {
        /* ── Top bar: X close ── */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextPrimary
                )
            }
        }

        /* ── Title ── */
        Text(
            text = "Import Wallet",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        )

        /* ── Descriptor text area ── */
        OutlinedTextField(
            value = state.descriptor,
            onValueChange = onDescriptorChanged,
            placeholder = {
                Text("Descriptor", color = TextMuted, fontSize = 14.sp)
            },
            trailingIcon = {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(onClick = onScanQr) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR",
                            tint = TextSecondary
                        )
                    }
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        filePickerLauncher.launch(intent)
                    }) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = "Import from file",
                            tint = TextSecondary
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentTeal,
                focusedBorderColor = AccentTeal,
                unfocusedBorderColor = DarkCard,
                focusedContainerColor = DarkSurface,
                unfocusedContainerColor = DarkSurface
            ),
            shape = RoundedCornerShape(8.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        /* ── Wallet Name input ── */
        OutlinedTextField(
            value = state.walletName,
            onValueChange = onWalletNameChanged,
            placeholder = {
                Text("Wallet Name", color = TextMuted, fontSize = 14.sp)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentTeal,
                focusedBorderColor = AccentTeal,
                unfocusedBorderColor = DarkCard,
                focusedContainerColor = DarkSurface,
                unfocusedContainerColor = DarkSurface
            ),
            shape = RoundedCornerShape(8.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
        )

        /* ── Error message ── */
        if (state.error != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = state.error,
                color = ErrorRed,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        /* ── Import button ── */
        PrimaryButton(
            text = if (state.isImporting) "Importing…" else "Import",
            onClick = onImport,
            enabled = state.canImport,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        )
    }
}

// ============ Preview ============

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun ImportWalletPreview() {
    ImportWalletScreen(
        state = ImportWalletUiState(),
        onClose = {},
        onDescriptorChanged = {},
        onWalletNameChanged = {},
        onFileContent = {},
        onScanQr = {},
        onImport = {}
    )
}
