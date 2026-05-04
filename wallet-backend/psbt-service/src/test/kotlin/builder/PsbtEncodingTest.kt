package cz.majny.wallet.psbt.builder

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Tests for PsbtEncoding - low-level Bitcoin and PSBT (BIP-174) byte
 * encoding utilities. These functions sit underneath every PSBT we
 * generate, so a regression here corrupts every transaction.
 */
class PsbtEncodingTest {

    // CompactSize / VarInt encoding (BIP-174 wire format)

    @Test
    fun `writeVarInt encodes small values as single byte`() {
        // Values < 0xFD use 1 byte verbatim.
        assertContentEquals(byteArrayOf(0x00), PsbtEncoding.writeVarInt(0).toByteArray())
        assertContentEquals(byteArrayOf(0x01), PsbtEncoding.writeVarInt(1).toByteArray())
        assertContentEquals(byteArrayOf(0xFC.toByte()), PsbtEncoding.writeVarInt(0xFC).toByteArray())
    }

    @Test
    fun `writeVarInt encodes 0xFD-0xFFFF as 0xFD plus 2 LE bytes`() {
        // 0xFD prefix + uint16 little-endian.
        assertContentEquals(
            byteArrayOf(0xFD.toByte(), 0xFD.toByte(), 0x00),
            PsbtEncoding.writeVarInt(0xFD).toByteArray()
        )
        assertContentEquals(
            byteArrayOf(0xFD.toByte(), 0x34, 0x12),
            PsbtEncoding.writeVarInt(0x1234).toByteArray()
        )
        assertContentEquals(
            byteArrayOf(0xFD.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            PsbtEncoding.writeVarInt(0xFFFF).toByteArray()
        )
    }

    @Test
    fun `writeVarInt encodes values above 0xFFFF as 0xFE plus 4 LE bytes`() {
        // 0xFE prefix + uint32 little-endian.
        assertContentEquals(
            byteArrayOf(0xFE.toByte(), 0x00, 0x00, 0x01, 0x00),
            PsbtEncoding.writeVarInt(0x10000).toByteArray()
        )
        assertContentEquals(
            byteArrayOf(0xFE.toByte(), 0x78, 0x56, 0x34, 0x12),
            PsbtEncoding.writeVarInt(0x12345678).toByteArray()
        )
    }

    // Little-endian integer encoding

    @Test
    fun `intToLE encodes 4-byte values in correct byte order`() {
        // 256 = 0x00000100, little-endian = 00 01 00 00
        assertContentEquals(
            byteArrayOf(0x00, 0x01, 0x00, 0x00),
            PsbtEncoding.intToLE(256, 4).toByteArray()
        )
        // 0x01020304 little-endian = 04 03 02 01
        assertContentEquals(
            byteArrayOf(0x04, 0x03, 0x02, 0x01),
            PsbtEncoding.intToLE(0x01020304, 4).toByteArray()
        )
    }

    @Test
    fun `intToLE handles 2-byte width`() {
        // 0x1234 in 2 bytes LE = 34 12
        assertContentEquals(
            byteArrayOf(0x34, 0x12),
            PsbtEncoding.intToLE(0x1234, 2).toByteArray()
        )
    }

    @Test
    fun `longToLE encodes 8 bytes in little-endian order`() {
        // 1 BTC = 100_000_000 sats = 0x05F5E100
        // 8-byte LE = 00 E1 F5 05 00 00 00 00
        assertContentEquals(
            byteArrayOf(0x00, 0xE1.toByte(), 0xF5.toByte(), 0x05, 0x00, 0x00, 0x00, 0x00),
            PsbtEncoding.longToLE(100_000_000L).toByteArray()
        )
    }

    // Hex roundtrip

    @Test
    fun `hexToBytes and bytesToHex roundtrip preserves values including high bytes`() {
        // Use a value with high bytes (>=0x80) - guards against sign extension regression.
        val hex = "00ff8001abcdef02703abc"
        val bytes = PsbtEncoding.hexToBytes(hex)
        assertEquals(hex, PsbtEncoding.bytesToHex(bytes))
    }

    @Test
    fun `bytesToHex outputs lowercase hex`() {
        // The PSBT spec is case-insensitive but our broadcast hex must match
        // mempool.space's expected lowercase form.
        val hex = PsbtEncoding.bytesToHex(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()))
        assertEquals("abcdef", hex)
    }

