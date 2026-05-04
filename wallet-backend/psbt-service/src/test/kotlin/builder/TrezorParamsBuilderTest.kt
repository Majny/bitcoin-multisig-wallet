package cz.majny.wallet.psbt.builder

import cz.majny.wallet.psbt.api.CosignerDto
import cz.majny.wallet.psbt.api.TxOutput
import cz.majny.wallet.psbt.api.WalletDetailDto
import cz.majny.wallet.psbt.builder.TestFixtures.TESTNET_ADDR
import cz.majny.wallet.psbt.builder.TestFixtures.makeTestnetXpub
import cz.majny.wallet.psbt.builder.TestFixtures.nCosigners
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Tests for derivation path parsing in TrezorParamsBuilder.
 *
 * Trezor Connect requires BIP-32 derivation paths as a uint32 array where
 * hardened indices have bit 0x80000000 set. Both helpers below feed into
 * `address_n` (and `multisig.pubkeys[].address_n`) on every signTransaction
 * call, so a regression here would silently route signatures to the wrong
 * key and produce an invalid transaction.
 *
 * Hardened reference values:
 *   84' = 0x80000054 = 2147483732
 *   48' = 0x80000030 = 2147483696
 *   1'  = 0x80000001 = 2147483649
 *   0'  = 0x80000000 = 2147483648
 *   2'  = 0x80000002 = 2147483650
 */
class TrezorParamsBuilderTest {

    @Test
    fun `parseOriginPathToUint32 handles BIP-84 singlesig path`() {
        // m/84'/1'/0' on testnet
        val expected = listOf(
            0x80000054L,  // 84'
            0x80000001L,  // 1'
            0x80000000L   // 0'
        )
        assertEquals(expected, TrezorParamsBuilder.parseOriginPathToUint32("84h/1h/0h"))
    }

    @Test
    fun `parseOriginPathToUint32 handles BIP-48 multisig path`() {
        // m/48'/1'/0'/2' on testnet
        val expected = listOf(
            0x80000030L,  // 48'
            0x80000001L,  // 1'
            0x80000000L,  // 0'
            0x80000002L   // 2' (script_type = P2WSH)
        )
        assertEquals(expected, TrezorParamsBuilder.parseOriginPathToUint32("48h/1h/0h/2h"))
    }

    @Test
    fun `parseOriginPathToUint32 accepts apostrophe notation as alias for h`() {
        // Both BIP-32 syntaxes for hardened: 84'/1'/0'  ==  84h/1h/0h
        val expected = listOf(0x80000054L, 0x80000001L, 0x80000000L)
        assertEquals(expected, TrezorParamsBuilder.parseOriginPathToUint32("84'/1'/0'"))
    }

    @Test
    fun `parseOriginPathToUint32 leaves unhardened indices untouched`() {
        // Tail of the derivation tree: chain (0/1) and address index.
        // These must NOT have the hardened bit set.
        val expected = listOf(0L, 5L)
        assertEquals(expected, TrezorParamsBuilder.parseOriginPathToUint32("0/5"))
    }

    @Test
    fun `parseOriginPathToUint32 trims leading slash`() {
        // Origin paths in descriptors sometimes appear as "/84h/1h/0h"
        val expected = listOf(0x80000054L, 0x80000001L, 0x80000000L)
        assertEquals(expected, TrezorParamsBuilder.parseOriginPathToUint32("/84h/1h/0h"))
    }

    @Test
    fun `parseDescriptorOrigin extracts BIP-48 path from sortedmulti descriptor`() {
        // Realistic 2-of-3 P2WSH testnet descriptor with first cosigner's origin.
        val desc = "wsh(sortedmulti(2,[abcd1234/48h/1h/0h/2h]" +
                   "tpubDExample.../0/*,[ffeeddcc/48h/1h/0h/2h]tpubD.../0/*))"
        val expected = listOf(0x80000030L, 0x80000001L, 0x80000000L, 0x80000002L)
        assertEquals(expected, TrezorParamsBuilder.parseDescriptorOrigin(desc))
    }

    @Test
    fun `parseDescriptorOrigin returns null when descriptor has no origin brackets`() {
        // Plain descriptor without [fingerprint/path] origin info.
        val desc = "wpkh(tpubDExample.../0/*)"
        assertNull(TrezorParamsBuilder.parseDescriptorOrigin(desc))
    }

    @Test
    fun `parseDescriptorOrigin returns null on malformed brackets`() {
        // Closing bracket before opening - parser must not throw.
        val desc = "wpkh(]bad[xpub.../0/*)"
        assertNull(TrezorParamsBuilder.parseDescriptorOrigin(desc))
    }

