package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.example.bitcoinwallet.core.api.TxInputDto
import com.example.bitcoinwallet.core.api.TxOutputDto
import com.example.bitcoinwallet.feature.wallet.viewmodel.TransactionDetailUiState
import com.example.bitcoinwallet.ui.theme.*

/**
 * Transaction Detail screen — dark themed, consistent with other screens.
 * Shows: txid, status, timestamp, fee/feeRate, inputs list, outputs list.
 */
@Composable
fun TransactionDetailScreen(
    state: TransactionDetailUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        /* ── Top bar ── */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Transaction Detail",
                color = TextMuted,
                fontSize = 14.sp
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextPrimary
                )
            }
        }

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            }
            state.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.error,
                        color = ErrorRed,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    /* ── TXID ── */
                    SectionLabel("Transaction ID")
                    Text(
                        text = state.txid,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    /* ── Status card ── */
                    InfoCard {
                        InfoRow("Status", state.confirmations,
                            valueColor = if (state.confirmed) ReceiveGreen else AccentTeal)
                        if (state.blockHeight != null) {
                            InfoRow("Block", "#${state.blockHeight}")
                        }
                        if (state.timestamp.isNotBlank()) {
                            InfoRow("Date", state.timestamp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    /* ── Fee card ── */
                    InfoCard {
                        InfoRow("Fee", state.feeBtc)
                        if (state.feeRate.isNotBlank()) {
                            InfoRow("Fee rate", state.feeRate)
                        }
                        if (state.size > 0) {
                            InfoRow("Size", "${state.size} bytes")
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    /* ── Inputs ── */
                    SectionLabel("Inputs (${state.inputs.size})")
                    state.inputs.forEachIndexed { idx, input ->
                        TxIoRow(
                            address = input.address,
                            valueSats = input.valueSats,
                            isMine = input.isMine
                        )
                        if (idx < state.inputs.lastIndex) {
                            HorizontalDivider(color = DarkCard.copy(alpha = 0.5f), thickness = 0.5.dp,
                                modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    /* ── Outputs ── */
                    SectionLabel("Outputs (${state.outputs.size})")
                    state.outputs.forEachIndexed { idx, output ->
                        TxIoRow(
                            address = output.address,
                            valueSats = output.valueSats,
                            isMine = output.isMine
                        )
                        if (idx < state.outputs.lastIndex) {
                            HorizontalDivider(color = DarkCard.copy(alpha = 0.5f), thickness = 0.5.dp,
                                modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

/* ──────────── Reusable composables ──────────── */

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

/**
 * Dark rounded card used for grouping info rows.
 */
@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextMuted, fontSize = 13.sp)
        Text(text = value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * A single input / output row: address, amount, isMine badge.
 */
@Composable
private fun TxIoRow(
    address: String,
    valueSats: Long,
    isMine: Boolean
) {
    val btcAmount = valueSats / 100_000_000.0
    val shortAddr = if (address.length > 20)
        "${address.take(10)}…${address.takeLast(8)}" else address

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = shortAddr,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isMine) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "mine",
                        color = AccentTeal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentTeal.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "%.8f".format(btcAmount),
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ============ Preview ============

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun TransactionDetailPreview() {
    TransactionDetailScreen(
        state = TransactionDetailUiState(
            txid = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
            confirmed = true,
            confirmations = "Confirmed",
            blockHeight = 840123,
            timestamp = "2025-12-15 14:32",
            feeSats = 3420,
            size = 225,
            weight = 573,
            inputs = listOf(
                TxInputDto(
                    txid = "prev_tx_001",
                    vout = 0,
                    address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                    valueSats = 150000,
                    isMine = true
                ),
                TxInputDto(
                    txid = "prev_tx_002",
                    vout = 1,
                    address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
                    valueSats = 50000,
                    isMine = false
                )
            ),
            outputs = listOf(
                TxOutputDto(
                    index = 0,
                    address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
                    valueSats = 120000,
                    isMine = false
                ),
                TxOutputDto(
                    index = 1,
                    address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                    valueSats = 76580,
                    isMine = true
                )
            ),
            isLoading = false
        ),
        onClose = {}
    )
}