    @Test
    fun `hexToBytes rejects odd-length input`() {
        assertFailsWith<IllegalArgumentException> { PsbtEncoding.hexToBytes("abc") }
    }

    // Multisig witness script construction

    @Test
    fun `buildMultisigWitnessScript produces canonical OP_M pubkeys OP_N OP_CHECKMULTISIG layout`() {
        // 2-of-3 with three distinguishable 33-byte pubkeys.
        val pk1 = ByteArray(33) { 0x01 }
        val pk2 = ByteArray(33) { 0x02 }
        val pk3 = ByteArray(33) { 0x03 }
        val script = PsbtEncoding.buildMultisigWitnessScript(2, listOf(pk1, pk2, pk3))

        // Expected layout: OP_2 (0x52) | <push 0x21><pk1> | <push 0x21><pk2> | <push 0x21><pk3> | OP_3 (0x53) | OP_CHECKMULTISIG (0xAE)
        // Total: 1 + (1+33)*3 + 1 + 1 = 105 bytes
        assertEquals(105, script.size)
        assertEquals(0x52.toByte(), script[0])               // OP_2
        assertEquals(0x21.toByte(), script[1])               // push 33
        assertEquals(0x21.toByte(), script[1 + 34])          // push 33 before pk2
        assertEquals(0x21.toByte(), script[1 + 68])          // push 33 before pk3
        assertEquals(0x53.toByte(), script[script.size - 2]) // OP_3
        assertEquals(0xAE.toByte(), script[script.size - 1]) // OP_CHECKMULTISIG
    }

    @Test
    fun `buildMultisigWitnessScript embeds OP_M correctly for 3-of-5`() {
        // 3-of-5 → OP_3 (0x53) prefix and OP_5 (0x55) suffix.
        val pubkeys = (1..5).map { ByteArray(33) { it.toByte() } }
        val script = PsbtEncoding.buildMultisigWitnessScript(3, pubkeys)
        assertEquals(0x53.toByte(), script[0])
        assertEquals(0x55.toByte(), script[script.size - 2])
        assertEquals(0xAE.toByte(), script[script.size - 1])
    }

    /*
     * Parameterized check for multisig witness script layout across the
     * full M-of-N spectrum supported by the application.
     * Layout for any M-of-N with compressed (33-byte) pubkeys:
     *   [OP_M] | [push 0x21][pubkey] * N | [OP_N] | [OP_CHECKMULTISIG]
     *   total = 1 + 34*N + 1 + 1 = 34*N + 3 bytes
     * Range covers 1-of-N edges, M=N edge cases, common 2-of-3,
     * recovery 2-of-4, corporate 3-of-5 and BIP-67 upper limit 15-of-15.
     */
    @ParameterizedTest(name = "{0}-of-{1} witness script layout")
    @CsvSource(
        "1, 2",
        "2, 2",
        "2, 3",
        "2, 4",
        "3, 4",
        "3, 5",
        "5, 9",
        "7, 10",
        "15, 15"
    )
    fun `buildMultisigWitnessScript has canonical layout for arbitrary M-of-N`(m: Int, n: Int) {
        val pubkeys = (1..n).map { i -> ByteArray(33) { i.toByte() } }
        val script = PsbtEncoding.buildMultisigWitnessScript(m, pubkeys)

        assertEquals(34 * n + 3, script.size,
            "$m-of-$n script size = 34*N + 3 bytes")
        assertEquals((0x50 + m).toByte(), script[0],
            "First byte must be OP_$m (0x${"%02x".format(0x50 + m)})")
        assertEquals((0x50 + n).toByte(), script[script.size - 2],
            "Penultimate byte must be OP_$n (0x${"%02x".format(0x50 + n)})")
        assertEquals(0xAE.toByte(), script[script.size - 1],
            "Last byte must be OP_CHECKMULTISIG (0xAE)")

        // Each pubkey is preceded by a 0x21 push opcode (push 33 bytes).
        for (i in 0 until n) {
            val pushPos = 1 + i * 34
            assertEquals(0x21.toByte(), script[pushPos],
                "Push opcode at position $pushPos for pubkey index $i must be 0x21")
        }
    }

