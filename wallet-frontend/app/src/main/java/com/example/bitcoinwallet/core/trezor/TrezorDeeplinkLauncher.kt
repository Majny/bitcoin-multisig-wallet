package com.example.bitcoinwallet.core.trezor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.bitcoinwallet.core.api.TrezorConnectMultisigDto
import com.example.bitcoinwallet.core.api.TrezorConnectParamsDto
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

class TrezorDeeplinkLauncher(
    private val connectBaseUrl: String = "https://connect.trezor.io/9/deeplink/1/",
    private val callbackScheme: String = "bitcoinwallet", // callback for manifest
    private val callbackHost: String = "trezor-callback",
) {


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

    /**
     * Opens Trezor Suite to sign a PSBT transaction.
     * The user confirms on the Trezor device, then Trezor Suite calls back
     * with the signed PSBT.
     *
     * Uses Trezor Connect deeplink method "signTransaction" with PSBT payload.
     */
    fun openSignTransaction(context: Context, psbtBase64: String, network: String = "testnet"): Boolean {
        val coin = if (network == "testnet") "Testnet" else "Bitcoin"
        val paramsJson = JSONObject().apply {
            put("coin", coin)
            put("psbt", psbtBase64)
        }.toString()

        val requestId = Random.nextInt(1, Int.MAX_VALUE).toString()
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

    /**
     * Opens Trezor Suite to sign a transaction using structured Trezor Connect params.
     * This is the correct format for Trezor Connect's signTransaction deeplink.
     * Used for singlesig wallets where backend provides pre-built inputs/outputs.
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

            // Include refTxs in structured format — Trezor Connect deeplink
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

        val requestId = Random.nextInt(1, Int.MAX_VALUE).toString()
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

    /**
     * Opens Trezor Suite to display an address on the Trezor device screen.
     * This lets the user verify the receive address on the hardware device.
     * For multisig wallets, the multisig object with all cosigner HD nodes is required.
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

        val requestId = Random.nextInt(1, Int.MAX_VALUE).toString()
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

    /**
     * Opens Trezor Suite to get public keys for multiple derivation paths
     * in a single deeplink call using the Trezor Connect `bundle` parameter.
     * Returns the request ID for tracking the callback.
     */
    /**
     * Builds a JSONObject for the Trezor Connect `multisig` field.
     * Backend provides pre-converted HDNodeType objects (depth, fingerprint, etc.)
     * which firmware requires instead of raw xpub strings.
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

        val requestId = Random.nextInt(1, Int.MAX_VALUE).toString()
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
