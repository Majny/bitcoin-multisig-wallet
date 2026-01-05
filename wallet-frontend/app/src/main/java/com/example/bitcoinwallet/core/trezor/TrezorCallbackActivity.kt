package com.example.bitcoinwallet.trezor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.example.bitcoinwallet.app.MainActivity
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.core.signer.TrezorDeviceIdentity
import org.json.JSONObject

class TrezorCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data: Uri = intent?.data ?: run {
            Log.e("TrezorCallback", "No data in callback")
            finish()
            return
        }

        val responseParam = data.getQueryParameter("response")
            ?: data.getQueryParameter("result")
            ?: data.getQueryParameter("payload")

        if (responseParam.isNullOrBlank()) {
            Log.e("TrezorCallback", "Missing response param")
            finish()
            return
        }

        val identity = parseIdentityFromResponse(responseParam)
        if (identity == null) {
            finish()
            return
        }
        SessionStore.pendingIdentity = identity

        startActivity(
            Intent(this@TrezorCallbackActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("trezor_connected", true)
        )

        finish()
    }

    private fun parseIdentityFromResponse(responseJson: String): TrezorDeviceIdentity? {
        return try {
            val root = JSONObject(responseJson)
            val success = root.optBoolean("success", false)
            if (!success) {
                val msg = root.optJSONObject("payload")?.optString("error")
                    ?: root.optString("error")
                Log.e("TrezorCallback", "Trezor error: $msg")
                return null
            }

            val payload = root.optJSONObject("payload") ?: run {
                Log.e("TrezorCallback", "Missing payload")
                return null
            }

            val xpub = payload.optString("xpub", "")
            if (xpub.isBlank()) {
                Log.e("TrezorCallback", "Missing xpub")
                return null
            }

            TrezorDeviceIdentity(
                fingerprint = payload.optString("fingerprint", ""),
                xpub = xpub,
                derivationPath = payload.optString("path", "m/84'/0'/0'"),
                deviceModel = payload.optString("device_model", null),
                deviceLabel = payload.optString("device_label", null)
            )
        } catch (e: Exception) {
            Log.e("TrezorCallback", "Error parsing response JSON", e)
            null
        }
    }
}