    // Bech32 address → scriptPubKey (BIP-173 test vectors)

    @Test
    fun `addressToScript decodes mainnet P2WPKH address from BIP-173`() {
        // BIP-173 test vector. The witness program (20-byte hash) is publicly known
        // for this address; the resulting scriptPubKey prepends 0x00 (OP_0) and 0x14 (push 20).
        val script = PsbtEncoding.addressToScript("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        assertEquals(
            "0014751e76e8199196d454941c45d1b3a323f1433bd6",
            PsbtEncoding.bytesToHex(script)
        )
    }

    @Test
    fun `addressToScript decodes testnet P2WSH address from BIP-173`() {
        // BIP-173 test vector for testnet P2WSH. 32-byte witness program → OP_0 + 0x20 push prefix.
        val script = PsbtEncoding.addressToScript(
            "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"
        )
        assertEquals(
            "00201863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262",
            PsbtEncoding.bytesToHex(script)
        )
    }

    // Raw transaction parsing (used to fill PSBT_IN_NON_WITNESS_UTXO refTxs)

    @Test
    fun `parseRawTransaction handles segwit transaction with witness data`() {
        // Hand-crafted minimal segwit tx (BIP-141 wire format):
        //   version  : 02 00 00 00
        //   marker   : 00
        //   flag     : 01
        //   in count : 01
        //   prevout  : 32 zero bytes | 00 00 00 00 (vout=0)
        //   scriptSig: 00 (empty - SegWit moves it into witness)
        //   sequence : ff ff ff ff
        //   out count: 01
        //   amount   : 40 0d 03 00 00 00 00 00 (200_000 sats LE)
        //   scriptLen: 16 (22 bytes - P2WPKH scriptPubKey)
        //   script   : 00 14 <20 zero bytes>
        //   witness  : 00 (input 0 has zero witness items - placeholder for parser)
        //   locktime : 7B 00 00 00 (123)
        //
        // The parser does not consume witness data per-input - it just reads
        // locktime from data.size - 4. So this exercises the segwit code path
        // (skipping marker/flag) and locktime extraction past witness bytes.
        val pkh = "00".repeat(20)
        val hex = "02000000" +                            // version 2
                  "0001" +                                 // marker + flag (segwit)
                  "01" +                                   // 1 input
                  "00".repeat(32) + "00000000" +           // prev hash + index
                  "00" +                                   // empty scriptSig
                  "ffffffff" +                             // sequence
                  "01" +                                   // 1 output
                  "400d030000000000" +                     // 200_000 sats
                  "16" + "0014" + pkh +                    // P2WPKH scriptPubKey (22 B)
                  "00" +                                   // witness for input 0: 0 items
                  "7b000000"                               // locktime = 123
        val parsed = PsbtEncoding.parseRawTransaction(hex)
        assertEquals(2, parsed.version)
        assertEquals(123, parsed.lockTime, "Locktime must be read from end of buffer")
        assertEquals(1, parsed.inputs.size)
        assertEquals(1, parsed.outputs.size)
        assertEquals(200_000L, parsed.outputs[0].amount)
        assertEquals("0014$pkh", parsed.outputs[0].scriptPubKey)
        // SegWit txs leave the inline scriptSig empty (witness carries the signature).
        assertEquals("", parsed.inputs[0].scriptSig)
    }