    // xpub → HDNodeType conversion (Trezor Connect input format)

    @Test
    fun `xpubToHDNode decodes BIP-32 test vector 1 master xpub`() {
        // BIP-32 Test Vector 1, master m derived from seed 000102030405060708090a0b0c0d0e0f.
        // Canonical reference values published in the BIP-32 specification.
        val masterXpub = "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8N" +
                         "qtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usU" +
                         "DFdp6W1EGMcet8"

        val node = TrezorParamsBuilder.xpubToHDNode(masterXpub, BitcoinNetwork.MAINNET)

        // Master node has no parent → depth=0, parent fingerprint=0, child number=0.
        assertEquals(0, node.depth)
        assertEquals(0L, node.fingerprint)
        assertEquals(0L, node.child_num)

        // Chain code and compressed public key are well-known constants for this xpub.
        assertEquals(
            "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508",
            node.chain_code
        )
        assertEquals(
            "0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2",
            node.public_key
        )
    }

    @Test
    fun `xpubToHDNode produces 33-byte compressed public key and 32-byte chain code`() {
        // Structural sanity check that holds for any valid xpub regardless of derivation.
        val masterXpub = "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8N" +
                         "qtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usU" +
                         "DFdp6W1EGMcet8"
        val node = TrezorParamsBuilder.xpubToHDNode(masterXpub, BitcoinNetwork.MAINNET)
        // Compressed public key = 33 bytes = 66 hex chars (prefix 0x02 or 0x03 + 32-byte X coord)
        assertEquals(66, node.public_key.length)
        assertTrue(
            node.public_key.startsWith("02") || node.public_key.startsWith("03"),
            "Compressed pubkey must have 0x02 or 0x03 prefix, got: ${node.public_key.take(2)}"
        )
        // Chain code is always 32 bytes = 64 hex chars
        assertEquals(64, node.chain_code.length)
    }

    // build() entry point - composes everything above into a complete TrezorConnectParams

    @Test
    fun `build for singlesig wallet produces expected address_n and no multisig object`() {
        val cosigner = CosignerDto(
            idx = 0,
            fingerprint = "12345678",
            originPath = "84h/1h/0h",
            xpubRoot = makeTestnetXpub("000102030405060708090a0b0c0d0e0f")
        )
        val wallet = WalletDetailDto(
            walletId = "test-singlesig-tcp",
            network = "testnet",
            type = "SINGLE_SIG",
            scriptType = "WPKH",
            receiveDescriptor = "wpkh([12345678/84h/1h/0h]${cosigner.xpubRoot}/0/*)",
            changeDescriptor = "wpkh([12345678/84h/1h/0h]${cosigner.xpubRoot}/1/*)",
            cosigners = listOf(cosigner)
        )
        val utxo = SelectedUtxo(
            txid = "00".repeat(32),
            vout = 0,
            value = 200_000L,
            scriptPubKey = "0014" + "ab".repeat(20),
            addressIndex = 5,
            addressType = "receive",
            address = TESTNET_ADDR
        )
        val output = TxOutput(address = TESTNET_ADDR, amountSats = 100_000L)

        val params = TrezorParamsBuilder.build(
            wallet = wallet,
            utxos = listOf(utxo),
            outputs = listOf(output),
            changeAddress = TESTNET_ADDR,
            changeAmount = 50_000L,
            changeIndex = 7
        )
        assertNotNull(params)
        assertEquals("Testnet", params.coin)
        assertEquals(1, params.inputs.size)
        assertEquals(2, params.outputs.size)  // recipient + change

        val input = params.inputs[0]
        // BIP-84 m/84'/1'/0'/0/5 → uint32 array
        assertEquals(
            listOf(0x80000054L, 0x80000001L, 0x80000000L, 0L, 5L),
            input.address_n
        )
        assertEquals(utxo.txid, input.prev_hash)
        assertEquals(0, input.prev_index)
        assertEquals("200000", input.amount)
        assertEquals("SPENDWITNESS", input.script_type)
        assertNull(input.multisig, "Singlesig input must not carry a multisig object")

        // Recipient: external address, no derivation.
        val recipient = params.outputs[0]
        assertEquals(TESTNET_ADDR, recipient.address)
        assertNull(recipient.address_n)
        assertEquals("PAYTOADDRESS", recipient.script_type)

        // Change: identified by derivation path, not address; PAYTOWITNESS so Trezor
        // labels it as internal on the device display.
        val change = params.outputs[1]
        assertNull(change.address)
        assertEquals(
            listOf(0x80000054L, 0x80000001L, 0x80000000L, 1L, 7L),
            change.address_n
        )
        assertEquals("PAYTOWITNESS", change.script_type)
    }

