package com.example.bitcoinwallet.feature.trezorconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TrezorConnectedScreen(
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(36.dp))

        Text(
            text = "Trezor Connected",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )

        Spacer(Modifier.height(48.dp))

        Box(contentAlignment = Alignment.BottomEnd) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(190.dp)
            )
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF1B8F2A),
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "Your Trezor device is connected\nand ready to use.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB0B0B0)
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B8F2A))
        ) {
            Text("Continue", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "For your security, always check your\ndevice screen.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF7D7D7D)
        )

        Spacer(Modifier.height(22.dp))
    }
}
