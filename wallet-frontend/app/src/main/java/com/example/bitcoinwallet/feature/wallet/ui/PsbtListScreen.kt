package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitcoinwallet.feature.wallet.viewmodel.PsbtListItem
import com.example.bitcoinwallet.feature.wallet.viewmodel.PsbtListUiState
import com.example.bitcoinwallet.ui.components.PrimaryButton
import com.example.bitcoinwallet.ui.theme.*

/**
 * PSBT list screen for a multisig wallet.
 * Shows "Create New PSBT" button, list of pending/signed PSBTs, and an "Import" button at the bottom.
 */
@Composable
fun PsbtListScreen(
    state: PsbtListUiState,
    onClose: () -> Unit,
    onCreatePsbt: () -> Unit,
    onPsbtClick: (PsbtListItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Top bar: title + X close
        PsbtListTopBar(onClose = onClose)

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
            // Create New PSBT button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                PrimaryButton(
                    text = "Create New PSBT",
                    onClick = onCreatePsbt,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // PSBT list
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(DarkSurface)
            ) {
                if (state.psbts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No PSBTs yet",
                            color = TextMuted,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.psbts) { psbt ->
                            PsbtListItemRow(
                                item = psbt,
                                onClick = { onPsbtClick(psbt) }
                            )
                            HorizontalDivider(
                                color = DarkCard,
                                thickness = 1.dp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }

        }
    }
}

// ─── Top bar ───────────────────────────────────────────────────────

@Composable
private fun PsbtListTopBar(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
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
            text = "PSBTs",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─── PSBT list item ────────────────────────────────────────────────

@Composable
private fun PsbtListItemRow(
    item: PsbtListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: status label + date
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = item.statusLabel,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.dateFormatted,
                color = TextMuted,
                fontSize = 12.sp
            )
        }

        // Right side: amount
        Text(
            text = item.amountBtcFormatted,
            color = TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ============ Previews ============

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun PsbtListPreview() {
    val sampleState = PsbtListUiState(
        walletId = "wlt_123",
        psbts = listOf(
            PsbtListItem(
                id = "1",
                status = "pending",
                requiredSigs = 2,
                currentSigs = 0,
                totalOutputSats = 25_000_000,
                createdAt = "2025-05-27T10:30:00Z",
                label = null
            ),
            PsbtListItem(
                id = "2",
                status = "pending",
                requiredSigs = 2,
                currentSigs = 0,
                totalOutputSats = 46_000_000,
                createdAt = "2025-05-25T14:00:00Z",
                label = null
            ),
            PsbtListItem(
                id = "3",
                status = "finalized",
                requiredSigs = 2,
                currentSigs = 2,
                totalOutputSats = 10_000_000,
                createdAt = "2025-05-20T08:00:00Z",
                label = null
            )
        ),
        isLoading = false
    )

    BitcoinWalletTheme {
        PsbtListScreen(
            state = sampleState,
            onClose = {},
            onCreatePsbt = {},
            onPsbtClick = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun PsbtListEmptyPreview() {
    BitcoinWalletTheme {
        PsbtListScreen(
            state = PsbtListUiState(isLoading = false),
            onClose = {},
            onCreatePsbt = {},
            onPsbtClick = {}
        )
    }
}
