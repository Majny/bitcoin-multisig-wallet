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
    suspend fun getNextReceiveAddress(walletId: String): ReceiveAddressResponse {
        log.info("Finding next receive address for wallet: {}", walletId)
        return findNextUnusedAddress(walletId, type = "receive")
    }

    /**
     * Vrátí první nepoužitou change adresu (privacy invariant: nikdy nereusuje).
     * Používá psbt-service při sestavování PSBT, aby každá tx měla nový change output.
     */
    suspend fun getNextChangeAddress(walletId: String): ReceiveAddressResponse {
        log.info("Finding next change address for wallet: {}", walletId)
        return findNextUnusedAddress(walletId, type = "change")
    }

    /**
     * Sdílená logika: najde první adresu daného typu (receive/change) bez on-chain aktivity.
     *
     * Když jsou všechny existující adresy v DB použité, požádá wallet-registry o odvození
     * nové na dalším indexu a okamžitě ji vrátí. Nově odvozená adresa nemůže mít aktivitu
     * v běžném scénáři, protože dosud neexistovala — ale pro paranoidní případy
     * (adresář coincidentally dostal peníze před derivací) pokračujeme v derivaci dál
     * dokud nenajdeme čistou, s tvrdým limitem MAX_DERIVATION_ATTEMPTS.
     *
     * IMPORTANT: výjimky z blockchain kontroly propagujeme — spolknutí chyby by vedlo
     * k reuse adresy (privacy violation).
     */
    private suspend fun findNextUnusedAddress(
        walletId: String,
        type: String
    ): ReceiveAddressResponse = coroutineScope {
        val addresses = registry.getAddresses(walletId, type).sortedBy { it.index }
        val network = detectNetwork(addresses)

        // Paralelně zjisti aktivitu všech existujících adres.
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

        // Všechny existující adresy jsou použité — rozšíříme window přes registry.
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

        // Extrémně nepravděpodobné — vzdáváme to a signalizujeme chybu.
        error("Failed to find unused $type address for wallet $walletId after " +
                "$MAX_DERIVATION_ATTEMPTS derivation attempts starting at index $baseIndex")
    }

    companion object {
        /** Horní limit pro kolikrát budeme opakovaně derivovat, pokud každá nová má aktivitu. */
        private const val MAX_DERIVATION_ATTEMPTS = 5
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
