package com.example.bitcoinwallet.core.trezor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.example.bitcoinwallet.core.session.SessionStore

/*
 * Shown when a Trezor Connect deeplink finds no handler - i.e. Trezor Suite
 * Mobile is not installed on the device. Offers a Play Store shortcut so the
 * user can install it without leaving the flow context.
 */
@Composable
fun TrezorSuiteMissingDialog() {
    val visible by SessionStore.trezorSuiteMissing.collectAsState()
    if (!visible) return

    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { SessionStore.setTrezorSuiteMissing(false) },
        title = { Text("Trezor Suite Mobile not installed") },
        text = {
            Text(
                "Signing transactions with this app requires the Trezor Suite " +
                    "Mobile application, which relays communication with your " +
                    "Trezor hardware wallet.\n\n" +
                    "After installing, please open Trezor Suite Mobile first " +
                    "and complete its initial setup (accept the terms and set " +
                    "the PIN). Until then, deeplinks from this app will not work."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                SessionStore.setTrezorSuiteMissing(false)
                val marketUri = Uri.parse("market://details?id=io.trezor.suite")
                val webUri = Uri.parse("https://play.google.com/store/apps/details?id=io.trezor.suite")
                val marketIntent = Intent(Intent.ACTION_VIEW, marketUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(marketIntent)
                } catch (e: ActivityNotFoundException) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, webUri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) {
                Text("Open Google Play")
            }
        },
        dismissButton = {
            TextButton(onClick = { SessionStore.setTrezorSuiteMissing(false) }) {
                Text("Close")
            }
        }
    )
}
