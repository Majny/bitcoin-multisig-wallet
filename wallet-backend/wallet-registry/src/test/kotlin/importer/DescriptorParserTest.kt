package cz.majny.wallet.registry.importer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/*
 * Tests for DescriptorParser - parsing of BIP-380/383 output descriptors
 * into ParsedDescriptor.
 *
 * Wallet import is the entry point for every multisig wallet in the
 * application; a parser bug here would either reject valid descriptors
 * exported from Sparrow Wallet or silently accept malformed ones and
 * produce wrong wallet metadata. Tests are pure - the parser does not
 * touch BitcoinJ, the database, or the network.
 */
class DescriptorParserTest {

    // Synthetic xpub strings (don't need to be cryptographically valid - the
    // parser only checks that they match the [xt]pub[base58]{79,120} regex).
    // Three lexicographically distinct base58 prefixes for BIP-67 sort tests.
    private val xpub1 = "tpub1" + "1".repeat(110)
    private val xpub2 = "tpub2" + "2".repeat(110)
    private val xpub3 = "tpub3" + "3".repeat(110)

    // Single-sig

    @Test
    fun `parse extracts cosigner from BIP-84 wpkh descriptor`() {
        val desc = "wpkh([12345678/84h/1h/0h]$xpub1/0/*)"
        val parsed = DescriptorParser.parse(desc, network = "testnet")

        assertEquals("SINGLE_SIG", parsed.type)
        assertEquals("WPKH", parsed.scriptType)
        assertEquals(0, parsed.accountIndex)
        assertEquals(1, parsed.cosigners.size)

        val cos = parsed.cosigners[0]
        assertEquals("12345678", cos.fingerprint)
        assertEquals("84h/1h/0h", cos.originPath)
        assertEquals(xpub1, cos.xpub)
    }

    @Test
    fun `parse normalizes uppercase fingerprint hex to lowercase`() {
        // Sparrow exports fingerprint in uppercase; we normalize so equality
        // checks downstream do not depend on case.
        val desc = "wpkh([ABCDEF12/84h/1h/0h]$xpub1/0/*)"
        val parsed = DescriptorParser.parse(desc, network = "testnet")
        assertEquals("abcdef12", parsed.cosigners[0].fingerprint)
    }

    @Test
    fun `parse extracts non-zero account index from origin path`() {
        // BIP-48 path with account=2: 48'/1'/2'/2'
        val desc = "wpkh([12345678/48h/1h/2h/2h]$xpub1/0/*)"
        val parsed = DescriptorParser.parse(desc, network = "testnet")
        assertEquals(2, parsed.accountIndex)
    }

    // Multisig

    @Test
    fun `parse extracts M, N, and cosigners from sortedmulti descriptor`() {
        val desc = "wsh(sortedmulti(2," +
            "[11111111/48h/1h/0h/2h]$xpub1/0/*," +
            "[22222222/48h/1h/0h/2h]$xpub2/0/*," +
            "[33333333/48h/1h/0h/2h]$xpub3/0/*))"
        val parsed = DescriptorParser.parse(desc, network = "testnet")

        assertEquals("MULTI_SIG", parsed.type)
        assertEquals("WSH", parsed.scriptType)
        assertEquals(2, parsed.m)
        assertEquals(3, parsed.n)
        assertEquals(3, parsed.cosigners.size)
        // Default label includes the threshold for clarity.
        assertEquals("2of3 Multisig", parsed.label)
    }

    @Test
    fun `parse keeps cosigners in BIP-67 sort order for sortedmulti`() {
        // Declare cosigners in REVERSE alphabetical order; parser must reorder
        // them lexicographically by xpub.
        val desc = "wsh(sortedmulti(2," +
            "[33333333/48h/1h/0h/2h]$xpub3/0/*," +
            "[22222222/48h/1h/0h/2h]$xpub2/0/*," +
            "[11111111/48h/1h/0h/2h]$xpub1/0/*))"
        val parsed = DescriptorParser.parse(desc, network = "testnet")
        // After sort: tpub1 < tpub2 < tpub3.
        assertEquals("11111111", parsed.cosigners[0].fingerprint)
        assertEquals("22222222", parsed.cosigners[1].fingerprint)
        assertEquals("33333333", parsed.cosigners[2].fingerprint)
        assertEquals(0, parsed.cosigners[0].idx)
        assertEquals(1, parsed.cosigners[1].idx)
        assertEquals(2, parsed.cosigners[2].idx)
    }

