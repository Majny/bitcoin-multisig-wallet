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

/**
 * Send BTC screen — matches the dark UI mockup:
 *   - Recipient address input
 *   - Amount input with BTC label
 *   - Auto Select checkbox
 *   - Fee priority selector (Low / Medium / High)
 *   - Transaction summary
 *   - Create Transaction button
 */
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // ===== Header with close button =====
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
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

        Spacer(modifier = Modifier.height(24.dp))

        // ===== Recipient =====
        SectionLabel("Recipient")
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.recipientAddress,
            onValueChange = onRecipientChanged,
            placeholder = {
                Text("Bitcoin address", color = TextMuted)
            },
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
                        tint = TextSecondary
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = outlinedFieldColors()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ===== Amount =====
        SectionLabel("Amount")
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.amountBtc,
            onValueChange = onAmountChanged,
            placeholder = {
                Text("0.00000000", color = TextMuted)
            },
            isError = state.amountError != null,
            supportingText = state.amountError?.let { err ->
                { Text(err, color = ErrorRed, fontSize = 12.sp) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            trailingIcon = {
                Text(
                    text = "BTC",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = 12.dp)
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = outlinedFieldColors()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ===== Auto Select + Edit Selection =====
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onAutoSelectChanged(!state.autoSelect) }
            ) {
                Checkbox(
                    checked = state.autoSelect,
                    onCheckedChange = onAutoSelectChanged,
                    colors = CheckboxDefaults.colors(
                        checkedColor = AccentTeal,
                        uncheckedColor = TextSecondary,
                        checkmarkColor = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Auto Select",
                    color = TextPrimary,
                    fontSize = 14.sp
                )
            }

            // Show "Edit Selection" button when auto is OFF
            if (!state.autoSelect) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Edit Selection",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, DarkCard, RoundedCornerShape(8.dp))
                        .clickable { onEditSelection() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ===== Fee section — presets (auto) or custom input (manual) =====
        if (state.autoSelect) {
            FeePrioritySelector(
                selected = state.feePriority,
                onSelect = onFeePriorityChanged,
                feeEstimates = state.feeEstimates
            )
        } else {
            CustomFeeInput(
                value = state.customFeeRate,
                onValueChange = onCustomFeeRateChanged
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // ===== Summary =====
        SectionLabel("Summary")
        Spacer(modifier = Modifier.height(12.dp))
        SummarySection(state)

        Spacer(modifier = Modifier.height(28.dp))

        // ===== Error message =====
        if (state.error != null) {
            Text(
                text = state.error,
                color = ErrorRed,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // ===== Create Transaction button =====
        PrimaryButton(
            text = if (state.isSending) "Creating..." else buttonText,
            onClick = onCreateTransaction,
            enabled = !state.isSending && !state.isLoading,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ===== Sub-components =====

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun FeePrioritySelector(
    selected: FeePriority,
    onSelect: (FeePriority) -> Unit,
    feeEstimates: FeeEstimatesDto? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = "Fee",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 16.dp)
        )

        val options = listOf(
            FeePriority.LOW    to Pair("Low",    feeEstimates?.hourFee),
            FeePriority.MEDIUM to Pair("Medium", feeEstimates?.halfHourFee),
            FeePriority.HIGH   to Pair("High",   feeEstimates?.fastestFee)
        )

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, DarkCard, RoundedCornerShape(8.dp))
        ) {
            options.forEach { (priority, labelRate) ->
                val (label, rate) = labelRate
                val isSelected = selected == priority
                Box(
                    modifier = Modifier
                        .background(if (isSelected) AccentTeal else Color.Transparent)
                        .clickable { onSelect(priority) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = label,
                            color = if (isSelected) TextPrimary else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (rate != null) {
                            Text(
                                text = "$rate sat/vB",
                                color = if (isSelected) TextPrimary else TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomFeeInput(
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Fee",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 16.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text("Enter Custom Fee", color = TextMuted, fontSize = 13.sp)
            },
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
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = outlinedFieldColors()
        )
    }
}

@Composable
private fun SummarySection(state: SendTransactionUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SummaryRow("Transaction Amount", formatBtc(state.amountSats))
        SummaryRow("Network Fee", formatSats(state.feeSats))
        HorizontalDivider(color = DarkCard, thickness = 1.dp)
        SummaryRow("Total", formatBtc(state.totalSats), bold = true)
        SummaryRow("Remaining Balance", formatBtc(state.remainingSats))
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

private fun formatBtc(sats: Long): String {
    val btc = sats / 100_000_000.0
    return String.format("%.8f BTC", btc)
}

private fun formatSats(sats: Long): String {
    return "$sats sats"
}

@Composable
private fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = AccentTeal,
    focusedBorderColor = AccentTeal,
    unfocusedBorderColor = DarkCard,
    errorBorderColor = ErrorRed,
    focusedContainerColor = DarkSurface,
    unfocusedContainerColor = DarkSurface
)

// ===== Preview =====

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E, name = "Auto Mode")
@Composable
private fun SendTransactionScreenPreview() {
    val state = SendTransactionUiState(
        recipientAddress = "",
        amountBtc = "1.23456789",
        feePriority = FeePriority.MEDIUM,
        autoSelect = true,
        balanceSats = 198_901_089L,
        amountSats = 123_456_789L,
        feeSats = 12_300L,
        totalSats = 123_469_089L,
        remainingSats = 75_432_100L,
        isLoading = false
    )

    BitcoinWalletTheme {
        SendTransactionScreen(
            state = state,
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
    val state = SendTransactionUiState(
        recipientAddress = "",
        amountBtc = "1.23456789",
        autoSelect = false,
        customFeeRate = "",
        balanceSats = 198_901_089L,
        amountSats = 123_456_789L,
        feeSats = 12_300L,
        totalSats = 123_469_089L,
        remainingSats = 75_432_100L,
        isLoading = false
    )

    BitcoinWalletTheme {
        SendTransactionScreen(
            state = state,
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
