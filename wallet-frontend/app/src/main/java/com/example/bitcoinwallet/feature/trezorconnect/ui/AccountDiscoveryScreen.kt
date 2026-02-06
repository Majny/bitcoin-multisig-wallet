package com.example.bitcoinwallet.feature.trezorconnect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bitcoinwallet.core.signer.ScannedAccount
import java.text.NumberFormat
import java.util.Locale

/**
 * Screen showing discovered accounts with their balances.
 * User can select which accounts to import.
 */
@Composable
fun AccountDiscoveryScreen(
    isLoading: Boolean,
    accounts: List<ScannedAccount>,
    selectedAccounts: Set<Int>,
    onToggleAccount: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    errorMessage: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        // Header
        Box(Modifier.fillMaxWidth()) {
            Text(
                text = "Discover Accounts",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )

            Text(
                text = "✕",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onCancel() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Select accounts to import. Accounts with activity are pre-selected.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB0B0B0)
        )

        Spacer(Modifier.height(18.dp))

        if (isLoading) {
            // Loading state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF4A90D9))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Scanning blockchain for account activity...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB0B0B0)
                    )
                }
            }
        } else if (errorMessage != null) {
            // Error state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "⚠️",
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE57373)
                    )
                }
            }
        } else {
            // Accounts list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                itemsIndexed(accounts) { index, account ->
                    AccountItem(
                        account = account,
                        isSelected = index in selectedAccounts,
                        onToggle = { onToggleAccount(index) }
                    )

                    if (index != accounts.lastIndex) {
                        HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 1.dp)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(54.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White
                )
            ) {
                Text("Cancel")
            }

            Button(
                onClick = onConfirm,
                enabled = !isLoading && selectedAccounts.isNotEmpty(),
                modifier = Modifier.weight(1f).height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B4450))
            ) {
                Text("Import ${selectedAccounts.size} Account(s)")
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun AccountItem(
    account: ScannedAccount,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val balanceFormatted = remember(account.totalSats) {
        formatSatoshis(account.totalSats)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .background(
                if (isSelected) Color(0xFF1A2A35) else Color.Transparent
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF4A90D9),
                uncheckedColor = Color(0xFF666666)
            )
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = getAccountLabel(account),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                if (account.hasActivity) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "●",
                        color = Color(0xFF4CAF50),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            Text(
                text = "${account.network} · ${getScriptTypeName(account.scriptType)}",
                color = Color(0xFFB0B0B0),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = account.derivationPath,
                color = Color(0xFF888888),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            if (account.hasActivity) {
                Text(
                    text = balanceFormatted,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${account.utxoCount} UTXO${if (account.utxoCount != 1) "s" else ""}",
                    color = Color(0xFFB0B0B0),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    text = "No activity",
                    color = Color(0xFF888888),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun formatSatoshis(sats: Long): String {
    return if (sats >= 100_000_000) {
        val btc = sats / 100_000_000.0
        String.format(Locale.US, "%.8f BTC", btc)
    } else {
        val formatter = NumberFormat.getNumberInstance(Locale.US)
        "${formatter.format(sats)} sats"
    }
}

private fun getAccountLabel(account: ScannedAccount): String {
    val parts = account.derivationPath.removePrefix("m/").split("/")
    val accountIndex = parts.getOrNull(2)?.removeSuffix("'")?.toIntOrNull() ?: 0
    val scriptName = getScriptTypeName(account.scriptType)
    return "$scriptName Account #${accountIndex + 1}"
}

private fun getScriptTypeName(scriptType: String): String {
    return when (scriptType.uppercase()) {
        "WPKH" -> "Native SegWit"
        "TR" -> "Taproot"
        "SH_WPKH" -> "Nested SegWit"
        "PKH" -> "Legacy"
        else -> scriptType
    }
}
