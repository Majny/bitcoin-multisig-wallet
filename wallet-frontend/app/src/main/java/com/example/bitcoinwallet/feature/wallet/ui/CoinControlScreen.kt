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
    onUtxoDetail: ((txid: String) -> Unit)? = null,
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

        // Order By button
        SecondaryButton(
            text = "Order By",
            onClick = onToggleSortMenu,
            modifier = Modifier.fillMaxWidth()
        )

        // Sort order dialog
        if (state.showSortMenu) {
            SortOrderDialog(
                currentOrder = state.sortOrder,
                onSelect = onSortOrderChanged,
                onDismiss = onToggleSortMenu
            )
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
        } else if (state.utxos.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "No UTXOs available", color = TextMuted, fontSize = 14.sp)
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
                        onToggle = { onToggleUtxo(utxo.key) },
                        onShowDetail = onUtxoDetail?.let { { it(utxo.txid) } }
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
    onShowDetail: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: amount + address — tapping navigates to transaction detail
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onShowDetail != null) Modifier.clickable(onClick = onShowDetail)
                    else Modifier
                )
        ) {
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

        // Status + date
        val statusText = when {
            utxo.reserved -> "Reserved"
            utxo.confirmed -> "Confirmed"
            else -> "Unconfirmed"
        }
        val statusColor = when {
            utxo.reserved -> ErrorRed
            utxo.confirmed -> ReceiveGreen
            else -> BitcoinOrangeLight
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(end = 12.dp)
        ) {
            Text(
                text = statusText,
                color = statusColor,
                fontSize = 13.sp
            )
            val dateText = utxo.blockTime?.let { epoch ->
                java.time.Instant.ofEpochSecond(epoch)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm"))
            } ?: if (utxo.confirmed) "" else "Pending"
            if (dateText.isNotBlank()) {
                Text(
                    text = dateText,
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
        }

        // Checkbox — disabled for reserved UTXOs
        Checkbox(
            checked = utxo.selected,
            onCheckedChange = if (utxo.reserved) null else { { onToggle() } },
            enabled = !utxo.reserved,
            colors = CheckboxDefaults.colors(
                checkedColor = AccentTeal,
                uncheckedColor = if (utxo.reserved) TextMuted.copy(alpha = 0.3f) else TextSecondary,
                checkmarkColor = TextPrimary,
                disabledCheckedColor = TextMuted.copy(alpha = 0.3f),
                disabledUncheckedColor = TextMuted.copy(alpha = 0.3f)
            )
        )
    }
}

// ===== Sort Order Dialog =====

@Composable
private fun SortOrderDialog(
    currentOrder: UtxoSortOrder,
    onSelect: (UtxoSortOrder) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = "Order by UTXOs",
                color = TextMuted,
                fontSize = 13.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                UtxoSortOrder.entries.forEachIndexed { index, order ->
                    if (index > 0) {
                        HorizontalDivider(color = DarkCard.copy(alpha = 0.5f), thickness = 1.dp)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(order) }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = order.label,
                            color = if (order == currentOrder) AccentTeal else TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = if (order == currentOrder) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}

// ===== Preview =====

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun CoinControlScreenPreview() {
    val sampleUtxos = listOf(
        SelectableUtxo("tx1", 0, 350_000, "bc1qAlice", "receive", confirmed = true, blockTime = 1712900000),
        SelectableUtxo("tx2", 1, 420_000, "bc1q8r9f3ziwk5p7u", "receive", confirmed = true, blockTime = 1712850000),
        SelectableUtxo("tx3", 0, 1_800_000, "bc1qRachel", "receive", confirmed = false),
        SelectableUtxo("tx4", 0, 5_000_000, "bc1q7l9a4", "receive", confirmed = true, blockTime = 1712700000),
        SelectableUtxo("tx5", 2, 700_000, "bc1p3nyuzc", "receive", confirmed = true, blockTime = 1712600000),
        SelectableUtxo("tx6", 0, 2_500_000, "bc1qBob", "change", confirmed = false),
        SelectableUtxo("tx7", 1, 1_230_000, "bc1qCharlie", "receive", confirmed = true, blockTime = 1712500000),
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
