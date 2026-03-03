package com.example.bitcoinwallet.core.trezor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.example.bitcoinwallet.app.MainActivity
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.core.session.SignResultType
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

        Log.d("TrezorCallback", "=== Trezor Callback Received ===")
        Log.d("TrezorCallback", "Full URI: $data")
        Log.d("TrezorCallback", "Query params: ${data.queryParameterNames}")

        val responseParam = data.getQueryParameter("response")
            ?: data.getQueryParameter("result")
            ?: data.getQueryParameter("payload")

        if (responseParam.isNullOrBlank()) {
            Log.e("TrezorCallback", "Missing response param. Available params: ${data.queryParameterNames.joinToString()}")
            finish()
            return
        }

        Log.d("TrezorCallback", "Raw response (${responseParam.length} chars): $responseParam")

        // Determine callback type from the "action" query parameter
        val action = data.getQueryParameter("action") ?: "auth"
        Log.d("TrezorCallback", "Action: $action")

        when (action) {
            "sign" -> handleSignCallback(responseParam)
            else -> handleAuthCallback(responseParam)
        }
    }

    /**
     * Handle getPublicKey callback — used for login/account discovery.
     */
    private fun handleAuthCallback(responseJson: String) {
        val identity = parseIdentityFromResponse(responseJson)
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

    /**
     * Handle signTransaction callback — user confirmed tx on Trezor.
     * Trezor Connect returns serializedTx (raw signed tx hex).
     * Legacy PSBT signing returns signedPsbt.
     */
    private fun handleSignCallback(responseJson: String) {
        val result = parseSignResult(responseJson)
        if (result == null) {
            Log.e("TrezorCallback", "Failed to parse sign response")
            finish()
            return
        }

        SessionStore.setPendingSignedPsbt(result.first)
        SessionStore.setPendingSignType(result.second)

        startActivity(
            Intent(this@TrezorCallbackActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("trezor_signed", true)
        )
        finish()
    }

    /**
     * Parsuje Trezor sign response a rozliší serializedTx (Trezor Connect)
     * od signedPsbt (legacy PSBT flow).
     * Vrací Pair(data, type) nebo null.
     */
    private fun parseSignResult(responseJson: String): Pair<String, SignResultType>? {
        return try {
            val root = JSONObject(responseJson)
            val success = root.optBoolean("success", false)
            if (!success) {
                val payload = root.optJSONObject("payload")
                val msg = payload?.optString("error") ?: root.optString("error")
                val code = payload?.optString("code", "") ?: ""
                Log.e("TrezorCallback", "Trezor sign FAILED: error=$msg, code=$code")
                Log.e("TrezorCallback", "Full error response: $responseJson")
                return null
            }

            val payload = root.optJSONObject("payload") ?: run {
                Log.e("TrezorCallback", "Missing payload in sign response")
                return null
            }

            // Trezor Connect signTransaction vrací serializedTx (kompletní podepsaná tx)
            val serializedTx = payload.optString("serializedTx", "")
            if (serializedTx.isNotBlank()) {
                Log.d("TrezorCallback", "Received serializedTx (${serializedTx.length} chars)")
                return Pair(serializedTx, SignResultType.SERIALIZED_TX)
            }

            // Fallback: signed PSBT (legacy flow)
            val signedPsbt = payload.optString("signedPsbt", "")
                .ifBlank { payload.optString("psbt", "") }
                .ifBlank { payload.optString("hex", "") }

            if (signedPsbt.isBlank()) {
                Log.e("TrezorCallback", "No signed data in response")
                return null
            }

            Log.d("TrezorCallback", "Received signed PSBT (${signedPsbt.length} chars)")
            Pair(signedPsbt, SignResultType.SIGNED_PSBT)
        } catch (e: Exception) {
            Log.e("TrezorCallback", "Error parsing sign response", e)
            null
        }
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
                derivationPath = payload.optString("serializedPath", "").ifBlank {
                    payload.optString("path", "m/84'/1'/0'")
                },
                deviceModel = payload.optString("device_model", null),
                deviceLabel = payload.optString("device_label", null)
            )
        } catch (e: Exception) {
            Log.e("TrezorCallback", "Error parsing response JSON", e)
            null
        }
    }
}
