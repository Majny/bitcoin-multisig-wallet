package com.example.bitcoinwallet.core.trezor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import kotlin.random.Random

class TrezorDeeplinkLauncher(
    private val connectBaseUrl: String = "https://connect.trezor.io/9/deeplink/1/",
    private val callbackScheme: String = "bitcoinwallet", // callback for manifest
    private val callbackHost: String = "trezor-callback",
) {
    fun openGetPublicKey(context: Context, derivationPath: String = "m/84'/0'/0'" /*  TODO: add for cycle for derivation path */): Boolean {
        val paramsJson = JSONObject().apply {
            put("coin", "btc")
            put("path", derivationPath)
        }.toString()

        // to identify request callbacks
        val requestId = Random.nextInt(1, Int.MAX_VALUE).toString()
        val callbackUrl = "$callbackScheme://$callbackHost?id=$requestId"

        val uri = Uri.parse(connectBaseUrl).buildUpon()
            .appendQueryParameter("method", "getPublicKey")
            .appendQueryParameter("params", paramsJson)
            .appendQueryParameter("callback", callbackUrl)
            .build()

        val intent = Intent(Intent.ACTION_VIEW, uri)

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e("TrezorDeeplink", "No app can handle Trezor deeplink (install Trezor Suite Mobile)", e)
            false
        }
    }
}
