package com.example.bitcoinwallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitcoinwallet.ui.theme.*

enum class DrawerItem(val label: String) {
    HOME("Home"),
    MULTISIG("Multisig Wallets"),
    SETTINGS("Settings")
}

/**
 * Drawer content for the hamburger menu.
 * Matches the dark-themed mockup: title "Bitcoin Wallet", then
 * Home / Multisig Wallets / Settings separated by dividers.
 */
@Composable
fun DrawerContent(
    onItemClick: (DrawerItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 280.dp)
            .background(DarkSurface)
            .padding(top = 48.dp)
    ) {
        /* ── Title ── */
        Text(
            text = "Bitcoin Wallet",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp)
        )

        HorizontalDivider(color = DarkCard, thickness = 1.dp)

        /* ── Menu items ── */
        DrawerItem.entries.forEach { item ->
            DrawerMenuItem(
                label = item.label,
                onClick = { onItemClick(item) }
            )
            HorizontalDivider(color = DarkCard, thickness = 1.dp)
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DrawerMenuItem(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ============ Preview ============

@Preview(showBackground = true, backgroundColor = 0xFF16213E)
@Composable
private fun DrawerContentPreview() {
    DrawerContent(onItemClick = {})
}
