package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitcoinwallet.core.api.FeeEstimatesDto
import com.example.bitcoinwallet.feature.wallet.viewmodel.FeePriority
import com.example.bitcoinwallet.feature.wallet.viewmodel.SendTransactionUiState
import com.example.bitcoinwallet.ui.components.PrimaryButton
import com.example.bitcoinwallet.ui.theme.*

@Composable
fun SendTransactionScreen(
    state: SendTransactionUiState,
    onClose: () -> Unit,
    onRecipientChanged: (String) -> Unit,
    onAmountChanged: (String) -> Unit,
    onFeePriorityChanged: (FeePriority) -> Unit,
    onAutoSelectChanged: (Boolean) -> Unit,
    onCustomFeeRateChanged: (String) -> Unit,
    onScanQr: () -> Unit,
    onEditSelection: () -> Unit,
    onCreateTransaction: () -> Unit,
    title: String = "New Transaction",
    buttonText: String = "Create Transaction",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // ── Pinned header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextPrimary
                )
            }
        }

        // ── Scrollable content ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Recipient ──
            SectionLabel("Recipient")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = state.recipientAddress,
                onValueChange = onRecipientChanged,
                placeholder = { Text("Bitcoin address", color = TextMuted) },
                isError = state.addressError != null,
                supportingText = state.addressError?.let { err ->
                    { Text(err, color = ErrorRed, fontSize = 12.sp) }
                },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onScanQr) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR",
                            tint = AccentTeal
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
            )

            Spacer(Modifier.height(16.dp))

            // ── Amount ──
            SectionLabel("Amount")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = state.amountBtc,
                onValueChange = onAmountChanged,
                placeholder = { Text("0.00000000", color = TextMuted) },
                isError = state.amountError != null,
                supportingText = state.amountError?.let { err ->
                    { Text(err, color = ErrorRed, fontSize = 12.sp) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = {
                    Text(
                        text = "BTC",
                        color = TextMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
            )

            Spacer(Modifier.height(16.dp))

            // ── UTXO selection ──
            SectionLabel("UTXO Selection")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.autoSelect) "Automatic" else "Manual",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (state.autoSelect) {
                            "Largest UTXOs first"
                        } else if (state.selectedUtxoCount > 0) {
                            "${state.selectedUtxoCount} UTXO${if (state.selectedUtxoCount > 1) "s" else ""} · ${formatBtc(state.selectedUtxos.sumOf { it.valueSats })}"
                        } else {
                            "No UTXOs selected"
                        },
                        color = if (!state.autoSelect && state.selectedUtxoCount == 0) ErrorRed else TextMuted,
                        fontSize = 12.sp
                    )
                }

                if (!state.autoSelect) {
                    Text(
                        text = "Edit",
                        color = AccentTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onEditSelection() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }

                Switch(
                    checked = state.autoSelect,
                    onCheckedChange = onAutoSelectChanged,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = AccentTeal,
                        checkedThumbColor = TextPrimary,
                        uncheckedTrackColor = DarkCard,
                        uncheckedThumbColor = TextMuted
                    )
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Fee ──
            SectionLabel("Fee")
            Spacer(Modifier.height(8.dp))
            FeePrioritySelector(
                selected = state.feePriority,
                onSelect = onFeePriorityChanged,
                feeEstimates = state.feeEstimates
            )

            if (!state.autoSelect) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.customFeeRate,
                    onValueChange = onCustomFeeRateChanged,
                    placeholder = {
                        Text(
                            text = state.feeEstimates?.halfHourFee?.let { "$it (recommended)" } ?: "Custom",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    },
                    label = { Text("Custom fee rate", color = TextMuted, fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        Text(
                            text = "sat/vB",
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors()
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Summary ──
            SectionLabel("Summary")
            Spacer(Modifier.height(8.dp))
            SummaryCard(state)

            // ── Error ──
            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.error,
                    color = ErrorRed,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ErrorRed.copy(alpha = 0.1f))
                        .padding(12.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Create button ──
            PrimaryButton(
                text = if (state.isSending) "Creating..." else buttonText,
                onClick = onCreateTransaction,
                enabled = !state.isSending && !state.isLoading,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

// ── Sub-components ──────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun FeePrioritySelector(
    selected: FeePriority,
    onSelect: (FeePriority) -> Unit,
    feeEstimates: FeeEstimatesDto? = null
) {
    val options = listOf(
        FeePriority.LOW    to Triple("Low",    feeEstimates?.hourFee,     ReceiveGreen),
        FeePriority.MEDIUM to Triple("Medium", feeEstimates?.halfHourFee, AccentTeal),
        FeePriority.HIGH   to Triple("High",   feeEstimates?.fastestFee,  BitcoinOrange)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        options.forEach { (priority, info) ->
            val (label, rate, accentColor) = info
            val isSelected = selected == priority

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(priority) }
                    .then(
                        if (isSelected) Modifier
                            .background(accentColor.copy(alpha = 0.15f))
                            .border(
                                width = 1.5.dp,
                                color = accentColor.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp)
                            )
                        else Modifier
                    )
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    color = if (isSelected) accentColor else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                if (rate != null) {
                    Text(
                        text = "$rate sat/vB",
                        color = if (isSelected) accentColor.copy(alpha = 0.8f) else TextMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: SendTransactionUiState) {
    val hasReserved = state.reservedSats > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryRow("Amount", formatBtc(state.amountSats), TextPrimary)
        SummaryRow(
            "Fee",
            formatFee(state.feeSats, state.feeRateSatVb),
            if (state.feeSats > 0) BitcoinOrange else TextMuted
        )

        HorizontalDivider(color = DarkCard.copy(alpha = 0.5f), thickness = 0.5.dp)

        SummaryRow("Total", formatBtc(state.totalSats), AccentTeal, bold = true)

        HorizontalDivider(color = DarkCard.copy(alpha = 0.5f), thickness = 0.5.dp)

        if (hasReserved) {
            SummaryRow("Total Balance", formatBtc(state.balanceSats), TextMuted)
            SummaryRow(
                "Reserved (pending PSBTs)",
                "- ${formatBtc(state.reservedSats)}",
                BitcoinOrange
            )
            SummaryRow(
                "Available",
                formatBtc(maxOf(state.balanceSats - state.reservedSats, 0L)),
                TextPrimary
            )
        } else {
            SummaryRow("Balance", formatBtc(state.balanceSats), TextMuted)
        }

        val remaining = state.remainingSats
        SummaryRow(
            "Remaining",
            formatBtc(remaining),
            when {
                remaining < 0 -> ErrorRed
                remaining == 0L -> BitcoinOrange
                else -> ReceiveGreen
            }
        )
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
    }
}

private fun formatBtc(sats: Long): String {
    val btc = sats / 100_000_000.0
    return String.format(java.util.Locale.US, "%.8f BTC", btc)
}

private fun formatFee(sats: Long, rateSatVb: Double): String {
    val rateStr = if (rateSatVb == rateSatVb.toLong().toDouble()) {
        "${rateSatVb.toLong()}"
    } else {
        String.format("%.1f", rateSatVb)
    }
    return "$sats sats ($rateStr sat/vB)"
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = AccentTeal,
    focusedBorderColor = AccentTeal,
    unfocusedBorderColor = DarkCard,
    errorBorderColor = ErrorRed,
    focusedContainerColor = DarkSurface,
    unfocusedContainerColor = DarkSurface
)

// ── Previews ──

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E, name = "Auto Mode")
@Composable
private fun SendTransactionScreenPreview() {
    BitcoinWalletTheme {
        SendTransactionScreen(
            state = SendTransactionUiState(
                recipientAddress = "",
                amountBtc = "0.00100000",
                feePriority = FeePriority.MEDIUM,
                autoSelect = true,
                balanceSats = 198_901_089L,
                amountSats = 100_000L,
                feeSats = 140L,
                totalSats = 100_140L,
                remainingSats = 198_800_949L,
                isLoading = false
            ),
            onClose = {},
            onRecipientChanged = {},
            onAmountChanged = {},
            onFeePriorityChanged = {},
            onAutoSelectChanged = {},
            onCustomFeeRateChanged = {},
            onScanQr = {},
            onEditSelection = {},
            onCreateTransaction = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E, name = "Manual Mode")
@Composable
private fun SendTransactionScreenManualPreview() {
    BitcoinWalletTheme {
        SendTransactionScreen(
            state = SendTransactionUiState(
                recipientAddress = "",
                amountBtc = "0.00100000",
                autoSelect = false,
                customFeeRate = "",
                balanceSats = 198_901_089L,
                amountSats = 100_000L,
                feeSats = 140L,
                totalSats = 100_140L,
                remainingSats = 198_800_949L,
                isLoading = false
            ),
            onClose = {},
            onRecipientChanged = {},
            onAmountChanged = {},
            onFeePriorityChanged = {},
            onAutoSelectChanged = {},
            onCustomFeeRateChanged = {},
            onScanQr = {},
            onEditSelection = {},
            onCreateTransaction = {}
        )
    }
}
