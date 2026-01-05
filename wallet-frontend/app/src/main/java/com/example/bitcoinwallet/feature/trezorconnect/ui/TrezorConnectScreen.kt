package com.example.bitcoinwallet.feature.trezorconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TrezorConnectScreen(
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(36.dp))

        Text(
            text = "Bitcoin Wallet",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )

        Spacer(Modifier.height(48.dp))

        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(180.dp)
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = "No Wallet Connected",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Please connect your Trezor device\nto load your Bitcoin balance\nand transactions.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB0B0B0)
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B8F2A))
        ) {
            Text("Connect Trezor", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(28.dp))
    }
}
