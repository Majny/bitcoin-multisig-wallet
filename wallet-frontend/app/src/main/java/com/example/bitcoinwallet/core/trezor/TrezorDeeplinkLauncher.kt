package com.example.bitcoinwallet.core.trezor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.bitcoinwallet.core.api.TrezorConnectParamsDto
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

class TrezorDeeplinkLauncher(
    private val connectBaseUrl: String = "https://connect.trezor.io/9/deeplink/1/",
    private val callbackScheme: String = "bitcoinwallet", // callback for manifest
    private val callbackHost: String = "trezor-callback",
) {

    companion object {
        /**
         * Standard BIP derivation paths for Bitcoin.
         * Purpose: 84 = Native SegWit (bech32), 86 = Taproot, 49 = Nested SegWit, 44 = Legacy
         * Coin type: 0 = mainnet, 1 = testnet
         */
        val MAINNET_DERIVATION_PATHS = listOf(
            "m/84'/0'/0'",  // Native SegWit account 0
            "m/84'/0'/1'",  // Native SegWit account 1
            "m/84'/0'/2'",  // Native SegWit account 2
            "m/86'/0'/0'",  // Taproot account 0
            "m/86'/0'/1'",  // Taproot account 1
        )

        val TESTNET_DERIVATION_PATHS = listOf(
            "m/84'/1'/0'",  // Native SegWit testnet account 0
            "m/84'/1'/1'",  // Native SegWit testnet account 1
            "m/86'/1'/0'",  // Taproot testnet account 0
        )

        /**
         * Get the next derivation path to scan.
         * Used for sequential account discovery.
         */
        fun getDerivationPath(purpose: Int, coinType: Int, accountIndex: Int): String {
            return "m/$purpose'/$coinType'/$accountIndex'"
        }
    }

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
                })
            }
            put("outputs", outputsArray)

            // NOTE: refTxs are NOT sent via deeplink — Trezor Suite Mobile fetches
            // reference transactions internally via its own blockbook backend.
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
     */
    fun openGetAddress(context: Context, derivationPath: String = "m/84'/1'/0'/0/0", network: String = "testnet"): Boolean {
        val coin = if (network == "testnet") "Testnet" else "Bitcoin"
        val paramsJson = JSONObject().apply {
            put("coin", coin)
            put("path", derivationPath)
            put("showOnTrezor", true)
        }.toString()

        val requestId = Random.nextInt(1, Int.MAX_VALUE).toString()
        val callbackUrl = "$callbackScheme://$callbackHost?id=$requestId&action=showAddress"

        val uri = Uri.parse(connectBaseUrl).buildUpon()
            .appendQueryParameter("method", "getAddress")
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
     * Opens Trezor Suite to get public keys for multiple derivation paths.
     * Returns the request ID for tracking the callback.
     */
    fun openGetPublicKeyBatch(
        context: Context,
        derivationPaths: List<String>,
        currentIndex: Int = 0
    ): String? {
        if (currentIndex >= derivationPaths.size) return null

        val path = derivationPaths[currentIndex]
        val paramsJson = JSONObject().apply {
            put("coin", "Bitcoin")
            put("path", path)
        }.toString()

        val requestId = Random.nextInt(1, Int.MAX_VALUE).toString()
        // Encode remaining paths and current index in callback
        val callbackUrl = "$callbackScheme://$callbackHost?id=$requestId&batchIndex=$currentIndex&batchTotal=${derivationPaths.size}"

        val uri = Uri.parse(connectBaseUrl).buildUpon()
            .appendQueryParameter("method", "getPublicKey")
            .appendQueryParameter("params", paramsJson)
            .appendQueryParameter("callback", callbackUrl)
            .build()

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
