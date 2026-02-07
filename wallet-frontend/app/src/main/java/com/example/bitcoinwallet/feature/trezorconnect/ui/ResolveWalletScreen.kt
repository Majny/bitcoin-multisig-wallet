package com.example.bitcoinwallet.feature.trezorconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bitcoinwallet.ui.theme.*

@Composable
fun ResolveWalletScreen(
    runResolve: suspend () -> Unit,
    onErrorGoBack: () -> Unit
) {
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {

        try {
            runResolve()
        } catch (t: Throwable) {
            error = t.message ?: "Unknown error"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))

        Text(
            text = if (error == null) "Loading wallets…" else "Something went wrong",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary
        )

        Spacer(Modifier.height(16.dp))

        if (error == null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Signing you in and fetching your wallets.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        } else {
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onErrorGoBack,
                colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary)
            ) {
                Text("Back")
            }
        }
    }
}
