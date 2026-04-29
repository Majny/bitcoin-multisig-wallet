package com.example.bitcoinwallet.feature.trezorconnect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.core.signer.WalletSummary
import com.example.bitcoinwallet.ui.theme.*

/*
 * Post-discovery picker. Shows every wallet the user has on the connected
 * network (singlesig only - multisig has its own management screen) and
 * pre-selects the row they were last on so an account switch stays on
 * the same visual position.
 */
@Composable
fun SelectAccountScreen(
    wallets: List<WalletSummary>,
    onClose: () -> Unit,
    onConfirm: (WalletSummary) -> Unit
) {
    // Pre-select the account the user was last on. Match by wallet id if we still
    // have one, otherwise fall back to BIP-48 account index (preserved across
    // Switch Account so the previous row stays highlighted).
    var selectedIndex by remember(wallets) {
        val preferredWalletId = SessionStore.activeWalletId
        val preferredAccount = SessionStore.activeAccountIndex
        val initial = wallets.indexOfFirst { w ->
            (!preferredWalletId.isNullOrBlank() && w.id == preferredWalletId) ||
                (preferredAccount != null && w.accountIndex == preferredAccount)
        }
        mutableStateOf(if (initial >= 0) initial else 0)
    }

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

            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextPrimary
                )
            }
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
                            Spacer(Modifier.height(4.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val netColor = if (w.network == "mainnet") BitcoinOrange else TestnetAmber
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(netColor.copy(alpha = 0.18f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = w.network.uppercase(),
                                        color = netColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = w.scriptType,
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

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
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentTeal)
        ) {
            Text("Confirm", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(10.dp))
    }
}
