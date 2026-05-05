package cz.majny.wallet.registry

import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/*
 * Tests for AddressDerivation - BIP-32/BIP-67 address generation from
 * output descriptors. Wrong derivation here means the wallet cannot
 * find its own funds, so this is one of the most safety-critical
 * components in the backend.
 *
 * Tests are fully offline; xpubs are generated deterministically from
 * fixed seeds, multisig vectors are constructed by varying cosigner seeds.
 */
class AddressDerivationTest {

    // Fixtures

    /*
     * Generates a deterministic xpub at the given network from a hex seed.
     * Used to fabricate test descriptors without committing real wallet keys.
     */
    private fun makeXpub(seedHex: String, network: BitcoinNetwork): String {
        val seed = hexToBytes(seedHex)
        return HDKeyDerivation.createMasterPrivateKey(seed)
            .serializePubB58(network)
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte()
    }

    // Singlesig P2WPKH

    @Test
    fun `singlesig P2WPKH descriptor produces 42-char tb1q testnet addresses`() {
        val xpub = makeXpub("000102030405060708090a0b0c0d0e0f", BitcoinNetwork.TESTNET)
        val descriptor = "wpkh([12345678/84h/1h/0h]$xpub/0/*)"

        val addrs = AddressDerivation.deriveAddresses(
            descriptor = descriptor,
            network = "testnet",
            chain = 0,
            fromIndex = 0,
            count = 5
        )
        assertEquals(5, addrs.size)
        addrs.forEachIndexed { i, derived ->
            assertEquals(i, derived.index, "Index field must reflect requested fromIndex offset")
            assertTrue(
                derived.address.startsWith("tb1q"),
                "Testnet P2WPKH address must start with tb1q, got: ${derived.address}"
            )
            // P2WPKH bech32: hrp(2) + sep(1) + version(1) + 32 char data + checksum(6) = 42 chars
            assertEquals(42, derived.address.length,
                "P2WPKH address length must be 42 chars, got: ${derived.address}")
        }
    }

    @Test
    fun `singlesig P2WPKH descriptor on mainnet produces bc1q addresses`() {
        val xpub = makeXpub("000102030405060708090a0b0c0d0e0f", BitcoinNetwork.MAINNET)
        val descriptor = "wpkh([12345678/84h/0h/0h]$xpub/0/*)"

        val addrs = AddressDerivation.deriveAddresses(
            descriptor, "mainnet", chain = 0, fromIndex = 0, count = 1
        )
        assertTrue(addrs[0].address.startsWith("bc1q"),
            "Mainnet P2WPKH must start with bc1q, got: ${addrs[0].address}")
    }

    @Test
    fun `singlesig derivation is idempotent and order-stable`() {
        val xpub = makeXpub("000102030405060708090a0b0c0d0e0f", BitcoinNetwork.TESTNET)
        val descriptor = "wpkh([12345678/84h/1h/0h]$xpub/0/*)"
        val first  = AddressDerivation.deriveAddresses(descriptor, "testnet", 0, 0, 10)
        val second = AddressDerivation.deriveAddresses(descriptor, "testnet", 0, 0, 10)
        assertEquals(first.map { it.address }, second.map { it.address },
            "Same descriptor and parameters must always yield same addresses")
    }

    @Test
    fun `singlesig different indices produce distinct addresses`() {
        val xpub = makeXpub("000102030405060708090a0b0c0d0e0f", BitcoinNetwork.TESTNET)
        val descriptor = "wpkh([12345678/84h/1h/0h]$xpub/0/*)"
        val addrs = AddressDerivation.deriveAddresses(descriptor, "testnet", 0, 0, 10)
        val unique = addrs.map { it.address }.toSet()
        assertEquals(addrs.size, unique.size,
            "Each address index must produce a unique address (no collisions)")
    }

    @Test
    fun `singlesig receive and change chains produce different addresses`() {
        val xpub = makeXpub("000102030405060708090a0b0c0d0e0f", BitcoinNetwork.TESTNET)
        val descriptor = "wpkh([12345678/84h/1h/0h]$xpub/0/*)"
        val receive = AddressDerivation.deriveAddresses(descriptor, "testnet", chain = 0, fromIndex = 0, count = 1)
        val change  = AddressDerivation.deriveAddresses(descriptor, "testnet", chain = 1, fromIndex = 0, count = 1)
        assertNotEquals(receive[0].address, change[0].address,
            "Receive (chain 0) and change (chain 1) at same index must differ")
    }

