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
import androidx.compose.ui.unit.dp
import com.example.bitcoinwallet.ui.theme.*

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
            color = TextPrimary
        )

        Spacer(Modifier.height(48.dp))

        Box(contentAlignment = Alignment.BottomEnd) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(190.dp)
            )
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = TrezorGreen,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "Your Trezor device is connected\nand ready to use.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TrezorGreen)
        ) {
            Text("Continue", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "For your security, always check your\ndevice screen.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )

        Spacer(Modifier.height(22.dp))
    }
}
