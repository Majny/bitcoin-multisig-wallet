package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.bitcoinwallet.feature.wallet.viewmodel.CosignerUiInfo
import com.example.bitcoinwallet.feature.wallet.viewmodel.PsbtDetailUiState
import com.example.bitcoinwallet.feature.wallet.viewmodel.SignatureUiInfo
import com.example.bitcoinwallet.feature.wallet.viewmodel.SignerStatus
import com.example.bitcoinwallet.ui.theme.AccentTeal
import com.example.bitcoinwallet.ui.theme.BitcoinOrangeLight
import com.example.bitcoinwallet.ui.theme.BitcoinWalletTheme
import com.example.bitcoinwallet.ui.theme.DarkBackground
import com.example.bitcoinwallet.ui.theme.DarkCard
import com.example.bitcoinwallet.ui.theme.DarkSurface
import com.example.bitcoinwallet.ui.theme.ErrorRed
import com.example.bitcoinwallet.ui.theme.ReceiveGreen
import com.example.bitcoinwallet.ui.theme.TextMuted
import com.example.bitcoinwallet.ui.theme.TextPrimary
import com.example.bitcoinwallet.ui.theme.TextSecondary

/*
 * PSBT Detail screen showing transaction summary, signatures status,
 * and context-dependent action buttons (Sign or Broadcast).
 */
@Composable
fun PsbtDetailScreen(
    state: PsbtDetailUiState,
    onClose: () -> Unit,
    onSignPsbt: () -> Unit,
    onBroadcast: () -> Unit,
    onShowRecipients: () -> Unit,
    onDismissRecipients: () -> Unit,
    onRenameCosigner: (cosignerIdx: Int, newLabel: String) -> Unit = { _, _ -> },
    onCancelPsbt: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showCancelDialog by remember { mutableStateOf(false) }

    if (state.showSignersDialog) {
        SignersDialog(
            cosigners = state.cosigners,
            isLoading = state.signersLoading,
            onDismiss = onDismissRecipients,
            onRename = onRenameCosigner
        )
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            containerColor = DarkCard,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Cancel transaction?") },
            text = {
                Text(
                    "This deletes the draft and frees the reserved UTXOs " +
                        "so they can be spent in a new transaction. " +
                        "Already-collected signatures are discarded. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        onCancelPsbt()
                    }
                ) {
                    Text("Cancel PSBT", color = ErrorRed, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Keep", color = TextSecondary)
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // ── Top bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
            Text(
                text = "PSBT Detail",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = AccentTeal) }
        } else if (state.error != null) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) { Text(text = state.error, color = ErrorRed, fontSize = 14.sp) }
        } else {
            // ── Content ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                // Status badge — shows "Broadcasted" for already-sent PSBTs,
                // otherwise the signature progress (e.g. "2 of 3 required").
                val badgeColor = if (state.isBroadcast || state.isFullySigned || state.canBroadcast)
                    ReceiveGreen else AccentTeal
                Row(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isBroadcast) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (state.isBroadcast) "Broadcasted" else state.signaturesLabel,
                        color = badgeColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // ── Summary card ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .padding(16.dp)
                ) {
                    SummaryRow("Amount", state.transactionAmountBtc)
                    Spacer(modifier = Modifier.height(6.dp))
                    SummaryRow("Fee", state.networkFeeBtc)
                    HorizontalDivider(
                        color = DarkCard, thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    SummaryRow("Total", state.totalBtc, bold = true)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Signatures card ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Signatures",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${state.currentSigs} / ${state.requiredSigs}",
                            color = if (state.isFullySigned) ReceiveGreen else TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (state.signatures.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        state.signatures.forEachIndexed { index, sig ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = DarkCard, thickness = 0.5.dp,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                            val cosigner = state.cosigners.firstOrNull {
                                it.cosignerIndex == sig.cosignerIndex
                            }
                            val signerName = cosigner?.let { displayNameFor(it) }
                                ?: "Signer ...${sig.fingerprint.take(4).uppercase()}"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = signerName,
                                    color = AccentTeal,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Signed",
                                    color = ReceiveGreen,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No signatures yet",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }

                    // "Show Signers" link
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Show all signers",
                        color = AccentTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onShowRecipients)
                            .padding(vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // ── Bottom actions ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBackground)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.isBroadcast) {
                    // Already-broadcast PSBT: no action, just show the txid.
                    val txid = state.txid
                    if (!txid.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkSurface)
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "Transaction ID",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = txid,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                } else if ((state.isFullySigned || state.canBroadcast) && !state.isBroadcast) {
                    Button(
                        onClick = onBroadcast,
                        enabled = !state.isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = ReceiveGreen),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(
                            if (state.isLoading) "Broadcasting…" else "Broadcast Transaction",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                        )
                    }
                } else if (state.currentUserSigned && !state.isBroadcast) {
                    // This device already signed — re-signing crashes Trezor Suite.
                    // Tinted with ReceiveGreen to mirror the "Signed" status colour
                    // used in the Signatures card; the disabled button reads as a
                    // success-in-progress, not an error.
                    Button(
                        onClick = { },
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = ReceiveGreen.copy(alpha = 0.12f),
                            disabledContentColor = ReceiveGreen
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        val remaining = state.remainingSigs
                        Text(
                            text = if (remaining <= 1)
                                "Signed · waiting for 1 more signature"
                            else
                                "Signed · waiting for $remaining more signatures",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                } else if (state.canSign && !state.isBroadcast) {
                    Button(
                        onClick = onSignPsbt,
                        enabled = !state.isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(
                            if (state.isLoading) "Processing…" else "Sign with Trezor",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                        )
                    }
                }

                // Cancel PSBT — destructive secondary action, hidden once the
                // tx is broadcast (audit history is preserved on the backend).
                if (!state.isBroadcast) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showCancelDialog = true },
                        enabled = !state.isLoading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text(
                            "Cancel PSBT",
                            color = ErrorRed,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                }

            }
        }
    }
}

