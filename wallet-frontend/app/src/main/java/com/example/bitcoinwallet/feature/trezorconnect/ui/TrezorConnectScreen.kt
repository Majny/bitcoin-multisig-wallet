package com.example.bitcoinwallet.feature.trezorconnect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bitcoinwallet.ui.theme.*

@Composable
fun TrezorConnectScreen(
    onConnect: (network: String) -> Unit
) {
    var selectedNetwork by remember { mutableStateOf("testnet") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            text = "Bitcoin Wallet",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary
        )

        Spacer(Modifier.weight(0.35f))

        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(180.dp)
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = "No Wallet Connected",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Please connect your Trezor device\nto load your Bitcoin balance\nand transactions.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(Modifier.height(32.dp))

        // Network toggle
        NetworkToggle(
            selectedNetwork = selectedNetwork,
            onNetworkSelected = { selectedNetwork = it }
        )

        Spacer(Modifier.weight(0.8f))

        Button(
            onClick = { onConnect(selectedNetwork) },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TrezorGreen)
        ) {
            Text("Connect Trezor", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun NetworkToggle(
    selectedNetwork: String,
    onNetworkSelected: (String) -> Unit
) {
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DarkSurface)
    ) {
        NetworkSegment(
            label = "Mainnet",
            network = "mainnet",
            isSelected = selectedNetwork == "mainnet",
            activeColor = BitcoinOrange,
            modifier = Modifier.weight(1f),
            onClick = { onNetworkSelected("mainnet") }
        )

        NetworkSegment(
            label = "Testnet",
            network = "testnet",
            isSelected = selectedNetwork == "testnet",
            activeColor = TestnetAmber,
            modifier = Modifier.weight(1f),
            onClick = { onNetworkSelected("testnet") }
        )
    }
}

@Composable
private fun NetworkSegment(
    label: String,
    network: String,
    isSelected: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) activeColor else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else TextSecondary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
