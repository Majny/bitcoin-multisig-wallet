package com.example.bitcoinwallet.core.trezor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.bitcoinwallet.core.api.TrezorConnectMultisigDto
import com.example.bitcoinwallet.core.api.TrezorConnectParamsDto
import com.example.bitcoinwallet.core.session.SessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/*
 * Builds Trezor Connect deeplinks and hands them to Trezor Suite Mobile via
 * Intent.ACTION_VIEW. Every call generates a random request id, parks it in
 * SessionStore, and embeds it in the callback URL - TrezorCallbackActivity
 * checks the returned id against the stored one to reject stale or spoofed
 * callbacks from other apps.
 */
class TrezorDeeplinkLauncher(
    private val connectBaseUrl: String = "https://connect.trezor.io/9/deeplink/1/",
    private val callbackScheme: String = "bitcoinwallet",
    private val callbackHost: String = "trezor-callback",
) {

    /*
     * Cryptographically random id, stored in SessionStore so the matching
     * callback can be authenticated. SecureRandom rather than UUID.random
     * to make sure the id can't be guessed by another app on the device.
     */
    private fun newRequestId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val id = bytes.joinToString("") { "%02x".format(it) }
        SessionStore.pendingRequestId = id
        return id
    }

    /* getPublicKey for a single derivation path - used during initial Trezor
     * connect to grab one xpub. Returns false if Trezor Suite Mobile is not
     * installed (no app handles the deeplink). */
    fun openGetPublicKey(
        context: Context,
        derivationPath: String = "m/84'/1'/0'",
        network: String = "testnet"
    ): Boolean {
        val coin = if (network == "testnet") "Testnet" else "Bitcoin"
        val paramsJson = JSONObject().apply {
            put("coin", coin)
            put("path", derivationPath)
            put("showOnTrezor", false)
            put("suppressBackupWarning", true)
        }.toString()

        val requestId = newRequestId()
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

    /*
     * Sign a PSBT - opens Trezor Suite, user confirms on the device, callback
     * carries the signed PSBT base64 back. Used by the multisig flow where
     * the backend hands us a serialized PSBT to forward unchanged.
     */
    fun openSignTransaction(context: Context, psbtBase64: String, network: String = "testnet"): Boolean {
        val coin = if (network == "testnet") "Testnet" else "Bitcoin"
        val paramsJson = JSONObject().apply {
            put("coin", coin)
            put("psbt", psbtBase64)
        }.toString()

        val requestId = newRequestId()
        val callbackUrl = "$callbackScheme://$callbackHost?id=$requestId&action=sign"

        val uri = Uri.parse(connectBaseUrl).buildUpon()
            .appendQueryParameter("method", "signTransaction")
            .appendQueryParameter("params", paramsJson)
            .appendQueryParameter("callback", callbackUrl)
            .build()

        Log.d("TrezorDeeplink", "openSignTransaction: network=$network coin=$coin")
        Log.d("TrezorDeeplink", "PSBT base64 (${psbtBase64.length} chars): $psbtBase64")
        Log.d("TrezorDeeplink", "paramsJson: $paramsJson")
        Log.d("TrezorDeeplink", "deeplink URI: $uri")

        val intent = Intent(Intent.ACTION_VIEW, uri)

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e("TrezorDeeplink", "No app can handle Trezor deeplink (install Trezor Suite Mobile)", e)
            false
        }
    }

    /*
     * Sign a tx using structured Trezor Connect params (inputs/outputs/refTxs)
     * rather than a serialized PSBT. Required for singlesig - Trezor Connect
     * deeplink does NOT accept tx_hex for refTxs, so the backend pre-parses
     * each previous tx into version/inputs/bin_outputs/lock_time and we
     * marshal the whole structure into JSON here.
     */
    fun openSignTransactionStructured(
        context: Context,
        params: TrezorConnectParamsDto
    ): Boolean {
        val paramsJson = JSONObject().apply {
            put("coin", params.coin)
            put("version", params.version)
            put("locktime", params.locktime)
            put("push", false)

            val inputsArray = JSONArray()
            for (input in params.inputs) {
                inputsArray.put(JSONObject().apply {
                    put("address_n", JSONArray(input.address_n))
                    put("prev_hash", input.prev_hash)
                    put("prev_index", input.prev_index)
                    put("amount", input.amount)
                    put("script_type", input.script_type)
                    put("sequence", input.sequence)
                    input.multisig?.let { ms ->
                        put("multisig", buildMultisigJson(ms))
                    }
                })
            }
            put("inputs", inputsArray)

            val outputsArray = JSONArray()
            for (output in params.outputs) {
                outputsArray.put(JSONObject().apply {
                    if (output.address != null) {
                        put("address", output.address)
                    }
                    if (output.address_n != null) {
                        put("address_n", JSONArray(output.address_n))
                    }
                    put("amount", output.amount)
                    put("script_type", output.script_type)
                    output.multisig?.let { ms ->
                        put("multisig", buildMultisigJson(ms))
                    }
                })
            }
            put("outputs", outputsArray)

            // Include refTxs in structured format - Trezor Connect deeplink
            // does NOT support tx_hex, needs parsed version/inputs/bin_outputs/lock_time.
            if (params.refTxs != null && params.refTxs.isNotEmpty()) {
                val refTxsArray = JSONArray()
                for (rtx in params.refTxs) {
                    refTxsArray.put(JSONObject().apply {
                        put("hash", rtx.hash)
                        put("version", rtx.version)
                        put("lock_time", rtx.lock_time)

                        val inputsArr = JSONArray()
                        for (inp in rtx.inputs) {
                            inputsArr.put(JSONObject().apply {
                                put("prev_hash", inp.prev_hash)
                                put("prev_index", inp.prev_index)
                                put("script_sig", inp.script_sig)
                                put("sequence", inp.sequence)
                            })
                        }
                        put("inputs", inputsArr)

                        val outputsArr = JSONArray()
                        for (out in rtx.bin_outputs) {
                            outputsArr.put(JSONObject().apply {
                                put("amount", out.amount)
                                put("script_pubkey", out.script_pubkey)
                            })
                        }
                        put("bin_outputs", outputsArr)
                    })
                }
                put("refTxs", refTxsArray)
            }
        }.toString()

        val requestId = newRequestId()
        val callbackUrl = "$callbackScheme://$callbackHost?id=$requestId&action=sign"

        val uri = Uri.parse(connectBaseUrl).buildUpon()
            .appendQueryParameter("method", "signTransaction")
            .appendQueryParameter("params", paramsJson)
            .appendQueryParameter("callback", callbackUrl)
            .build()

        Log.d("TrezorDeeplink", "openSignTransactionStructured: coin=${params.coin} inputs=${params.inputs.size} outputs=${params.outputs.size}")
        Log.d("TrezorDeeplink", "paramsJson (${paramsJson.length} chars): $paramsJson")
        Log.d("TrezorDeeplink", "deeplink URI (${uri.toString().length} chars): $uri")
        if (uri.toString().length > 2000) {
            Log.w("TrezorDeeplink", "WARNING: URI is very long (${uri.toString().length} chars), may exceed Android intent limits")
        }

        val intent = Intent(Intent.ACTION_VIEW, uri)

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e("TrezorDeeplink", "No app can handle Trezor deeplink (install Trezor Suite Mobile)", e)
            false
        }
    }

    /*
     * Show an address on the Trezor screen so the user can compare it byte-by-byte
     * with what the phone displays. For multisig, the full cosigner pubkey set
     * (the `multisig` field) is required - without it firmware can't recompute
     * the script and refuses to display the address.
     */
    fun openGetAddress(
        context: Context,
        path: List<Long>,
        coin: String,
        scriptType: String,
        multisig: com.example.bitcoinwallet.core.api.TrezorConnectMultisigDto? = null
    ): Boolean {
        val paramsJson = JSONObject().apply {
            put("coin", coin)
            put("path", JSONArray(path))
            put("showOnTrezor", true)
            put("scriptType", scriptType)
            multisig?.let { ms ->
                put("multisig", buildMultisigJson(ms))
            }
        }.toString()

        val requestId = newRequestId()
        val callbackUrl = "$callbackScheme://$callbackHost?id=$requestId&action=showAddress"

        val uri = Uri.parse(connectBaseUrl).buildUpon()
            .appendQueryParameter("method", "getAddress")
            .appendQueryParameter("params", paramsJson)
            .appendQueryParameter("callback", callbackUrl)
            .build()

        Log.d("TrezorDeeplink", "openGetAddress: coin=$coin scriptType=$scriptType multisig=${multisig != null}")
        Log.d("TrezorDeeplink", "paramsJson: $paramsJson")

        val intent = Intent(Intent.ACTION_VIEW, uri)

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e("TrezorDeeplink", "No app can handle Trezor deeplink (install Trezor Suite Mobile)", e)
            false
        }
    }

    /*
     * Builds the Trezor Connect `multisig` JSON. Firmware needs HDNodeType
     * objects (depth, fingerprint, child_num, chain_code, public_key) - not
     * raw xpub strings - so the backend pre-converts and we copy the fields
     * across.
     */
    private fun buildMultisigJson(ms: TrezorConnectMultisigDto): JSONObject {
        return JSONObject().apply {
            val pubkeysArray = JSONArray()
            for (pk in ms.pubkeys) {
                pubkeysArray.put(JSONObject().apply {
                    put("node", JSONObject().apply {
                        put("depth", pk.node.depth)
                        put("fingerprint", pk.node.fingerprint)
                        put("child_num", pk.node.child_num)
                        put("chain_code", pk.node.chain_code)
                        put("public_key", pk.node.public_key)
                    })
                    put("address_n", JSONArray(pk.address_n))
                })
            }
            put("pubkeys", pubkeysArray)
            put("m", ms.m)
            val sigsArray = JSONArray()
            for (sig in ms.signatures) {
                sigsArray.put(sig)
            }
            put("signatures", sigsArray)
        }
    }

    /*
     * Bundle getPublicKey - fetches multiple xpubs in a single deeplink so
     * account discovery doesn't require N round-trips through Trezor Suite.
     * Returns the request id for trace logging or null if the deeplink
     * couldn't be launched.
     */
    fun openGetPublicKeyBundle(
        context: Context,
        derivationPaths: List<String>,
        network: String = "mainnet"
    ): String? {
        if (derivationPaths.isEmpty()) return null

        val coin = if (network == "testnet") "Testnet" else "Bitcoin"
        val bundle = JSONArray()
        for (path in derivationPaths) {
            bundle.put(JSONObject().apply {
                put("path", path)
                put("coin", coin)
                put("showOnTrezor", false)
                put("suppressBackupWarning", true)
            })
        }
        val paramsJson = JSONObject().apply {
            put("bundle", bundle)
        }.toString()

        val requestId = newRequestId()
        val callbackUrl = "$callbackScheme://$callbackHost?id=$requestId&bundle=true"

        val uri = Uri.parse(connectBaseUrl).buildUpon()
            .appendQueryParameter("method", "getPublicKey")
            .appendQueryParameter("params", paramsJson)
            .appendQueryParameter("callback", callbackUrl)
            .build()

        Log.d("TrezorDeeplink", "openGetPublicKeyBundle: ${derivationPaths.size} paths, network=$network")

        val intent = Intent(Intent.ACTION_VIEW, uri)

        return try {
            context.startActivity(intent)
            requestId
        } catch (e: ActivityNotFoundException) {
            Log.e("TrezorDeeplink", "No app can handle Trezor deeplink (install Trezor Suite Mobile)", e)
            null
        }
    }
}