    @Test
    fun `parse rejects multi descriptor with invalid M greater than N`() {
        val desc = "wsh(sortedmulti(5," +
            "[11111111/48h/1h/0h/2h]$xpub1/0/*," +
            "[22222222/48h/1h/0h/2h]$xpub2/0/*," +
            "[33333333/48h/1h/0h/2h]$xpub3/0/*))"
        assertFailsWith<DescriptorParseException> {
            DescriptorParser.parse(desc, network = "testnet")
        }
    }

    @Test
    fun `parse rejects multi descriptor with M equal to zero`() {
        val desc = "wsh(sortedmulti(0," +
            "[11111111/48h/1h/0h/2h]$xpub1/0/*," +
            "[22222222/48h/1h/0h/2h]$xpub2/0/*))"
        assertFailsWith<DescriptorParseException> {
            DescriptorParser.parse(desc, network = "testnet")
        }
    }

    // Checksum and multi-line input

    @Test
    fun `parse strips trailing checksum suffix`() {
        // Sparrow-exported descriptors end with #xxxxxxxx (BIP-380 checksum);
        // the parser ignores the checksum and operates on the bare descriptor.
        val desc = "wpkh([12345678/84h/1h/0h]$xpub1/0/*)#abcdefgh"
        val parsed = DescriptorParser.parse(desc, network = "testnet")
        assertEquals("SINGLE_SIG", parsed.type)
        assertTrue(!parsed.receiveDescriptor.contains("#"),
            "Stored receive descriptor must not contain the checksum suffix")
    }

    @Test
    fun `parse accepts two-line input as separate receive and change descriptors`() {
        val recv = "wpkh([12345678/84h/1h/0h]$xpub1/0/*)"
        val chng = "wpkh([12345678/84h/1h/0h]$xpub1/1/*)"
        val parsed = DescriptorParser.parse("$recv\n$chng", network = "testnet")
        assertEquals(recv, parsed.receiveDescriptor)
        assertEquals(chng, parsed.changeDescriptor)
    }

    @Test
    fun `parse derives change descriptor by flipping zero to one in single-line input`() {
        // Memory bug #16 (PSBT change reuse) was rooted in change derivation
        // mistakes; parser must always produce a distinct change descriptor.
        val recv = "wpkh([12345678/84h/1h/0h]$xpub1/0/*)"
        val parsed = DescriptorParser.parse(recv, network = "testnet")
        assertNotEquals(parsed.receiveDescriptor, parsed.changeDescriptor)
        assertTrue(parsed.changeDescriptor.contains("/1/*"),
            "Change descriptor must use the /1/* trailing path")
    }

    @Test
    fun `parse expands BIP-389 multipath descriptor with 0 and 1 placeholder`() {
        // Modern wallets export <0;1> placeholder so a single descriptor
        // covers both receive and change (BIP-389).
        val multipath = "wpkh([12345678/84h/1h/0h]$xpub1/<0;1>/*)"
        val parsed = DescriptorParser.parse(multipath, network = "testnet")
        assertTrue(parsed.receiveDescriptor.contains("/0/*"),
            "Receive must use chain 0 placeholder substitution")
        assertTrue(parsed.changeDescriptor.contains("/1/*"),
            "Change must use chain 1 placeholder substitution")
    }

    // Wallet ID determinism

    @Test
    fun `walletId is deterministic across repeated parses of the same descriptor`() {
        val desc = "wpkh([12345678/84h/1h/0h]$xpub1/0/*)"
        val a = DescriptorParser.parse(desc, network = "testnet")
        val b = DescriptorParser.parse(desc, network = "testnet")
        assertEquals(a.walletId, b.walletId,
            "Same descriptor + network must always produce the same walletId")
        assertTrue(a.walletId.startsWith("w-"),
            "WalletId convention prefix must be 'w-'")
    }

    @Test
    fun `walletId differs between mainnet and testnet for the same descriptor`() {
        val desc = "wpkh([12345678/84h/1h/0h]$xpub1/0/*)"
        val mainnet = DescriptorParser.parse(desc, network = "mainnet").walletId
        val testnet = DescriptorParser.parse(desc, network = "testnet").walletId
        assertNotEquals(mainnet, testnet,
            "Cross-network walletId collision would route mainnet operations to testnet wallet")
    }

    // Cosigner ID

    @Test
    fun `ParsedCosigner cosignerId encodes fingerprint and origin path deterministically`() {
        val cos = ParsedCosigner(
            fingerprint = "12345678",
            originPath = "48'/1'/0'/2'",
            xpub = xpub1
        )
        // Apostrophes converted to "h", slashes to "_" so id is a safe identifier.
        assertEquals("cs-12345678-48h_1h_0h_2h", cos.cosignerId)
    }
}