    @Test
    fun `parseRawTransaction handles legacy non-segwit transaction`() {
        // Hand-crafted minimal legacy tx: version=2, 1 input (no witness), 1 output, locktime=0.
        // Layout:
        //   version  : 02 00 00 00
        //   in count : 01
        //   prevout  : 00..00 (32 bytes, all zeros) | 00 00 00 00 (vout=0)
        //   scriptSig: 00 (empty)
        //   sequence : ff ff ff ff
        //   out count: 01
        //   amount   : a0 86 01 00 00 00 00 00 (100_000 sats LE)
        //   scriptLen: 16 (22 bytes - P2WPKH scriptPubKey)
        //   script   : 00 14 <20 bytes of 0xab>
        //   locktime : 00 00 00 00
        val pkh = "ab".repeat(20)
        val hex = "02000000" +                              // version 2
                  "01" +                                     // 1 input
                  "00".repeat(32) + "00000000" +             // prev hash + index
                  "00" +                                     // empty scriptSig
                  "ffffffff" +                               // sequence
                  "01" +                                     // 1 output
                  "a086010000000000" +                       // amount 100_000 sats
                  "16" + "0014" + pkh +                      // scriptPubKey: 22 bytes (00 14 <20>)
                  "00000000"                                 // locktime
        val parsed = PsbtEncoding.parseRawTransaction(hex)
        assertEquals(2, parsed.version)
        assertEquals(0, parsed.lockTime)
        assertEquals(1, parsed.inputs.size)
        assertEquals(1, parsed.outputs.size)
        assertEquals(100_000L, parsed.outputs[0].amount)
        assertEquals("0014$pkh", parsed.outputs[0].scriptPubKey)
        // prev_index should be 0 (read as uint32 LE)
        assertEquals(0L, parsed.inputs[0].prevIndex)
    }

    // BIP-32 derivation path encoding for PSBT_IN_BIP32_DERIVATION

    @Test
    fun `encodeBip32Derivation produces fingerprint plus path elements as 4-byte LE`() {
        // fingerprint = "12345678" (4 bytes), originPath = "84h/1h/0h", chain = 0, index = 5
        // Expected: 12 34 56 78 | 54 00 00 80 | 01 00 00 80 | 00 00 00 80 | 00 00 00 00 | 05 00 00 00
        val out = PsbtEncoding.encodeBip32Derivation(
            fingerprint = "12345678",
            originPath = "84h/1h/0h",
            chain = 0,
            index = 5
        )
        val expectedHex = "12345678" +
                          "54000080" +    // 84' = 0x80000054 LE
                          "01000080" +    // 1'  = 0x80000001 LE
                          "00000080" +    // 0'  = 0x80000000 LE
                          "00000000" +    // chain = 0 LE
                          "05000000"      // index = 5 LE
        assertEquals(expectedHex, PsbtEncoding.bytesToHex(out))
    }

    // PSBT magic + structural sanity

    @Test
    fun `parsePsbt rejects buffer with wrong magic`() {
        // Magic must be 0x70 0x73 0x62 0x74 0xff ("psbt" + separator)
        val bad = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0x00, 0x00)
        assertFailsWith<IllegalArgumentException> { PsbtEncoding.parsePsbt(bad) }
    }

    @Test
    fun `parsePsbt requires a global unsigned transaction`() {
        // Magic followed by an immediate 0x00 separator → no global KVs at all.
        // Parser must reject because there is no PSBT_GLOBAL_UNSIGNED_TX.
        val noGlobal = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte(), 0x00)
        assertFailsWith<IllegalArgumentException> { PsbtEncoding.parsePsbt(noGlobal) }
    }
}
