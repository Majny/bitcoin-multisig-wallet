package cz.majny.wallet.explorer.http

import cz.majny.wallet.explorer.client.BlockchainClient
import cz.majny.wallet.explorer.service.WalletExplorer
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ExplorerRoutes")

/**
 * REST API endpointy pro explorer-service.
 *
 * Wallet-level agregace:
 *   GET /explorer/wallet/{id}/balance
 *   GET /explorer/wallet/{id}/transactions
 *   GET /explorer/wallet/{id}/utxos
 *   GET /explorer/wallet/{id}/receive-address
 *
 * Transaction detail:
 *   GET /explorer/tx/{txid}
 *   GET /explorer/tx/{txid}/detail?walletId=...
 */
fun Route.explorerRoutes(
    explorer: WalletExplorer,
    blockchainClient: BlockchainClient
) {
    route("/explorer") {

        // ========== Wallet-level endpoints ==========

        route("/wallet/{walletId}") {

            /**
             * GET /explorer/wallet/{walletId}/balance
             *
             * Vrátí celkový zůstatek peněženky (confirmed + unconfirmed).
             */
            get("/balance") {
                val walletId = call.parameters["walletId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing walletId"))

                try {
                    val balance = explorer.getWalletBalance(walletId)
                    call.respond(balance)
                } catch (e: Exception) {
                    log.error("Failed to get balance for {}", walletId, e)
                    call.respond(HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Failed to get balance")))
                }
            }

            /**
             * GET /explorer/wallet/{walletId}/transactions?limit=50&offset=0
             *
             * Vrátí historii transakcí seřazenou chronologicky.
             * Podporuje paginaci.
             */
            get("/transactions") {
                val walletId = call.parameters["walletId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing walletId"))

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                try {
                    val txs = explorer.getWalletTransactions(walletId, limit, offset)
                    call.respond(txs)
                } catch (e: Exception) {
                    log.error("Failed to get transactions for {}", walletId, e)
                    call.respond(HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Failed to get transactions")))
                }
            }

            /**
             * GET /explorer/wallet/{walletId}/utxos
             *
             * Vrátí všechny UTXOs peněženky pro coin control.
             * Každý UTXO je obohacen o adresu a derivation info.
             */
            get("/utxos") {
                val walletId = call.parameters["walletId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing walletId"))

                try {
                    val utxos = explorer.getWalletUtxos(walletId)
                    call.respond(utxos)
                } catch (e: Exception) {
                    log.error("Failed to get UTXOs for {}", walletId, e)
                    call.respond(HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Failed to get UTXOs")))
                }
            }

            /**
             * GET /explorer/wallet/{walletId}/receive-address
             *
             * Vrátí první nepoužitou receive adresu.
             */
            get("/receive-address") {
                val walletId = call.parameters["walletId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing walletId"))

                try {
                    val addr = explorer.getNextReceiveAddress(walletId)
                    call.respond(addr)
                } catch (e: Exception) {
                    log.error("Failed to get receive address for {}", walletId, e)
                    call.respond(HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Failed to get receive address")))
                }
            }
        }

        // ========== Transaction-level endpoints ==========

        /**
         * GET /explorer/tx/{txid}
         *
         * Vrátí detail transakce — raw data z blockchain.
         */
        get("/tx/{txid}") {
            val txid = call.parameters["txid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing txid"))

            val network = call.request.queryParameters["network"] ?: "mainnet"

            try {
                val tx = blockchainClient.getTransaction(txid, network)

                val detail = TransactionDetailResponse(
                    txid = tx.txid,
                    version = tx.version,
                    locktime = tx.locktime,
                    size = tx.size,
                    weight = tx.weight,
                    fee = tx.fee,
                    confirmed = tx.status.confirmed,
                    blockHeight = tx.status.block_height,
                    blockTime = tx.status.block_time,
                    inputs = tx.vin.map { input ->
                        TransactionInput(
                            txid = input.txid,
                            vout = input.vout,
                            address = input.prevout?.scriptpubkey_address ?: "",
                            valueSats = input.prevout?.value ?: 0
                        )
                    },
                    outputs = tx.vout.mapIndexed { idx, output ->
                        TransactionOutput(
                            index = idx,
                            address = output.scriptpubkey_address,
                            valueSats = output.value
                        )
                    }
                )
                call.respond(detail)
            } catch (e: Exception) {
                log.error("Failed to get tx {}", txid, e)
                call.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Failed to get transaction")))
            }
        }

        /**
         * GET /explorer/tx/{txid}/detail?walletId={walletId}
         *
         * Detail transakce s označením "isMine" pro inputy/outputy.
         */
        get("/tx/{txid}/detail") {
            val txid = call.parameters["txid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing txid"))
            val walletId = call.request.queryParameters["walletId"]

            try {
                // Pokud je walletId, načti adresy pro "isMine" labeling a detekci sítě
                val myAddresses = if (walletId != null) {
                    try {
                        explorer.getWalletAddresses(walletId)
                    } catch (_: Exception) {
                        emptySet()
                    }
                } else {
                    emptySet()
                }

                // Detect network from wallet addresses (tb1... = testnet),
                // fallback to explicit query parameter or mainnet
                val network = when {
                    myAddresses.any { it.startsWith("tb1") || it.startsWith("2") ||
                            it.startsWith("m") || it.startsWith("n") } -> "testnet"
                    else -> call.request.queryParameters["network"] ?: "mainnet"
                }

                val tx = blockchainClient.getTransaction(txid, network)

                val detail = TransactionDetailResponse(
                    txid = tx.txid,
                    version = tx.version,
                    locktime = tx.locktime,
                    size = tx.size,
                    weight = tx.weight,
                    fee = tx.fee,
                    confirmed = tx.status.confirmed,
                    blockHeight = tx.status.block_height,
                    blockTime = tx.status.block_time,
                    inputs = tx.vin.map { input ->
                        val addr = input.prevout?.scriptpubkey_address ?: ""
                        TransactionInput(
                            txid = input.txid,
                            vout = input.vout,
                            address = addr,
                            valueSats = input.prevout?.value ?: 0,
                            isMine = addr in myAddresses
                        )
                    },
                    outputs = tx.vout.mapIndexed { idx, output ->
                        TransactionOutput(
                            index = idx,
                            address = output.scriptpubkey_address,
                            valueSats = output.value,
                            isMine = output.scriptpubkey_address in myAddresses
                        )
                    }
                )
                call.respond(detail)
            } catch (e: Exception) {
                log.error("Failed to get tx detail {}", txid, e)
                call.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Failed to get transaction detail")))
            }
        }

        // ========== Fee estimates (proxied) ==========

        /**
         * GET /explorer/fees
         *
         * Doporučené fee rates.
         */
        get("/fees") {
            try {
                val fees = blockchainClient.getFeeEstimates()
                call.respond(fees)
            } catch (e: Exception) {
                log.error("Failed to get fee estimates", e)
                call.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Failed to get fee estimates")))
            }
        }
    }
}
