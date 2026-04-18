package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 20.dp)
    ) {
        /* ── Top bar ── */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Import Wallet",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextPrimary
                )
            }
        }

        /* ── Descriptor text area ── */
        OutlinedTextField(
            value = state.descriptor,
            onValueChange = onDescriptorChanged,
            placeholder = {
                Text("Descriptor", color = TextMuted, fontSize = 14.sp)
            },
            trailingIcon = {
                IconButton(onClick = onScanQr) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "Scan QR (UR)",
                        tint = AccentTeal
                    )
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

        /* ── Error card ── */
        if (state.error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            ImportErrorCard(message = state.error)
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

/**
 * Inline card shown when the import fails. Maps a few well-known backend
 * error messages to a friendlier title + actionable hint; falls back to the
 * raw message when no pattern matches so we never hide information from the
 * user.
 */
@Composable
private fun ImportErrorCard(message: String) {
    val title = when {
        message.contains("not a cosigner", ignoreCase = true) ->
            "Wallet doesn't include this account"
        message.contains("parse error", ignoreCase = true) ||
            message.contains("descriptor", ignoreCase = true) ->
            "Invalid descriptor"
        else -> "Cannot import wallet"
    }
    val hint = when {
        message.contains("not a cosigner", ignoreCase = true) ->
            "Switch to the account that is part of this multisig, " +
                "or sign in with the Trezor that holds one of the cosigner xpubs."
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ErrorRed.copy(alpha = 0.10f))
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = ErrorRed,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = ErrorRed,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                color = TextSecondary,
                fontSize = 13.sp
            )
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        }
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
