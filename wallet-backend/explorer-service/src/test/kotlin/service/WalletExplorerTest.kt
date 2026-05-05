package cz.majny.wallet.explorer.service

import cz.majny.wallet.explorer.client.BlockchainClient
import cz.majny.wallet.explorer.client.RawTransaction
import cz.majny.wallet.explorer.client.RegistryClient
import cz.majny.wallet.explorer.client.TxConfirmationStatus
import cz.majny.wallet.explorer.client.TxInput
import cz.majny.wallet.explorer.client.TxOutput
import cz.majny.wallet.explorer.client.TxPrevout
import io.ktor.client.HttpClient
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/*
 * Tests for WalletExplorer.classifyTransaction - converts a raw mempool/Esplora
 * transaction into the wallet-perspective WalletTransaction (SENT/RECEIVED,
 * net amount, confirmation count).
 *
 * Memory bug #9 ("confirmations always returned 1") is regression-guarded
 * here. The function is otherwise pure - it does not call upstream services -
 * so tests are constructed with synthetic RawTransaction fixtures and a
 * dummy WalletExplorer (the registry/blockchain clients are unused).
 */
class WalletExplorerTest {

    private val explorer = WalletExplorer(
        registry = RegistryClient("http://unused", HttpClient()),
        blockchain = BlockchainClient("http://unused", HttpClient())
    )

    private val myAddr = "tb1qmyaddress"
    private val theirAddr = "tb1qtheiraddress"

    private fun input(addr: String, value: Long): TxInput = TxInput(
        prevout = TxPrevout(scriptpubkey_address = addr, value = value)
    )

    private fun output(addr: String, value: Long): TxOutput = TxOutput(
        scriptpubkey_address = addr, value = value
    )

    private fun confirmedAt(blockHeight: Int) = TxConfirmationStatus(
        confirmed = true,
        block_height = blockHeight,
        block_time = 1_700_000_000L
    )

    private val unconfirmed = TxConfirmationStatus(confirmed = false)

    // SENT classification

    @Test
    fun `transaction with our input is classified as SENT`() {
        val tx = RawTransaction(
            txid = "abc",
            vin = listOf(input(myAddr, 100_000)),
            vout = listOf(output(theirAddr, 80_000)),
            fee = 20_000,
            status = unconfirmed
        )
        val classified = explorer.classifyTransaction(tx, setOf(myAddr))
        assertEquals("SENT", classified.type)
        // SENT amount = my inputs - my outputs (change). No change here.
        // Result includes both fee and what counterparty received (100k = 80k + 20k fee).
        assertEquals(100_000L, classified.amountSats)
    }

    @Test
    fun `SENT amount accounts for change output back to wallet`() {
        // Spend 100k input, send 60k to recipient, get 30k change back, 10k fee.
        val tx = RawTransaction(
            txid = "abc",
            vin = listOf(input(myAddr, 100_000)),
            vout = listOf(
                output(theirAddr, 60_000),
                output(myAddr, 30_000)  // change back to us
            ),
            fee = 10_000,
            status = unconfirmed
        )
        val classified = explorer.classifyTransaction(tx, setOf(myAddr))
        assertEquals("SENT", classified.type)
        // Our outflow = 100k - 30k change = 70k (60k recipient + 10k fee).
        assertEquals(70_000L, classified.amountSats)
    }

    // RECEIVED classification

    @Test
    fun `transaction with no inputs from us but output to us is RECEIVED`() {
        val tx = RawTransaction(
            txid = "abc",
            vin = listOf(input(theirAddr, 200_000)),
            vout = listOf(
                output(myAddr, 50_000),
                output(theirAddr, 149_000)  // their change
            ),
            fee = 1_000,
            status = unconfirmed
        )
        val classified = explorer.classifyTransaction(tx, setOf(myAddr))
        assertEquals("RECEIVED", classified.type)
        assertEquals(50_000L, classified.amountSats)
    }

