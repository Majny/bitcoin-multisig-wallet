package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.bitcoinwallet.feature.wallet.viewmodel.CosignerUiInfo
import com.example.bitcoinwallet.feature.wallet.viewmodel.PsbtDetailUiState
import com.example.bitcoinwallet.feature.wallet.viewmodel.SignatureUiInfo
import com.example.bitcoinwallet.feature.wallet.viewmodel.SignerStatus
import com.example.bitcoinwallet.ui.components.PrimaryButton
import com.example.bitcoinwallet.ui.components.SecondaryButton
import com.example.bitcoinwallet.ui.theme.*

/**
 * PSBT Detail screen showing transaction summary, signatures status,
 * and context-dependent action buttons (Sign or Broadcast).
 */
@Composable
fun PsbtDetailScreen(
    state: PsbtDetailUiState,
    onClose: () -> Unit,
    onSignPsbt: () -> Unit,
    onExportPsbt: () -> Unit,
    onBroadcast: () -> Unit,
    onShowRecipients: () -> Unit,
    onDismissRecipients: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Signers dialog
    if (state.showSignersDialog) {
        SignersDialog(
            cosigners = state.cosigners,
            isLoading = state.signersLoading,
            onDismiss = onDismissRecipients
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Top bar
        PsbtDetailTopBar(onClose = onClose)

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentTeal)
            }
        } else if (state.error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.error,
                    color = ErrorRed,
                    fontSize = 14.sp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Title
                Text(
                    text = "PSBT Detail",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Signature status: "2 of 3 required"
                Text(
                    text = state.signaturesLabel,
                    color = AccentTeal,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Summary card
                SummaryCard(state = state)

                Spacer(modifier = Modifier.height(16.dp))

                // Show Recipients button
                PrimaryButton(
                    text = "Show Recipients",
                    onClick = onShowRecipients
                )

                // Signers section (who has signed)
                if (state.signatures.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    SignersSection(signatures = state.signatures)
                }

                // Broadcast success message
                if (state.broadcastSuccess && state.txid != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Transaction broadcast! TXID:",
                        color = ReceiveGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.txid,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Bottom action buttons
            ActionButtonsSection(
                state = state,
                onSignPsbt = onSignPsbt,
                onExportPsbt = onExportPsbt,
                onBroadcast = onBroadcast
            )
        }
    }
}

// ─── Top bar ───────────────────────────────────────────────────────

@Composable
private fun PsbtDetailTopBar(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Text(
            text = "PSBT detail",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = TextPrimary
            )
        }
    }
}

// ─── Summary card ──────────────────────────────────────────────────

@Composable
private fun SummaryCard(
    state: PsbtDetailUiState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(16.dp)
    ) {
        Text(
            text = "Summary",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(12.dp))

        SummaryRow(label = "Transaction Amount", value = state.transactionAmountBtc)
        SummaryRow(label = "Network Fee", value = state.networkFeeBtc)

        HorizontalDivider(
            color = DarkCard,
            thickness = 1.dp,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        SummaryRow(label = "Total", value = state.totalBtc)
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = TextSecondary,
            fontSize = 13.sp
        )
    }
}

// ─── Signers section ───────────────────────────────────────────────

@Composable
private fun SignersSection(
    signatures: List<SignatureUiInfo>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(16.dp)
    ) {
        Text(
            text = "Signatures",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        signatures.forEach { sig ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = sig.fingerprint,
                    color = AccentTeal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = sig.deviceId,
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// ─── Bottom action buttons ─────────────────────────────────────────

@Composable
private fun ActionButtonsSection(
    state: PsbtDetailUiState,
    onSignPsbt: () -> Unit,
    onExportPsbt: () -> Unit,
    onBroadcast: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Show either "Sign PSBT" or "Broadcast" — not both
        if (state.canSign && !state.isBroadcast) {
            // Still needs signatures → show Sign button
            PrimaryButton(
                text = "Sign PSBT",
                onClick = onSignPsbt
            )
        }

        // Export PSBT — always available (unless already broadcast)
        if (!state.isBroadcast) {
            SecondaryButton(
                text = "Export PSBT",
                onClick = onExportPsbt
            )
        }

        if ((state.isFullySigned || state.canBroadcast) && !state.isBroadcast) {
            // Fully signed → show bright green Broadcast button
            Button(
                onClick = onBroadcast,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ReceiveGreen
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "Broadcast",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

// ─── Signers dialog (Show Recipients) ──────────────────────────────

@Composable
private fun SignersDialog(
    cosigners: List<CosignerUiInfo>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DarkBackground)
                .padding(20.dp)
        ) {
            // Header: title + X button
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Recipients",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterStart)
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else if (cosigners.isEmpty()) {
                Text(
                    text = "No cosigners found",
                    color = TextMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                cosigners.forEach { cosigner ->
                    val statusText = when (cosigner.status) {
                        SignerStatus.SIGNED -> "Signed"
                        SignerStatus.PENDING -> "Pending"
                        SignerStatus.MISSING -> "Missing"
                    }
                    val statusColor = when (cosigner.status) {
                        SignerStatus.SIGNED -> ReceiveGreen
                        SignerStatus.PENDING -> BitcoinOrangeLight
                        SignerStatus.MISSING -> ErrorRed
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = cosigner.fingerprint,
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

// ============ Previews ============

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun PsbtDetailPendingPreview() {
    BitcoinWalletTheme {
        PsbtDetailScreen(
            state = PsbtDetailUiState(
                psbtId = "abc-123",
                status = "pending",
                requiredSigs = 3,
                currentSigs = 1,
                totalOutputSats = 123456789,
                estimatedFeeSats = 12300,
                isLoading = false,
                signatures = listOf(
                    SignatureUiInfo("73c5da0a", "Trezor T", "2025-05-27T10:30:00Z")
                )
            ),
            onClose = {},
            onSignPsbt = {},
            onExportPsbt = {},
            onBroadcast = {},
            onShowRecipients = {},
            onDismissRecipients = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun PsbtDetailReadyPreview() {
    BitcoinWalletTheme {
        PsbtDetailScreen(
            state = PsbtDetailUiState(
                psbtId = "abc-456",
                status = "finalized",
                requiredSigs = 2,
                currentSigs = 2,
                totalOutputSats = 50_000_000,
                estimatedFeeSats = 5600,
                isLoading = false,
                signatures = listOf(
                    SignatureUiInfo("73c5da0a", "Trezor T", "2025-05-27T10:30:00Z"),
                    SignatureUiInfo("a1b2c3d4", "Ledger S", "2025-05-27T14:20:00Z")
                )
            ),
            onClose = {},
            onSignPsbt = {},
            onExportPsbt = {},
            onBroadcast = {},
            onShowRecipients = {},
            onDismissRecipients = {}
        )
    }
}
