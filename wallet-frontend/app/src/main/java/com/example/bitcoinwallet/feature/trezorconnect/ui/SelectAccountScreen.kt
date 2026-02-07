package com.example.bitcoinwallet.feature.trezorconnect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.bitcoinwallet.core.signer.WalletSummary
import com.example.bitcoinwallet.ui.theme.*

@Composable
fun SelectAccountScreen(
    wallets: List<WalletSummary>,
    onClose: () -> Unit,
    onConfirm: (WalletSummary) -> Unit
) {
    var selectedIndex by remember { mutableStateOf(0) }

    LaunchedEffect(wallets.size) {
        if (selectedIndex !in wallets.indices) selectedIndex = 0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {

            Text(
                text = "Select Account",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                modifier = Modifier.align(Alignment.Center)
            )

            Text(
                text = "X",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
        ) {
            if (wallets.isEmpty()) {
                Text(
                    text = "No accounts returned from backend.",
                    color = TextSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                wallets.forEachIndexed { i, w ->
                    val isSelected = i == selectedIndex

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedIndex = i }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = w.label?.takeIf { it.isNotBlank() } ?: "Account #${i + 1}",
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(2.dp))

                            Text(
                                text = "${w.network} · ${w.scriptType}",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            if (w.type == com.example.bitcoinwallet.core.signer.WalletType.MULTI_SIG && w.m != null && w.n != null) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Multisig ${w.m}-of-${w.n}",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = { selectedIndex = i }
                        )
                    }

                    if (i != wallets.lastIndex) {
                        Divider(color = DividerColor, thickness = 1.dp)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { if (wallets.isNotEmpty()) onConfirm(wallets[selectedIndex]) },
            enabled = wallets.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary)
        ) {
            Text("Confirm", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(10.dp))
    }
}
