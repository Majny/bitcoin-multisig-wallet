package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitcoinwallet.ui.components.PrimaryButton
import com.example.bitcoinwallet.ui.theme.*

/*
 * Transaction Sent success screen.
 * Shows a green checkmark, amount, fee, and a "Return To Wallet" button.
 */
@Composable
fun TransactionSentScreen(
    amountSats: Long,
    feeSats: Long,
    onReturnToWallet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.3f))

        // Green checkmark circle
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(ReceiveGreen),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Success",
                tint = TextPrimary,
                modifier = Modifier.size(52.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Title
        Text(
            text = "Transaction Sent!",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.weight(0.25f))

        // Summary
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SummaryLine("Amount", formatBtcFull(amountSats))
            SummaryLine("Network Fee", formatBtcFull(feeSats))
        }

        Spacer(modifier = Modifier.weight(0.35f))

        // Return button
        PrimaryButton(
            text = "Return To Wallet",
            onClick = onReturnToWallet,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 14.sp
        )
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 14.sp
        )
    }
}

private fun formatBtcFull(sats: Long): String {
    val btc = sats / 100_000_000.0
    return String.format(java.util.Locale.US, "%.8f BTC", btc)
}

// Preview

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun TransactionSentScreenPreview() {
    BitcoinWalletTheme {
        TransactionSentScreen(
            amountSats = 123_469_089L,
            feeSats = 12_300L,
            onReturnToWallet = {}
        )
    }
}
