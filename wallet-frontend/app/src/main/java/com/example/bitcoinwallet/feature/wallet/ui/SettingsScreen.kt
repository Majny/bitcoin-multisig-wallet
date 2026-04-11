package com.example.bitcoinwallet.feature.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.ui.theme.*

private val currencies = listOf("CZK", "USD", "EUR")

/**
 * Settings screen — Trezor device status, currency picker, app info.
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onSwitchAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCurrency by remember {
        mutableStateOf(SessionStore.preferredCurrency.value.uppercase())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        /* ── Top bar ── */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Settings",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
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

        /* ── Account section ── */
        SectionLabel("Account")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Trezor", color = TextMuted, fontSize = 14.sp)
                Text(
                    text = "Connected",
                    color = ReceiveGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onSwitchAccount,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentTeal.copy(alpha = 0.15f),
                    contentColor = AccentTeal
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(text = "Switch Account", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        /* ── Currency section ── */
        SectionLabel("Currency")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            currencies.forEach { code ->
                val isSelected = code == selectedCurrency
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (isSelected) Modifier.background(AccentTeal)
                            else Modifier
                                .background(Color.Transparent)
                                .border(1.dp, DarkCard, RoundedCornerShape(10.dp))
                        )
                        .clickable {
                            selectedCurrency = code
                            SessionStore.setPreferredCurrency(code.lowercase())
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = code,
                        color = if (isSelected) Color.White else TextSecondary,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        /* ── App info section ── */
        SectionLabel("App Info")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .padding(16.dp)
        ) {
            SettingsRow("Network", "Bitcoin mainnet")
            HorizontalDivider(color = DarkCard, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
            SettingsRow("Address type", "Native SegWit (P2WSH)")
            HorizontalDivider(color = DarkCard, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
            SettingsRow("App version", "1.0.0")
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

/* ── Small helpers ── */

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
    )
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
    SettingsScreen(onClose = {}, onSwitchAccount = {})
}
