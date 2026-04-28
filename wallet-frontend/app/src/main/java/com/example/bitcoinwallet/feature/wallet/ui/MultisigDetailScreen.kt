package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.bitcoinwallet.feature.wallet.viewmodel.MultisigDetailUiState
import com.example.bitcoinwallet.ui.components.PrimaryButton
import com.example.bitcoinwallet.ui.components.SecondaryButton
import com.example.bitcoinwallet.ui.theme.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * Multisig Wallet Detail screen – dashboard for a single multisig wallet.
 * Shows wallet name, M-of-N, balance, PSBTs + Receive buttons, transaction history.
 */
@Composable
fun MultisigDetailScreen(
    state: MultisigDetailUiState,
    onClose: () -> Unit,
    onPsbtsClick: () -> Unit,
    onReceiveClick: () -> Unit,
    onTransactionClick: (Transaction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Top bar with close button + wallet name
        MultisigDetailTopBar(
            walletName = state.walletName,
            mOfN = state.mOfN,
            onClose = onClose
        )

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
            // Balance section
            MultisigBalanceSection(
                balance = state.balance,
                onPsbtsClick = onPsbtsClick,
                onReceiveClick = onReceiveClick
            )

            // Transaction history
            MultisigTransactionHistorySection(
                transactions = state.transactions,
                onTransactionClick = onTransactionClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ─── Top bar ───────────────────────────────────────────────────────

@Composable
private fun MultisigDetailTopBar(
    walletName: String,
    mOfN: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = walletName,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = " · $mOfN",
            color = TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = TextPrimary
            )
        }
    }
}

// ─── Balance section ───────────────────────────────────────────────

@Composable
private fun MultisigBalanceSection(
    balance: WalletBalance,
    onPsbtsClick: () -> Unit,
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

        // Action buttons – PSBTs (primary) + Receive BTC (secondary)
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PrimaryButton(
                text = "PSBTs",
                onClick = onPsbtsClick
            )

            SecondaryButton(
                text = "Receive BTC",
                onClick = onReceiveClick
            )
        }
    }
}

// ─── Transaction history section ───────────────────────────────────

@Composable
private fun MultisigTransactionHistorySection(
    transactions: List<Transaction>,
    onTransactionClick: (Transaction) -> Unit,
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
            text = "Transactions",
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

        if (transactions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No transactions yet",
                    color = TextMuted,
                    fontSize = 14.sp
                )
            }
        } else {
            val sorted = transactions.sortedByDescending { it.dateTime }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(sorted) { transaction ->
                    MultisigTransactionItem(
                        transaction = transaction,
                        onClick = { onTransactionClick(transaction) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MultisigTransactionItem(
    transaction: Transaction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd, HH:mm")
    val typeText = if (transaction.type == TransactionType.RECEIVED) "Received" else "Sent"
    val amountBtc = transaction.amount / 100_000_000.0
    val amountText = String.format(java.util.Locale.US, "%.8f BTC", amountBtc)
    val typeColor = if (transaction.type == TransactionType.RECEIVED) ReceiveGreen else TextSecondary
    // Unconfirmed txs carry a 9999-12-31 sentinel from WalletRepository so
    // they sort to the top of history; formatting that sentinel as
    // "MMM dd, HH:mm" reads as "Dec 31, 23:59" which is nonsense to the
    // user. Mirror the dashboard's "Pending" label instead.
    val dateText = if (transaction.confirmed) {
        transaction.dateTime.format(dateTimeFormatter)
    } else {
        "Pending"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: type + date on one line
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = typeText,
                color = typeColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = dateText,
                color = if (transaction.confirmed) TextMuted else AccentTeal,
                fontSize = 12.sp
            )
        }

        // Right: amount
        Text(
            text = amountText,
            color = typeColor,
            fontSize = 14.sp
        )
    }
}

// ============ Previews ============

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun MultisigDetailPreview() {
    val sampleState = MultisigDetailUiState(
        walletId = "wlt_123",
        walletName = "Family Vault",
        m = 2,
        n = 3,
        balance = WalletBalance(
            balanceSats = 123450000, // 1.2345 BTC
            balanceFiat = 75000.0,
            fiatCurrency = "CZK"
        ),
        transactions = listOf(
            Transaction("1", "tx1", TransactionType.RECEIVED, 1500000, LocalDateTime.of(2024, 4, 25, 14, 32)),
            Transaction("2", "tx2", TransactionType.SENT, 250000, LocalDateTime.of(2024, 4, 24, 9, 15)),
            Transaction("3", "tx3", TransactionType.RECEIVED, 10000000, LocalDateTime.of(2024, 4, 23, 18, 45)),
            Transaction("4", "tx4", TransactionType.SENT, 500000, LocalDateTime.of(2024, 4, 22, 11, 0)),
            Transaction("5", "tx5", TransactionType.RECEIVED, 2000000, LocalDateTime.of(2024, 4, 21, 20, 10)),
        ),
        isLoading = false
    )

    BitcoinWalletTheme {
        MultisigDetailScreen(
            state = sampleState,
            onClose = {},
            onPsbtsClick = {},
            onReceiveClick = {},
            onTransactionClick = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun MultisigDetailLoadingPreview() {
    BitcoinWalletTheme {
        MultisigDetailScreen(
            state = MultisigDetailUiState(
                walletName = "Family Vault",
                m = 2,
                n = 3,
                isLoading = true
            ),
            onClose = {},
            onPsbtsClick = {},
            onReceiveClick = {},
            onTransactionClick = {}
        )
    }
}
