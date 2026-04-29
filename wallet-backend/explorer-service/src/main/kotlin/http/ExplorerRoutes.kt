package cz.majny.wallet.explorer.http

import cz.majny.wallet.explorer.client.BlockchainClient
import cz.majny.wallet.explorer.service.WalletExplorer
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ExplorerRoutes")

/*
 * explorer-service routes. All endpoints are wallet-level aggregations on top
 * of blockchain-service per-address calls - they hide the N+1 problem from
 * the frontend by fanning out internally.
 */
fun Route.explorerRoutes(
    explorer: WalletExplorer,
    blockchainClient: BlockchainClient
) {
    route("/explorer") {

        route("/wallet/{walletId}") {

            /*
             * GET /explorer/wallet/{walletId}/balance
             * Confirmed + unconfirmed totals across every wallet address.
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

            /*
             * GET /explorer/wallet/{walletId}/transactions?limit=50&offset=0
             * Chronological tx history with SENT/RECEIVED classification done here.
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

            /*
             * GET /explorer/wallet/{walletId}/utxos
             * Full UTXO list enriched with derivation info, used by the
             * coin-control screen.
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

            /*
             * GET /explorer/wallet/{walletId}/receive-address
             * First unused receive address. Derives a new one past the gap
             * limit if every pre-derived address has on-chain activity.
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

            /*
             * GET /explorer/wallet/{walletId}/change-address
             * First unused change address. Called by psbt-service when
             * building a new PSBT. Privacy invariant: every outgoing tx
             * gets a fresh change output, never reused.
             */
            get("/change-address") {
                val walletId = call.parameters["walletId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing walletId"))

                try {
                    val addr = explorer.getNextChangeAddress(walletId)
                    call.respond(addr)
                } catch (e: Exception) {
                    log.error("Failed to get change address for {}", walletId, e)
                    call.respond(HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Failed to get change address")))
                }
            }
        }

        /*
         * GET /explorer/tx/{txid}
         * Raw transaction detail straight from blockchain-service, no
         * wallet annotation.
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

        /*
         * GET /explorer/tx/{txid}/detail?walletId={walletId}
         * Same as /tx/{txid} but each input/output is annotated with isMine
         * relative to the supplied wallet - drives the YOURS badge in the
         * transaction-detail UI.
         */
        get("/tx/{txid}/detail") {
            val txid = call.parameters["txid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing txid"))
            val walletId = call.request.queryParameters["walletId"]

            try {
                // Pull the wallet's address set for isMine labeling. Network
                // is detected from the address prefixes (tb1/m/n/2 → testnet)
                // so callers don't have to pass it explicitly.
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

        // Fee estimates (proxied)

        /* GET /explorer/fees - current sat/vB recommendations. */
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
