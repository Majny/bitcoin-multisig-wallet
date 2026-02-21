package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.ui.theme.*

/**
 * Settings screen — dark themed.
 * Shows wallet info and app version.
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val walletId = SessionStore.activeWalletId ?: "—"
    val shortWalletId = if (walletId.length > 16) "${walletId.take(8)}…${walletId.takeLast(8)}" else walletId

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        /* ── Top bar ── */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Settings", color = TextMuted, fontSize = 14.sp)
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextPrimary
                )
            }
        }

        /* ── Title ── */
        Text(
            text = "Settings",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        /* ── Info card ── */
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .padding(16.dp)
        ) {
            SettingsRow("Active wallet", shortWalletId)
            HorizontalDivider(color = DarkCard, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
            SettingsRow("Network", "Bitcoin mainnet")
            HorizontalDivider(color = DarkCard, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
            SettingsRow("Address type", "Native SegWit (P2WSH)")
            HorizontalDivider(color = DarkCard, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
            SettingsRow("App version", "1.0.0")
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextMuted, fontSize = 13.sp)
        Text(text = value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ============ Preview ============

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun SettingsPreview() {
    SettingsScreen(onClose = {})
}
