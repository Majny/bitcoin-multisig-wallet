package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitcoinwallet.ui.components.PrimaryButton
import com.example.bitcoinwallet.ui.theme.*

/*
 * Transaction Error screen.
 * Shows a red X icon, error title, detail message, and a "Return" button.
 */
@Composable
fun TransactionErrorScreen(
    title: String = "Transaction Failed",
    message: String,
    onReturn: () -> Unit,
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

        // ===== Red X circle =====
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(ErrorRed),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Error",
                tint = TextPrimary,
                modifier = Modifier.size(52.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ===== Title =====
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ===== Message =====
        Text(
            text = message,
            color = TextSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.weight(0.5f))

        // ===== Return button =====
        PrimaryButton(
            text = "Return To Wallet",
            onClick = onReturn,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ===== Preview =====

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun TransactionErrorScreenPreview() {
    BitcoinWalletTheme {
        TransactionErrorScreen(
            message = "All funds are reserved in pending transactions. Please wait for them to confirm or cancel them.",
            onReturn = {}
        )
    }
}