    // BIP-84 published test vector (offline reference verification)

    @Test
    fun `singlesig P2WPKH BIP-84 mainnet test vector matches published address`() {
        // BIP-84 official test vector (https://github.com/bitcoin/bips/blob/master/bip-0084.mediawiki):
        //   mnemonic: "abandon abandon abandon abandon abandon abandon
        //              abandon abandon abandon abandon abandon about"
        //   passphrase: empty (BIP-84 deliberately uses no passphrase, unlike
        //               the BIP-39 reference vector which uses "TREZOR")
        //   seed (BIP-39 PBKDF2 result with empty passphrase): the hex below
        //   m/84'/0'/0'/0/0 first receive address: bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu
        val seedHex = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
                      "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        val master = HDKeyDerivation.createMasterPrivateKey(hexToBytes(seedHex))
        val purpose    = HDKeyDerivation.deriveChildKey(master,    ChildNumber(84, true))   // 84'
        val coinType   = HDKeyDerivation.deriveChildKey(purpose,   ChildNumber(0,  true))   // 0'  (mainnet)
        val account    = HDKeyDerivation.deriveChildKey(coinType,  ChildNumber(0,  true))   // 0'
        val accountXpub = account.serializePubB58(BitcoinNetwork.MAINNET)

        val descriptor = "wpkh([73c5da0a/84h/0h/0h]$accountXpub/0/*)"
        val addrs = AddressDerivation.deriveAddresses(
            descriptor, "mainnet", chain = 0, fromIndex = 0, count = 1
        )
        assertEquals(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            addrs[0].address,
            "BIP-84 first receive address must match the published test vector"
        )
    }

    // Multisig P2WSH

    @Test
    fun `multisig sortedmulti descriptor produces 62-char tb1q P2WSH addresses`() {
        val xpubs = (0 until 3).map { i ->
            makeXpub("00".repeat(15) + "%02x".format(i + 1), BitcoinNetwork.TESTNET)
        }
        val descriptor = "wsh(sortedmulti(2," +
            xpubs.mapIndexed { i, xp -> "[%08x/48h/1h/0h/2h]$xp/0/*".format(i + 1) }
                .joinToString(",") +
            "))"

        val addrs = AddressDerivation.deriveAddresses(
            descriptor, "testnet", chain = 0, fromIndex = 0, count = 5
        )
        assertEquals(5, addrs.size)
        addrs.forEach { derived ->
            assertTrue(derived.address.startsWith("tb1q"),
                "Testnet P2WSH must start with tb1q, got: ${derived.address}")
            // P2WSH bech32: 32-byte program → 62-char address (vs 42 for 20-byte P2WPKH)
            assertEquals(62, derived.address.length,
                "P2WSH address length must be 62 chars, got: ${derived.address}")
        }
    }

    @Test
    fun `BIP-67 sortedmulti is invariant to cosigner order in descriptor`() {
        // Memory bug #1 in PSBT was caused by sorting at the wrong level.
        // For ADDRESS derivation, the analogous bug would be: rearranging
        // cosigners in the descriptor changes the derived address. With
        // sortedmulti(), it must NOT - both orderings must produce identical
        // addresses because pubkeys get sorted lexicographically before
        // building the witness script.
        val xp1 = makeXpub("00".repeat(15) + "01", BitcoinNetwork.TESTNET)
        val xp2 = makeXpub("00".repeat(15) + "02", BitcoinNetwork.TESTNET)
        val xp3 = makeXpub("00".repeat(15) + "03", BitcoinNetwork.TESTNET)

        val orderA = "wsh(sortedmulti(2," +
            "[01000000/48h/1h/0h/2h]$xp1/0/*," +
            "[02000000/48h/1h/0h/2h]$xp2/0/*," +
            "[03000000/48h/1h/0h/2h]$xp3/0/*))"
        // Same cosigners, different declaration order.
        val orderB = "wsh(sortedmulti(2," +
            "[03000000/48h/1h/0h/2h]$xp3/0/*," +
            "[01000000/48h/1h/0h/2h]$xp1/0/*," +
            "[02000000/48h/1h/0h/2h]$xp2/0/*))"

        val addrsA = AddressDerivation.deriveAddresses(orderA, "testnet", 0, 0, 5)
        val addrsB = AddressDerivation.deriveAddresses(orderB, "testnet", 0, 0, 5)
        assertEquals(addrsA.map { it.address }, addrsB.map { it.address },
            "sortedmulti must produce identical addresses regardless of cosigner declaration order")
    }