    @Test
    fun `RECEIVED amount sums multiple outputs to our addresses`() {
        // Faucet payouts and consolidations can pay to several of our addresses
        // in a single transaction.
        val anotherMyAddr = "tb1qanotherofours"
        val tx = RawTransaction(
            txid = "abc",
            vin = listOf(input(theirAddr, 200_000)),
            vout = listOf(
                output(myAddr, 30_000),
                output(anotherMyAddr, 20_000),
                output(theirAddr, 149_000)
            ),
            fee = 1_000,
            status = unconfirmed
        )
        val classified = explorer.classifyTransaction(
            tx, setOf(myAddr, anotherMyAddr)
        )
        assertEquals("RECEIVED", classified.type)
        assertEquals(50_000L, classified.amountSats,
            "RECEIVED amount must sum every output paying any of our addresses")
    }

    // Confirmations

    @Test
    fun `unconfirmed transaction has zero confirmations regardless of block height`() {
        val tx = RawTransaction(
            txid = "abc",
            vin = listOf(input(myAddr, 100_000)),
            vout = listOf(output(theirAddr, 90_000)),
            fee = 10_000,
            status = unconfirmed
        )
        val classified = explorer.classifyTransaction(
            tx, setOf(myAddr), currentBlockHeight = 800_000
        )
        assertEquals(0, classified.confirmations)
    }

    @Test
    fun `confirmed transaction with current block height returns correct confirmation count`() {
        val tx = RawTransaction(
            txid = "abc",
            vin = listOf(input(myAddr, 100_000)),
            vout = listOf(output(theirAddr, 90_000)),
            fee = 10_000,
            status = confirmedAt(800_000)
        )
        // Block 800_000 with tip at 800_005 → 6 confirmations (inclusive of current).
        val classified = explorer.classifyTransaction(
            tx, setOf(myAddr), currentBlockHeight = 800_005
        )
        assertEquals(6, classified.confirmations)
    }

    @Test
    fun `confirmed transaction with currentBlockHeight 0 falls back to 1 confirmation`() {
        // Memory bug #9 regression guard: previously this function returned 1
        // unconditionally, masking unconfirmed transactions as confirmed.
        // Now: if tip height is unknown (0 = blockchain client failure),
        // we report the safe minimum of 1 for an already-confirmed tx.
        val tx = RawTransaction(
            txid = "abc",
            vin = listOf(input(myAddr, 100_000)),
            vout = listOf(output(theirAddr, 90_000)),
            fee = 10_000,
            status = confirmedAt(800_000)
        )
        val classified = explorer.classifyTransaction(
            tx, setOf(myAddr), currentBlockHeight = 0
        )
        assertEquals(1, classified.confirmations)
    }

    @Test
    fun `confirmations are clamped to at least 1 when tip height appears stale`() {
        // Edge case: cached tip height is briefly behind the tx's own block.
        // Result must still be a positive number (>=1) so the UI does not
        // display a confirmed tx as "unconfirmed".
        val tx = RawTransaction(
            txid = "abc",
            vin = listOf(input(myAddr, 100_000)),
            vout = listOf(output(theirAddr, 90_000)),
            fee = 10_000,
            status = confirmedAt(800_010)
        )
        val classified = explorer.classifyTransaction(
            tx, setOf(myAddr), currentBlockHeight = 800_000
        )
        assertEquals(1, classified.confirmations,
            "Confirmation count must never go below 1 for a confirmed tx")
    }

    // Metadata propagation

    @Test
    fun `inputCount, outputCount, fee and weight are propagated from raw transaction`() {
        val tx = RawTransaction(
            txid = "abc",
            vin = listOf(input(theirAddr, 200_000), input(theirAddr, 100_000)),
            vout = listOf(output(myAddr, 50_000), output(theirAddr, 249_000)),
            fee = 1_000,
            size = 250,
            weight = 1_000,
            status = unconfirmed
        )
        val classified = explorer.classifyTransaction(tx, setOf(myAddr))
        assertEquals(2, classified.inputCount)
        assertEquals(2, classified.outputCount)
        assertEquals(1_000L, classified.fee)
        assertEquals(250, classified.size)
        assertEquals(1_000, classified.weight)
        assertEquals("abc", classified.txid)
    }
}