    @Test
    fun `build for multisig wallet places signer path on input and BIP-67 sorts pubkeys`() {
        val cosigners = nCosigners(3)
        val descriptor = "wsh(sortedmulti(2," +
            cosigners.joinToString(",") {
                "[${it.fingerprint}/48h/1h/0h/2h]${it.xpubRoot}/0/*"
            } + "))"
        val wallet = WalletDetailDto(
            walletId = "test-multisig-tcp",
            network = "testnet",
            type = "MULTI_SIG",
            scriptType = "WSH",
            m = 2,
            n = 3,
            receiveDescriptor = descriptor,
            changeDescriptor = descriptor.replace("/0/*", "/1/*"),
            cosigners = cosigners
        )
        val utxo = SelectedUtxo(
            txid = "00".repeat(32),
            vout = 0,
            value = 1_000_000L,
            scriptPubKey = "0020" + "ab".repeat(32),
            addressIndex = 5,
            addressType = "receive",
            address = TESTNET_ADDR
        )
        val output = TxOutput(address = TESTNET_ADDR, amountSats = 100_000L)

        // Sign as cosigner 1 (middle of the three) - tests that signer index
        // selects the correct origin path independently of BIP-67 sort order.
        val params = TrezorParamsBuilder.build(
            wallet = wallet,
            utxos = listOf(utxo),
            outputs = listOf(output),
            changeAddress = TESTNET_ADDR,
            changeAmount = 800_000L,
            changeIndex = 7,
            signerCosignerIndex = 1
        )
        assertNotNull(params)
        val input = params.inputs[0]

        // Signer's address_n: BIP-48 m/48'/1'/0'/2'/0/5 (cosigner 1's path)
        assertEquals(
            listOf(0x80000030L, 0x80000001L, 0x80000000L, 0x80000002L, 0L, 5L),
            input.address_n
        )

        // Multisig metadata
        val multisig = input.multisig
        assertNotNull(multisig, "Multisig input must contain a multisig object")
        assertEquals(2, multisig.m)
        assertEquals(3, multisig.pubkeys.size, "All N=3 pubkeys must be present")
        assertEquals(3, multisig.signatures.size, "Signatures slot per cosigner")
        multisig.signatures.forEach {
            assertEquals("", it, "All signature slots must start empty")
        }

        // BIP-67 sort happens at the DERIVED child level, not the account-level
        // public_key that HDNodeDto carries. To verify, we must independently
        // derive each cosigner's child pubkey at [chain=0, index=5] (the input's
        // chain/index), sort cosigners by that child pubkey, and confirm the
        // multisig.pubkeys order matches.
        val expectedAccountPubkeys = cosigners
            .map { cos ->
                val accountKey = DeterministicKey.deserializeB58(cos.xpubRoot, BitcoinNetwork.TESTNET)
                val accountPubkeyHex = accountKey.pubKeyPoint.getEncoded(true)
                    .joinToString("") { "%02x".format(it) }
                val chainKey = HDKeyDerivation.deriveChildKey(accountKey, 0)
                val childKey = HDKeyDerivation.deriveChildKey(chainKey, 5)
                val childPubkeyHex = childKey.pubKey.joinToString("") { "%02x".format(it) }
                Triple(cos, accountPubkeyHex, childPubkeyHex)
            }
            .sortedBy { it.third }   // BIP-67: sort by derived child pubkey
            .map { it.second }       // expected order of HDNodeDto.public_key

        val actualAccountPubkeys = multisig.pubkeys.map { it.node.public_key }
        assertEquals(
            expectedAccountPubkeys,
            actualAccountPubkeys,
            "Cosigner HDNodes must be ordered by their derived child pubkey at [0, 5] (BIP-67)"
        )

        // Each pubkey's address_n is just [chain, index] relative to its account xpub.
        multisig.pubkeys.forEach { pk ->
            assertEquals(listOf(0L, 5L), pk.address_n,
                "Each cosigner pubkey must be derived at [chain=0, index=5]")
        }

        // Change output: own derivation + multisig metadata for all cosigners.
        val change = params.outputs[1]
        assertNull(change.address)
        assertEquals(
            listOf(0x80000030L, 0x80000001L, 0x80000000L, 0x80000002L, 1L, 7L),
            change.address_n
        )
        val changeMs = change.multisig
        assertNotNull(changeMs, "Multisig change output must carry its multisig object")
        assertEquals(3, changeMs.pubkeys.size)
    }
}
