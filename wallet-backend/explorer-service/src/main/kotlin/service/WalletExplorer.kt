package cz.majny.wallet.explorer.service

import cz.majny.wallet.explorer.client.*
import cz.majny.wallet.explorer.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WalletExplorer")

/*
 * Aggregation layer that joins registry (addresses) with blockchain-service
 * (on-chain data). Hides the N+1 problem from callers: they pass one
 * walletId and we fan out per-address requests internally.
 */
class WalletExplorer(
    private val registry: RegistryClient,
    private val blockchain: BlockchainClient
) {

    /*
     * Wallet address set, used for isMine flags on transaction detail.
     */
    suspend fun getWalletAddresses(walletId: String): Set<String> {
        return registry.getAddresses(walletId).map { it.address }.toSet()
    }

    /*
     * Wallet balance (confirmed + unconfirmed). Reads address-level info from
     * blockchain-service in parallel and sums up balances. We deliberately use
     * the lighter /address/{addr} endpoint (returns balance + tx_count) instead
     * of pulling the full UTXO list per address, which would multiply API calls.
     */
    suspend fun getWalletBalance(walletId: String): WalletBalanceResponse = coroutineScope {
        log.info("Computing balance for wallet: {}", walletId)

        val addresses = registry.getAddresses(walletId)
        if (addresses.isEmpty()) {
            return@coroutineScope WalletBalanceResponse(
                walletId = walletId,
                confirmedSats = 0,
                unconfirmedSats = 0,
                totalSats = 0,
                utxoCount = 0,
                addressCount = 0
            )
        }

        val network = detectNetwork(addresses)

        // AddressInfo carries confirmed/unconfirmed balances directly; no need
        // to walk the UTXO set just to sum them up - saves ~30 mempool.space
        // calls on a typical wallet.
        val infoList = addresses.map { addr ->
            async {
                try {
                    blockchain.getAddressInfo(addr.address, network)
                } catch (e: Exception) {
                    log.warn("Failed to get address info for {}: {}", addr.address, e.message)
                    null
                }
            }
        }.awaitAll().filterNotNull()

        val confirmed = infoList.sumOf { it.confirmedBalance }
        val unconfirmed = infoList.sumOf { it.unconfirmedBalance }

        WalletBalanceResponse(
            walletId = walletId,
            confirmedSats = confirmed,
            unconfirmedSats = unconfirmed,
            totalSats = confirmed + unconfirmed,
            utxoCount = infoList.sumOf { it.utxoCount },
            addressCount = addresses.size
        )
    }

    // TRANSACTIONS

    /*
     * Wallet transaction history. Fans out per address, dedups by txid
     * (a tx touching multiple wallet addresses shows up only once),
     * classifies each as SENT (any input is ours) or RECEIVED (none are),
     * computes net amount and sorts with unconfirmed txs on top then by time.
     */
    suspend fun getWalletTransactions(
        walletId: String,
        limit: Int = 50,
        offset: Int = 0
    ): WalletTransactionsResponse = coroutineScope {
        log.info("Getting transactions for wallet: {} (limit={}, offset={})", walletId, limit, offset)

        val addresses = registry.getAddresses(walletId)
        val myAddressSet = addresses.map { it.address }.toSet()

        if (myAddressSet.isEmpty()) {
            return@coroutineScope WalletTransactionsResponse(
                walletId = walletId,
                transactions = emptyList(),
                total = 0,
                limit = limit,
                offset = offset
            )
        }

        val network = detectNetwork(addresses)

        // First pass: cheap AddressInfo call for every address. Results are
        // cached in blockchain-service for ~60s, so if the user just hit
        // /balance these calls are effectively free.
        val infoFetch = addresses.map { addr ->
            async {
                try { blockchain.getAddressInfo(addr.address, network) to addr }
                catch (e: Exception) {
                    log.warn("Failed to get address info for {}: {}", addr.address, e.message)
                    null
                }
            }
        }
        val tipFetch = async {
            try { blockchain.getTipHeight(network) } catch (e: Exception) {
                log.warn("Failed to get tip height: {}", e.message)
                0
            }
        }

        // Only fetch full tx lists for addresses that actually have activity
        // skips ~80% of a fresh wallet's addresses.
        val activeAddresses = infoFetch.awaitAll()
            .filterNotNull()
            .filter { (info, _) -> info.txCount > 0 }
            .map { it.second }

        val txFetch = activeAddresses.map { addr ->
            async {
                try {
                    blockchain.getAddressTransactions(addr.address, network)
                } catch (e: Exception) {
                    log.warn("Failed to get txs for {}: {}", addr.address, e.message)
                    emptyList()
                }
            }
        }

        val rawTxMap = mutableMapOf<String, RawTransaction>()
        txFetch.awaitAll().flatten().forEach { tx -> rawTxMap[tx.txid] = tx }
        val currentHeight = tipFetch.await()

        val classified = rawTxMap.values.map { tx ->
            classifyTransaction(tx, myAddressSet, currentHeight)
        }

        // Unconfirmed first (user cares about those most), then by block time desc.
        val sorted = classified.sortedWith(
            compareBy<WalletTransaction> { it.confirmed }
                .thenByDescending { it.blockTime ?: Long.MAX_VALUE }
        )

        val total = sorted.size
        val page = sorted.drop(offset).take(limit)

        WalletTransactionsResponse(
            walletId = walletId,
            transactions = page,
            total = total,
            limit = limit,
            offset = offset
        )
    }

    /*
     * Wallet UTXOs enriched with derivation info. Powers the coin-control
     * screen.
     */
    suspend fun getWalletUtxos(walletId: String): WalletUtxosResponse = coroutineScope {
        log.info("Getting UTXOs for wallet: {}", walletId)

        val addresses = registry.getAddresses(walletId)

        val network = detectNetwork(addresses)

        // Pre-filter via AddressInfo (already cached, likely free on this pass):
        // skip addresses with utxoCount == 0 so we don't waste a /utxo call.
        val activeAddresses = addresses.map { addr ->
            async {
                try {
                    val info = blockchain.getAddressInfo(addr.address, network)
                    if (info.utxoCount > 0) addr else null
                } catch (e: Exception) {
                    // Err on the side of fetching - a failed info call
                    // shouldn't hide real UTXOs.
                    addr
                }
            }
        }.awaitAll().filterNotNull()

        val enrichedUtxos = activeAddresses.map { addr ->
            async {
                try {
                    blockchain.getAddressUtxos(addr.address, network).map { utxo ->
                        WalletUtxo(
                            txid = utxo.txid,
                            vout = utxo.vout,
                            valueSats = utxo.value,
                            address = addr.address,
                            addressIndex = addr.index,
                            addressType = addr.type,
                            confirmed = utxo.status.confirmed,
                            blockHeight = utxo.status.block_height,
                            blockTime = utxo.status.block_time
                        )
                    }
                } catch (e: Exception) {
                    log.warn("Failed to get UTXOs for {}: {}", addr.address, e.message)
                    emptyList()
                }
            }
        }.awaitAll().flatten()

        // Confirmed first, then by value desc - matches the coin-control
        // display preference.
        val sorted = enrichedUtxos.sortedWith(
            compareByDescending<WalletUtxo> { it.confirmed }
                .thenByDescending { it.valueSats }
        )

        WalletUtxosResponse(
            walletId = walletId,
            utxos = sorted,
            totalSats = sorted.sumOf { it.valueSats },
            count = sorted.size
        )
    }

    /*
     * First receive address with no on-chain activity. Scans from index 0
     * and stops at the first clean one.
     */
    suspend fun getNextReceiveAddress(walletId: String): ReceiveAddressResponse {
        log.info("Finding next receive address for wallet: {}", walletId)
        return findNextUnusedAddress(walletId, type = "receive")
    }

    /*
     * First change address with no on-chain activity. Called by psbt-service
     * during PSBT assembly - privacy invariant: every outgoing tx burns a
     * fresh change output, never a reused one.
     */
    suspend fun getNextChangeAddress(walletId: String): ReceiveAddressResponse {
        log.info("Finding next change address for wallet: {}", walletId)
        return findNextUnusedAddress(walletId, type = "change")
    }

    /*
     * Shared logic for /receive-address and /change-address. If every
     * pre-derived address already has activity, asks registry to derive a new
     * one past the gap limit. A freshly derived address is normally clean -
     * but we check anyway to defend against the odd race where someone sent
     * to it before derivation (e.g. paper-trail recovery), and extend up to
     * MAX_DERIVATION_ATTEMPTS times before giving up.
     *
     * We deliberately don't catch exceptions from the activity check -
     * swallowing a blockchain error could let us return a dirty address and
     * quietly break the no-reuse invariant.
     */
    private suspend fun findNextUnusedAddress(
        walletId: String,
        type: String
    ): ReceiveAddressResponse = coroutineScope {
        val addresses = registry.getAddresses(walletId, type).sortedBy { it.index }
        val network = detectNetwork(addresses)

        // Activity check for all pre-derived addresses in parallel.
        val withActivity = addresses.map { addr ->
            async {
                val hasActivity = blockchain.hasActivity(addr.address, network)
                addr to hasActivity
            }
        }.awaitAll()

        val unused = withActivity.firstOrNull { !it.second }
        if (unused != null) {
            return@coroutineScope ReceiveAddressResponse(
                walletId = walletId,
                address = unused.first.address,
                index = unused.first.index,
                isNew = true
            )
        }

        // Every pre-derived address is used; extend the window via registry.
        val baseIndex = (addresses.maxOfOrNull { it.index } ?: -1) + 1
        log.info("All {} addresses up to index {} are used, deriving next for wallet {}",
            type, baseIndex - 1, walletId)

        for (offset in 0 until MAX_DERIVATION_ATTEMPTS) {
            val idx = baseIndex + offset
            val derived = registry.deriveAdditionalAddress(walletId, type, idx)
            val hasActivity = blockchain.hasActivity(derived.address, network)
            if (!hasActivity) {
                return@coroutineScope ReceiveAddressResponse(
                    walletId = walletId,
                    address = derived.address,
                    index = derived.index,
                    isNew = true
                )
            }
            log.warn("Freshly derived address already has activity (rare): wallet={} {}/{} addr={}",
                walletId, type, idx, derived.address)
        }

        // Essentially never hit in practice - give up rather than loop forever.
        error("Failed to find unused $type address for wallet $walletId after " +
                "$MAX_DERIVATION_ATTEMPTS derivation attempts starting at index $baseIndex")
    }

    companion object {
        /* How many freshly-derived addresses we're willing to check before
         * giving up. 5 is a wide safety margin for what should normally take 1. */
        private const val MAX_DERIVATION_ATTEMPTS = 5
    }

    /*
     * Detects network from the first address prefix. tb1 / 2 / m / n →
     * testnet, everything else → mainnet. Used so routes don't need to
     * pass the network explicitly.
     */
    private fun detectNetwork(addresses: List<cz.majny.wallet.explorer.client.WalletAddress>): String {
        val first = addresses.firstOrNull()?.address ?: return "mainnet"
        return if (first.startsWith("tb1") || first.startsWith("2") ||
                   first.startsWith("m")   || first.startsWith("n")) "testnet" else "mainnet"
    }

    /*
     * Classifies a raw transaction from the wallet's perspective.
     *   SENT     - at least one input belongs to us
     *   RECEIVED - no inputs are ours, at least one output is
     * Amount semantics:
     *   SENT     - sum(my inputs) − sum(my outputs, i.e. change) = what
     *              actually left + fee
     *   RECEIVED - sum(outputs paying us)
     */
    internal fun classifyTransaction(
        tx: RawTransaction,
        myAddresses: Set<String>,
        currentBlockHeight: Int = 0
    ): WalletTransaction {
        // Input addresses
        val myInputSum = tx.vin.sumOf { input ->
            val addr = input.prevout?.scriptpubkey_address ?: ""
            if (addr in myAddresses) input.prevout?.value ?: 0L else 0L
        }

        // Output addresses
        val myOutputSum = tx.vout.sumOf { output ->
            if (output.scriptpubkey_address in myAddresses) output.value else 0L
        }

        val isSent = myInputSum > 0
        val type: String
        val amount: Long

        if (isSent) {
            type = "SENT"
            // What I spent = my inputs - my outputs (the change) - includes
            // the fee plus the actual amount the counterparty received.
            amount = myInputSum - myOutputSum
        } else {
            type = "RECEIVED"
            amount = myOutputSum
        }

        // Confirmations: currentHeight - txBlockHeight + 1.
        val confirmations = if (tx.status.confirmed && tx.status.block_height != null) {
            if (currentBlockHeight > 0) maxOf(currentBlockHeight - tx.status.block_height + 1, 1)
            else 1
        } else {
            0
        }

        return WalletTransaction(
            txid = tx.txid,
            type = type,
            amountSats = amount,
            fee = tx.fee,
            confirmed = tx.status.confirmed,
            blockHeight = tx.status.block_height,
            blockTime = tx.status.block_time,
            confirmations = confirmations,
            inputCount = tx.vin.size,
            outputCount = tx.vout.size,
            size = tx.size,
            weight = tx.weight
        )
    }
}
