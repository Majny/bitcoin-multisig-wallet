package cz.majny.wallet.explorer.service

import cz.majny.wallet.explorer.client.*
import cz.majny.wallet.explorer.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WalletExplorer")

/**
 * Agregační vrstva — spojuje wallet-registry (adresy) s blockchain-service (on-chain data).
 *
 * Řeší N+1 problém: frontend volá 1 request s walletId,
 * WalletExplorer interně dotáže všechny adresy peněženky.
 */
class WalletExplorer(
    private val registry: RegistryClient,
    private val blockchain: BlockchainClient
) {

    /**
     * Vrátí množinu všech adres dané peněženky.
     * Používá se pro isMine labeling v transaction detail.
     */
    suspend fun getWalletAddresses(walletId: String): Set<String> {
        return registry.getAddresses(walletId).map { it.address }.toSet()
    }

    // ========================================================================
    // BALANCE
    // ========================================================================

    /**
     * Spočítá celkový zůstatek peněženky — sečte UTXOs ze všech adres.
     * Vrací confirmed + unconfirmed zvlášť.
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

        // Paralelně získej AddressInfo pro všechny adresy.
        // AddressInfo obsahuje přímo confirmed/unconfirmed zůstatek — nepotřebujeme
        // načítat UTXOs jen kvůli výpočtu balance (to šetří ~30 API volání na mempool.space).
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

    // ========================================================================
    // TRANSACTIONS
    // ========================================================================

    /**
     * Získá historii transakcí pro celou peněženku.
     *
     * Algoritmus:
     * 1. Načte všechny adresy z registry
     * 2. Pro každou adresu stáhne transakce z blockchain-service
     * 3. Deduplikuje podle txid (jedna tx může být na více adresách)
     * 4. Klasifikuje jako SENT/RECEIVED:
     *    - Pokud alespoň jeden input patří mým adresám → SENT
     *    - Jinak → RECEIVED
     * 5. Spočítá net amount (kolik přišlo/odešlo)
     * 6. Seřadí chronologicky (unconfirmed first)
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

        // Krok 1: AddressInfo pro všechny adresy (výsledek je cachován 60 s —
        // pokud frontend zavolal /balance těsně předtím, tato volání jsou zdarma).
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

        // Krok 2: stáhni tx jen pro adresy s alespoň jednou transakcí
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

        // Klasifikuj a spočítej amount
        val classified = rawTxMap.values.map { tx ->
            classifyTransaction(tx, myAddressSet, currentHeight)
        }

        // Seřaď: unconfirmed first, pak podle času (nejnovější first)
        val sorted = classified.sortedWith(
            compareBy<WalletTransaction> { it.confirmed }
                .thenByDescending { it.blockTime ?: Long.MAX_VALUE }
        )

        // Paginate
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

    // ========================================================================
    // UTXOs (pro coin control)
    // ========================================================================

    /**
     * Vrátí všechny UTXOs peněženky obohacené o adresu a derivation path info.
     */
    suspend fun getWalletUtxos(walletId: String): WalletUtxosResponse = coroutineScope {
        log.info("Getting UTXOs for wallet: {}", walletId)

        val addresses = registry.getAddresses(walletId)

        val network = detectNetwork(addresses)

        // Pre-filter pomocí AddressInfo (cachováno 60 s — při přechodu z main menu zdarma).
        // Adresy s utxoCount == 0 přeskočíme, abychom zbytečně nevolali /utxo endpoint.
        val activeAddresses = addresses.map { addr ->
            async {
                try {
                    val info = blockchain.getAddressInfo(addr.address, network)
                    if (info.utxoCount > 0) addr else null
                } catch (e: Exception) {
                    addr  // při chybě fetchujeme UTXOs raději i tak
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

        // Seřaď: potvrzené first, pak podle value desc
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

    // ========================================================================
    // RECEIVE ADDRESS
    // ========================================================================

    /**
     * Najde první nepoužitou receive adresu.
     * Prochází adresy od indexu 0, hledá první bez aktivity.
     */
    suspend fun getNextReceiveAddress(walletId: String): ReceiveAddressResponse = coroutineScope {
        log.info("Finding next receive address for wallet: {}", walletId)

        val receiveAddresses = registry.getAddresses(walletId, "receive")
            .sortedBy { it.index }

        val network = detectNetwork(receiveAddresses)

        // Paralelně zjisti aktivitu
        val withActivity = receiveAddresses.map { addr ->
            async {
                val hasActivity = try {
                    blockchain.hasActivity(addr.address, network)
                } catch (e: Exception) {
                    false
                }
                addr to hasActivity
            }
        }.awaitAll()

        // Najdi první bez aktivity
        val unused = withActivity.firstOrNull { !it.second }

        if (unused != null) {
            ReceiveAddressResponse(
                walletId = walletId,
                address = unused.first.address,
                index = unused.first.index,
                isNew = true
            )
        } else {
            // Všechny adresy jsou použité — nevrátíme žádnou existující adresu (předejdeme reuse),
            // ale sdělíme frontendu, jaký index má dál odvozovat.
            val nextIndex = (receiveAddresses.maxOfOrNull { it.index } ?: -1) + 1
            ReceiveAddressResponse(
                walletId = walletId,
                address = "",
                index = nextIndex,
                isNew = false,
                needsDerivation = true
            )
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    /**
     * Detekuje síť z formátu první adresy.
     * tb1 / 2 / m / n  → testnet
     * bc1 / 1 / 3      → mainnet
     */
    private fun detectNetwork(addresses: List<cz.majny.wallet.explorer.client.WalletAddress>): String {
        val first = addresses.firstOrNull()?.address ?: return "mainnet"
        return if (first.startsWith("tb1") || first.startsWith("2") ||
                   first.startsWith("m")   || first.startsWith("n")) "testnet" else "mainnet"
    }

    /**
     * Klasifikuje transakci z pohledu peněženky.
     *
     * SENT: alespoň jeden input patří mým adresám
     * RECEIVED: žádný input nepatří mým adresám, ale alespoň jeden output ano
     *
     * Částka:
     * - SENT: suma mých inputů - suma mých outputů (change) = skutečně odesláno + fee
     * - RECEIVED: suma outputů na mé adresy
     */
    private fun classifyTransaction(
        tx: RawTransaction,
        myAddresses: Set<String>,
        currentBlockHeight: Int = 0
    ): WalletTransaction {
        // Input adresy
        val myInputSum = tx.vin.sumOf { input ->
            val addr = input.prevout?.scriptpubkey_address ?: ""
            if (addr in myAddresses) input.prevout?.value ?: 0L else 0L
        }

        // Output adresy
        val myOutputSum = tx.vout.sumOf { output ->
            if (output.scriptpubkey_address in myAddresses) output.value else 0L
        }

        val isSent = myInputSum > 0
        val type: String
        val amount: Long

        if (isSent) {
            type = "SENT"
            // Co jsem utratil = moje inputy - moje outputy (change)
            // To zahrnuje fee + skutečně odeslanou částku
            amount = myInputSum - myOutputSum
        } else {
            type = "RECEIVED"
            amount = myOutputSum
        }

        // Počet konfirmací: currentHeight - txBlockHeight + 1
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
