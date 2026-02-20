package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitcoinwallet.feature.wallet.viewmodel.CoinControlUiState
import com.example.bitcoinwallet.feature.wallet.viewmodel.SelectableUtxo
import com.example.bitcoinwallet.feature.wallet.viewmodel.UtxoSortOrder
import com.example.bitcoinwallet.ui.components.PrimaryButton
import com.example.bitcoinwallet.ui.components.SecondaryButton
import com.example.bitcoinwallet.ui.theme.*

/**
 * Coin Control screen — UTXO selection for advanced send.
 *
 * Layout based on mockup:
 *   - "UTXOs" title with close button
 *   - "N selected  X BTC" subtitle
 *   - Confirmation button (teal)
 *   - Order By button (dark) with dropdown
 *   - Scrollable UTXO list with checkboxes
 */
@Composable
fun CoinControlScreen(
    state: CoinControlUiState,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
    onToggleUtxo: (String) -> Unit,
    onToggleSortMenu: () -> Unit,
    onSortOrderChanged: (UtxoSortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // ===== Header =====
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "UTXOs",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextSecondary
                )
            }
        }

        // ===== Selection summary =====
        Text(
            text = "${state.selectedCount} selected  ${state.formatSelectedBtc()}",
            color = TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp, bottom = 16.dp)
        )

        // ===== Action buttons =====
        PrimaryButton(
            text = "Confirmation",
            onClick = onConfirm,
            enabled = state.selectedCount > 0,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Order By with dropdown
        Box {
            SecondaryButton(
                text = "Order By",
                onClick = onToggleSortMenu,
                modifier = Modifier.fillMaxWidth()
            )

            DropdownMenu(
                expanded = state.showSortMenu,
                onDismissRequest = onToggleSortMenu,
                modifier = Modifier.background(DarkSurface)
            ) {
                UtxoSortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = order.label,
                                color = if (order == state.sortOrder) AccentTeal else TextPrimary,
                                fontWeight = if (order == state.sortOrder) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = { onSortOrderChanged(order) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== Loading / Error =====
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentTeal)
            }
        } else if (state.error != null) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(text = state.error, color = ErrorRed, fontSize = 14.sp)
            }
        } else {
            // ===== UTXO list =====
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(state.utxos, key = { it.key }) { utxo ->
                    UtxoRow(
                        utxo = utxo,
                        onToggle = { onToggleUtxo(utxo.key) }
                    )
                    HorizontalDivider(color = DarkCard.copy(alpha = 0.5f), thickness = 1.dp)
                }
            }
        }
    }
}

// ===== UTXO Row =====

@Composable
private fun UtxoRow(
    utxo: SelectableUtxo,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: amount + address
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = utxo.formatBtc(),
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = utxo.shortAddress(),
                color = TextMuted,
                fontSize = 12.sp
            )
        }

        // Status label
        val statusText = if (utxo.confirmed) "Confirmed" else "Unconfirmed"
        val statusColor = if (utxo.confirmed) ReceiveGreen else BitcoinOrangeLight

        Text(
            text = statusText,
            color = statusColor,
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 12.dp)
        )

        // Checkbox
        Checkbox(
            checked = utxo.selected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = AccentTeal,
                uncheckedColor = TextSecondary,
                checkmarkColor = TextPrimary
            )
        )
    }
}

// ===== Preview =====

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun CoinControlScreenPreview() {
    val sampleUtxos = listOf(
        SelectableUtxo("tx1", 0, 350_000, "bc1qAlice", "receive", true, false),
        SelectableUtxo("tx2", 1, 420_000, "bc1q8r9f3ziwk5p7u", "receive", true, false),
        SelectableUtxo("tx3", 0, 1_800_000, "bc1qRachel", "receive", false, false),
        SelectableUtxo("tx4", 0, 5_000_000, "bc1q7l9a4", "receive", true, false),
        SelectableUtxo("tx5", 2, 700_000, "bc1p3nyuzc", "receive", true, false),
        SelectableUtxo("tx6", 0, 2_500_000, "bc1qBob", "change", false, false),
        SelectableUtxo("tx7", 1, 1_230_000, "bc1qCharlie", "receive", true, false),
    )

    val state = CoinControlUiState(
        utxos = sampleUtxos,
        isLoading = false
    )

    BitcoinWalletTheme {
        CoinControlScreen(
            state = state,
            onClose = {},
            onConfirm = {},
            onToggleUtxo = {},
            onToggleSortMenu = {},
            onSortOrderChanged = {}
        )
    }
}
