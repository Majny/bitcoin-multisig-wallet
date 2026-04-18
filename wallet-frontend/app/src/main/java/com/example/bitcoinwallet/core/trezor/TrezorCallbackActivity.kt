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

        // Validate the id matches the request we launched. Guards against stale
        // callbacks (app was killed and an old intent gets redelivered) and
        // against callbacks spoofed by another app — the id is generated from
        // SecureRandom so an attacker cannot guess it.
        val callbackId = data.getQueryParameter("id")
        val expectedId = SessionStore.pendingRequestId
        if (expectedId == null || callbackId == null || callbackId != expectedId) {
            Log.w("TrezorCallback",
                "Rejected callback: id='$callbackId' does not match pending='$expectedId'")
            finish()
            return
        }
        // One-shot: consume the id so it cannot be replayed.
        SessionStore.pendingRequestId = null

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
            "showAddress" -> {
                Log.d("TrezorCallback", "Address verification completed")
                finish()
            }
            else -> handleAuthCallback(responseParam)
        }
    }

    /**
     * Handle getPublicKey callback — used for login/account discovery.
     * Supports bundle mode: Trezor returns all xpubs in a single callback.
     */
    private fun handleAuthCallback(responseJson: String) {
        val data: Uri = intent?.data ?: run { finish(); return }
        val isBundle = data.getQueryParameter("bundle") == "true"

        if (isBundle) {
            val identities = parseBundleResponse(responseJson)
            if (identities.isEmpty()) {
                Log.e("TrezorCallback", "Bundle response contained no valid xpubs")
                finish()
                return
            }
            Log.d("TrezorCallback", "Bundle complete, ${identities.size} xpubs collected")
            SessionStore.pendingBatchXpubs = identities.toMutableList()
            SessionStore.pendingIdentity = identities.first()
        } else {
            // Single mode (backwards compat)
            val identity = parseIdentityFromResponse(responseJson)
            if (identity == null) {
                finish()
                return
            }
            SessionStore.pendingIdentity = identity
            SessionStore.pendingBatchXpubs = mutableListOf(identity)
        }

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
        // Defense-in-depth: reject before forwarding the signature if Trezor Suite
        // identifies the responding device as a different one than what the JWT
        // session is bound to. Catches the "user swapped Trezor between login and
        // signing" case earlier than broadcast failure (singlesig) or backend
        // signature verification (multisig).
        if (isResponseFromDifferentDevice(responseJson)) {
            Log.w("TrezorCallback", "Sign response from a Trezor whose fingerprint differs from session — rejecting")
            SessionStore.setPendingSignedPsbt("WRONG_DEVICE")
            startActivity(
                Intent(this@TrezorCallbackActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
            return
        }

        val result = parseSignResult(responseJson)
        if (result == null) {
            // Classify the failure so UI can distinguish user cancel from protocol error
            // and from the wrong-Trezor case (firmware refuses unknown paths).
            val sentinel = classifyFailure(responseJson)
            Log.e("TrezorCallback", "Sign response failed: classified as $sentinel")
            SessionStore.setPendingSignedPsbt(sentinel)
            startActivity(
                Intent(this@TrezorCallbackActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
            return
        }

        SessionStore.setPendingSignedPsbt(result.first)
        SessionStore.setPendingSignType(result.second)

        startActivity(
            Intent(this@TrezorCallbackActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    /**
     * Inspect a failed Trezor response JSON and decide whether it looks like a
     * user cancel, a wrong-device path rejection, or a generic protocol error.
     * The classification drives the message the UI shows.
     */
    private fun classifyFailure(responseJson: String): String {
        return try {
            val root = JSONObject(responseJson)
            val payload = root.optJSONObject("payload")
            val code = payload?.optString("code", "") ?: ""
            val msg = (payload?.optString("error") ?: root.optString("error", "")).lowercase()
            // Firmware rejects when the requested derivation has no matching key on
            // the connected device — practically means a different Trezor than the
            // one that produced the wallet's xpub is plugged in.
            val looksWrongDevice = msg.contains("forbidden key path") ||
                msg.contains("path not allowed") ||
                msg.contains("invalid public key") ||
                msg.contains("device fingerprint mismatch")
            val looksCancelled = code.contains("Cancel", ignoreCase = true) ||
                msg.contains("cancel") || msg.contains("reject") || msg.contains("denied")
            when {
                looksWrongDevice -> "WRONG_DEVICE"
                looksCancelled -> "CANCELLED"
                else -> "ERROR"
            }
        } catch (_: Exception) {
            "ERROR"
        }
    }

    /**
     * Returns true when the Trezor that produced this response identifies itself
     * with a different master fingerprint than the one we authenticated with.
     * Returns false if either fingerprint is missing — we cannot verify, so we
     * fall through to the existing error paths (firmware key check, broadcast).
     */
    private fun isResponseFromDifferentDevice(responseJson: String): Boolean {
        return try {
            val root = JSONObject(responseJson)
            val payload = root.optJSONObject("payload") ?: return false
            val responseFp = extractDeviceFingerprint(payload) ?: return false
            val sessionFp = SessionStore.session?.user?.trezorFingerprint?.takeIf { it.isNotBlank() }
                ?: return false
            !responseFp.equals(sessionFp, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun extractDeviceFingerprint(payload: JSONObject): String? {
        return payload.optJSONObject("device")?.optString("fingerprint", "")?.ifBlank { null }
            ?: payload.optString("device_fingerprint", "").ifBlank { null }
    }

    /**
     * Parsuje Trezor sign response a rozliší serializedTx (Trezor Connect)
     * od signedPsbt (legacy PSBT flow).
     * Vrací Pair(data, type) nebo null.
     * Pro multisig: také extrahuje `signatures` array a uloží do SessionStore.
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

            // Extract per-input signatures array (used for multisig)
            val signaturesArray = payload.optJSONArray("signatures")
            if (signaturesArray != null) {
                val sigs = mutableListOf<String>()
                for (i in 0 until signaturesArray.length()) {
                    sigs.add(signaturesArray.optString(i, ""))
                }
                Log.d("TrezorCallback", "Extracted ${sigs.size} per-input signatures")
                SessionStore.setPendingTrezorSignatures(sigs)
            } else {
                SessionStore.setPendingTrezorSignatures(null)
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

    /**
     * Parses a bundle response where payload is a JSON array of xpub results.
     */
    private fun parseBundleResponse(responseJson: String): List<TrezorDeviceIdentity> {
        return try {
            val root = JSONObject(responseJson)
            if (!root.optBoolean("success", false)) {
                val msg = root.optJSONObject("payload")?.optString("error")
                    ?: root.optString("error")
                Log.e("TrezorCallback", "Trezor bundle error: $msg")
                return emptyList()
            }

            val payload = root.optJSONArray("payload") ?: run {
                // Fallback: single result wrapped in object (not array)
                val single = parseIdentityFromResponse(responseJson)
                return if (single != null) listOf(single) else emptyList()
            }

            val identities = mutableListOf<TrezorDeviceIdentity>()
            for (i in 0 until payload.length()) {
                val item = payload.getJSONObject(i)
                val xpub = item.optString("xpub", "")
                if (xpub.isBlank()) continue

                identities.add(
                    TrezorDeviceIdentity(
                        fingerprint = item.optString("fingerprint", ""),
                        xpub = xpub,
                        derivationPath = item.optString("serializedPath", "").ifBlank {
                            item.optString("path", "")
                        },
                        deviceModel = item.optString("device_model", null),
                        deviceLabel = item.optString("device_label", null)
                    )
                )
            }
            Log.d("TrezorCallback", "Parsed ${identities.size} identities from bundle")
            identities
        } catch (e: Exception) {
            Log.e("TrezorCallback", "Error parsing bundle response", e)
            emptyList()
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
