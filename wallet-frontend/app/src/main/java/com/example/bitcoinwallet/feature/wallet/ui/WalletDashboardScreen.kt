package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitcoinwallet.feature.wallet.model.Transaction
import com.example.bitcoinwallet.feature.wallet.model.TransactionType
import com.example.bitcoinwallet.feature.wallet.model.WalletBalance
import com.example.bitcoinwallet.ui.components.PrimaryButton
import com.example.bitcoinwallet.ui.components.SecondaryButton
import com.example.bitcoinwallet.ui.components.WalletTopBar
import com.example.bitcoinwallet.ui.theme.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Main wallet dashboard screen showing balance and transaction history.
 */
@Composable
fun WalletDashboardScreen(
    balance: WalletBalance,
    transactions: List<Transaction>,
    onMenuClick: () -> Unit,
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    onTransactionClick: (Transaction) -> Unit,
    isLoading: Boolean = false,
    error: String? = null,
    network: String = "mainnet",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Top bar
        WalletTopBar(
            title = "Bitcoin Wallet",
            onMenuClick = onMenuClick,
            network = network
        )

        // Error banner
        if (error != null) {
            Text(
                text = error,
                color = androidx.compose.ui.graphics.Color.Red,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0x33FF0000))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Balance section
        BalanceSection(
            balance = balance,
            onSendClick = onSendClick,
            onReceiveClick = onReceiveClick
        )

        // Transaction history
        TransactionHistorySection(
            transactions = transactions,
            isLoading = isLoading,
            onTransactionClick = onTransactionClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BalanceSection(
    balance: WalletBalance,
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // BTC Balance
        Text(
            text = balance.formatBtc(),
            color = TextPrimary,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Fiat equivalent
        Text(
            text = balance.formatFiat(),
            color = TextSecondary,
            fontSize = 16.sp
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PrimaryButton(
                text = "Send BTC",
                onClick = onSendClick
            )
            
            SecondaryButton(
                text = "Receive BTC",
                onClick = onReceiveClick
            )
        }
    }
}

@Composable
private fun TransactionHistorySection(
    transactions: List<Transaction>,
    onTransactionClick: (Transaction) -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(DarkSurface)
            .padding(top = 20.dp)
    ) {
        // Section header
        Text(
            text = "Transaction",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        HorizontalDivider(
            color = DarkCard,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = TextPrimary)
                }
            }
            transactions.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Žádné transakce",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                }
            }
            else -> {
                val sorted = transactions.sortedByDescending { it.dateTime }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(sorted) { transaction ->
                        TransactionItem(
                            transaction = transaction,
                            onClick = { onTransactionClick(transaction) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionItem(
    transaction: Transaction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormatter = DateTimeFormatter.ofPattern("MMM dd")
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val typeText = if (transaction.type == TransactionType.RECEIVED) "Received" else "Sent"
    val amountBtc = transaction.amount / 100_000_000.0
    val amountText = String.format(java.util.Locale.US, "%.8f BTC", amountBtc)
    val typeColor = if (transaction.type == TransactionType.RECEIVED) ReceiveGreen else TextSecondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Date + Time
        Column(modifier = Modifier.width(56.dp)) {
            Text(
                text = transaction.dateTime.format(dateFormatter),
                color = TextMuted,
                fontSize = 14.sp
            )
            Text(
                text = transaction.dateTime.format(timeFormatter),
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        // Type + Amount
        Text(
            text = "$typeText $amountText",
            color = typeColor,
            fontSize = 14.sp
        )
    }
}

// ============ Previews ============

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun WalletDashboardPreview() {
    val sampleBalance = WalletBalance(
        balanceSats = 123450000, // 1.2345 BTC
        balanceFiat = 75000.0,
        fiatCurrency = "CZK"
    )
    
    val sampleTransactions = listOf(
        Transaction("1", "tx1", TransactionType.RECEIVED, 1500000, LocalDateTime.of(2024, 4, 25, 14, 32)),
        Transaction("2", "tx2", TransactionType.SENT, 250000, LocalDateTime.of(2024, 4, 24, 9, 15)),
        Transaction("3", "tx3", TransactionType.RECEIVED, 10000000, LocalDateTime.of(2024, 4, 23, 18, 45)),
        Transaction("4", "tx4", TransactionType.SENT, 500000, LocalDateTime.of(2024, 4, 22, 11, 0)),
        Transaction("5", "tx5", TransactionType.RECEIVED, 2000000, LocalDateTime.of(2024, 4, 21, 20, 10)),
        Transaction("6", "tx6", TransactionType.SENT, 120000, LocalDateTime.of(2024, 4, 20, 7, 55)),
    )
    
    BitcoinWalletTheme {
        WalletDashboardScreen(
            balance = sampleBalance,
            transactions = sampleTransactions,
            onMenuClick = {},
            onSendClick = {},
            onReceiveClick = {},
            onTransactionClick = {}
        )
    }
}