    @Test
    fun `multisig different indices produce distinct addresses`() {
        val xpubs = (0 until 3).map { i ->
            makeXpub("00".repeat(15) + "%02x".format(i + 1), BitcoinNetwork.TESTNET)
        }
        val descriptor = "wsh(sortedmulti(2," +
            xpubs.mapIndexed { i, xp -> "[%08x/48h/1h/0h/2h]$xp/0/*".format(i + 1) }
                .joinToString(",") +
            "))"

        val addrs = AddressDerivation.deriveAddresses(descriptor, "testnet", 0, 0, 10)
        val unique = addrs.map { it.address }.toSet()
        assertEquals(addrs.size, unique.size,
            "Each multisig address index must produce a unique P2WSH address")
    }

    @Test
    fun `multisig 3-of-5 descriptor parses M and N correctly and produces addresses`() {
        val xpubs = (0 until 5).map { i ->
            makeXpub("00".repeat(15) + "%02x".format(i + 1), BitcoinNetwork.TESTNET)
        }
        val descriptor = "wsh(sortedmulti(3," +
            xpubs.mapIndexed { i, xp -> "[%08x/48h/1h/0h/2h]$xp/0/*".format(i + 1) }
                .joinToString(",") +
            "))"
        val addrs = AddressDerivation.deriveAddresses(descriptor, "testnet", 0, 0, 1)
        assertEquals(62, addrs[0].address.length,
            "3-of-5 P2WSH address must still be standard 62-char bech32")
    }

    // Range and parameter handling

    @ParameterizedTest(name = "fromIndex={0} count={1} produces {1} addresses starting at {0}")
    @CsvSource("0, 5", "10, 3", "100, 1", "0, 20")
    fun `deriveAddresses respects fromIndex and count parameters`(fromIndex: Int, count: Int) {
        val xpub = makeXpub("000102030405060708090a0b0c0d0e0f", BitcoinNetwork.TESTNET)
        val descriptor = "wpkh([12345678/84h/1h/0h]$xpub/0/*)"
        val addrs = AddressDerivation.deriveAddresses(descriptor, "testnet", 0, fromIndex, count)
        assertEquals(count, addrs.size)
        assertEquals(fromIndex, addrs.first().index)
        assertEquals(fromIndex + count - 1, addrs.last().index)
    }

    @Test
    fun `default gap limit is 20 addresses per BIP-44 convention`() {
        assertEquals(20, AddressDerivation.DEFAULT_GAP_LIMIT,
            "BIP-44 gap limit determines how many addresses are pre-derived")
    }

    // Error handling and unsupported types

    @Test
    fun `P2TR descriptor throws UnsupportedOperationException with helpful message`() {
        // Regression guard: an earlier version silently fell back to P2WPKH
        // when BitcoinJ's P2TR encoder was not available, producing
        // legitimate-looking but wrong addresses.
        val xpub = makeXpub("000102030405060708090a0b0c0d0e0f", BitcoinNetwork.TESTNET)
        val descriptor = "tr([12345678/86h/1h/0h]$xpub/0/*)"
        val ex = assertFailsWith<UnsupportedOperationException> {
            AddressDerivation.deriveAddresses(descriptor, "testnet", 0, 0, 1)
        }
        assertTrue(
            ex.message?.contains("P2TR") == true,
            "Exception message must mention P2TR for clear diagnostics"
        )
    }

    @Test
    fun `singlesig descriptor without xpub throws IllegalArgumentException`() {
        // No xpub anywhere → cannot derive.
        val descriptor = "wpkh([12345678/84h/1h/0h]NOT_AN_XPUB/0/*)"
        assertFailsWith<IllegalArgumentException> {
            AddressDerivation.deriveAddresses(descriptor, "testnet", 0, 0, 1)
        }
    }

    @Test
    fun `multisig descriptor without xpubs throws IllegalArgumentException`() {
        val descriptor = "wsh(sortedmulti(2,NOT_XPUB_1,NOT_XPUB_2,NOT_XPUB_3))"
        assertFailsWith<IllegalArgumentException> {
            AddressDerivation.deriveAddresses(descriptor, "testnet", 0, 0, 1)
        }
    }
}