private fun displayNameFor(cosigner: CosignerUiInfo): String {
    if (cosigner.isMe) return "YOU"
    cosigner.label?.takeIf { it.isNotBlank() }?.let { return it }
    val suffix = cosigner.xpub?.takeLast(4)?.uppercase()
        ?: cosigner.fingerprint.take(4).uppercase()
    return "Signer ...$suffix"
}

// ─── Small helpers ────────────────────────────────────────────────

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    bold: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextMuted, fontSize = 13.sp)
        Text(
            text = value, color = if (bold) TextPrimary else TextSecondary,
            fontSize = 13.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ─── Signers dialog ───────────────────────────────────────────────

@Composable
private fun SignersDialog(
    cosigners: List<CosignerUiInfo>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onRename: (cosignerIdx: Int, newLabel: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DarkBackground)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Signers",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = TextPrimary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = AccentTeal) }
            } else if (cosigners.isEmpty()) {
                Text("No cosigners found", color = TextMuted, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
            } else {
                cosigners.forEachIndexed { index, cosigner ->
                    if (index > 0) {
                        HorizontalDivider(color = DarkCard, thickness = 0.5.dp,
                            modifier = Modifier.padding(vertical = 2.dp))
                    }

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
                    val statusIcon = when (cosigner.status) {
                        SignerStatus.SIGNED -> "\u2713"
                        SignerStatus.PENDING -> "\u25CB"
                        SignerStatus.MISSING -> "\u2717"
                    }

                    val displayName = displayNameFor(cosigner)
                    val isEditing = editingIdx == cosigner.cosignerIndex

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Status icon circle
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(statusColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(statusIcon, color = statusColor,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            if (isEditing) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkCard)
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = editText,
                                        onValueChange = { editText = it },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontSize = 14.sp,
                                            color = TextPrimary
                                        ),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentTeal),
                                        decorationBox = { innerTextField ->
                                            if (editText.isEmpty()) {
                                                Text("Enter name", color = TextMuted, fontSize = 14.sp)
                                            }
                                            innerTextField()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Save",
                                        color = AccentTeal,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clickable {
                                                if (editText.isNotBlank()) {
                                                    onRename(cosigner.cosignerIndex, editText.trim())
                                                }
                                                editingIdx = null
                                            }
                                            .padding(4.dp)
                                    )
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = displayName,
                                        color = if (cosigner.isMe) AccentTeal else TextPrimary,
                                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Rename",
                                        tint = TextMuted,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                editText = cosigner.label ?: ""
                                                editingIdx = cosigner.cosignerIndex
                                            }
                                    )
                                }
                                val subtitle = if (cosigner.xpub != null)
                                    "${cosigner.fingerprint} · ...${cosigner.xpub.takeLast(8)}"
                                else cosigner.fingerprint
                                Text(subtitle,
                                    color = TextMuted, fontSize = 11.sp)
                            }
                        }

                        if (!isEditing) {
                            Text(statusText, color = statusColor,
                                fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
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
                psbtId = "abc-123", status = "pending",
                requiredSigs = 3, currentSigs = 1,
                totalOutputSats = 123456789, estimatedFeeSats = 12300,
                isLoading = false,
                signatures = listOf(
                    SignatureUiInfo("73c5da0a", "Trezor T", "2025-05-27T10:30:00Z")
                )
            ),
            onClose = {}, onSignPsbt = {},
            onBroadcast = {}, onShowRecipients = {}, onDismissRecipients = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun PsbtDetailReadyPreview() {
    BitcoinWalletTheme {
        PsbtDetailScreen(
            state = PsbtDetailUiState(
                psbtId = "abc-456", status = "finalized",
                requiredSigs = 2, currentSigs = 2,
                totalOutputSats = 50_000_000, estimatedFeeSats = 5600,
                isLoading = false,
                signatures = listOf(
                    SignatureUiInfo("73c5da0a", "Trezor T", "2025-05-27T10:30:00Z"),
                    SignatureUiInfo("a1b2c3d4", "Ledger S", "2025-05-27T14:20:00Z")
                )
            ),
            onClose = {}, onSignPsbt = {},
            onBroadcast = {}, onShowRecipients = {}, onDismissRecipients = {}
        )
    }
}
