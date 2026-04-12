package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitcoinwallet.feature.wallet.viewmodel.MultisigWalletItem
import com.example.bitcoinwallet.feature.wallet.viewmodel.MultisigWalletsUiState
import com.example.bitcoinwallet.ui.theme.*

/**
 * Multisig Wallets list screen.
 * Matches mockup: hamburger, title, "Import Wallet +" button, wallet list with name/M-of-N/balance.
 */
@Composable
fun MultisigWalletsScreen(
    state: MultisigWalletsUiState,
    onMenuClick: () -> Unit,
    onImportWallet: () -> Unit,
    onWalletClick: (MultisigWalletItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        /* ── Top bar: hamburger + label ── */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Menu",
                    tint = TextPrimary
                )
            }
            Text(
                text = "Multisig Wallets",
                color = TextMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        /* ── Title ── */
        Text(
            text = "Multisig Wallets",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 20.dp)
        )

        /* ── Import Wallet button ── */
        Button(
            onClick = onImportWallet,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentTeal,
                contentColor = TextPrimary
            )
        ) {
            Text(
                text = "Import Wallet",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Import",
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        /* ── Content: Loading / Error / List ── */
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            }
            state.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
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
            state.wallets.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No multisig wallets yet.\nImport one to get started.",
                        color = TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                HorizontalDivider(color = DarkCard, thickness = 1.dp)

                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.wallets) { wallet ->
                        MultisigWalletRow(
                            wallet = wallet,
                            onClick = { onWalletClick(wallet) }
                        )
                        HorizontalDivider(color = DarkCard, thickness = 1.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MultisigWalletRow(
    wallet: MultisigWalletItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = wallet.label,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = wallet.mOfN,
                color = TextMuted,
                fontSize = 12.sp
            )
        }

        Text(
            text = wallet.balanceBtc,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ============ Preview ============

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun MultisigWalletsPreview() {
    MultisigWalletsScreen(
        state = MultisigWalletsUiState(
            wallets = listOf(
                MultisigWalletItem("w1", "Family Vault", 2, 3, 25_000_000),
                MultisigWalletItem("w2", "Work Vault", 3, 5, 46_000_000)
            ),
            isLoading = false
        ),
        onMenuClick = {},
        onImportWallet = {},
        onWalletClick = {}
    )
}
