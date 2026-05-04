package cz.majny.wallet.psbt.builder

import cz.majny.wallet.psbt.api.CosignerDto
import cz.majny.wallet.psbt.api.TxOutput
import cz.majny.wallet.psbt.api.WalletDetailDto
import cz.majny.wallet.psbt.builder.TestFixtures.TESTNET_ADDR
import cz.majny.wallet.psbt.builder.TestFixtures.makeTestnetXpub
import cz.majny.wallet.psbt.builder.TestFixtures.nCosigners
import cz.majny.wallet.psbt.builder.TestFixtures.parseGeneratedPsbt
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * End-to-end roundtrip tests for PsbtBuilder.createPsbt:
 *   1. Build PSBT from synthetic wallet + UTXO.
 *   2. Decode base64, parse the binary structure.
 *   3. Assert that all required BIP-174 records are present and well-formed.
 *
 * Together with PsbtEncodingTest these cover the three implementation
 * invariants explicitly called out in section sec:test-chyby of the thesis:
 *   - PSBT_IN_NON_WITNESS_UTXO present for every input (firmware 2.4+)
 *   - PSBT_IN_WITNESS_SCRIPT present for multisig inputs with BIP-67-sorted
 *     pubkeys
 *   - PSBT_OUT_BIP32_DERIVATION present for change so Trezor recognizes it
 *     as internal and does not surface it as a payment to a third party
 *
 * Tests are fully offline - no network, no database, no Trezor emulator.
 * Cosigner xpubs are generated deterministically from fixed seeds.
 */
class PsbtBuilderRoundtripTest {

    @Test
    fun `singlesig PSBT magic prefix and per-input per-output records are well-formed`() {
        val cosigner = CosignerDto(
            idx = 0,
            fingerprint = "12345678",
            originPath = "84h/1h/0h",
            xpubRoot = makeTestnetXpub("000102030405060708090a0b0c0d0e0f")
        )
        val wallet = WalletDetailDto(
            walletId = "test-singlesig",
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

        val result = PsbtBuilder.createPsbt(
            wallet = wallet,
            utxos = listOf(utxo),
            outputs = listOf(output),
            changeAddress = TESTNET_ADDR,
            changeIndex = 7,
            feeRate = 10.0
        )

        // Magic: "psbt" + 0xff = 70 73 62 74 ff
        val raw = Base64.getDecoder().decode(result.psbtBase64)
        assertEquals(0x70.toByte(), raw[0])
        assertEquals(0x73.toByte(), raw[1])
        assertEquals(0x62.toByte(), raw[2])
        assertEquals(0x74.toByte(), raw[3])
        assertEquals(0xff.toByte(), raw[4])

        val parsed = parseGeneratedPsbt(result.psbtBase64)
        assertEquals(1, parsed.inputKvs.size)
        assertEquals(2, parsed.outputKvs.size)  // recipient + change

        val inputKvs = parsed.inputKvs[0]
        assertTrue(
            inputKvs.any { it.keyType == PsbtEncoding.PSBT_IN_WITNESS_UTXO },
            "Singlesig input must contain PSBT_IN_WITNESS_UTXO"
        )
        assertTrue(
            inputKvs.any { it.keyType == PsbtEncoding.PSBT_IN_BIP32_DERIVATION },
            "Singlesig input must contain PSBT_IN_BIP32_DERIVATION"
        )
        assertTrue(
            inputKvs.none { it.keyType == PsbtEncoding.PSBT_IN_WITNESS_SCRIPT },
            "Singlesig input must NOT contain PSBT_IN_WITNESS_SCRIPT (multisig only)"
        )
        // Change output (index 1) carries BIP32 derivation, recipient (index 0) does not.
        assertTrue(
            parsed.outputKvs[1].any { it.keyType == PsbtEncoding.PSBT_OUT_BIP32_DERIVATION },
            "Change output must contain PSBT_OUT_BIP32_DERIVATION"
        )
        assertEquals(0, parsed.outputKvs[0].size,
            "Recipient output must have no per-output metadata")
    }

    @Test
    fun `singlesig PSBT contains NON_WITNESS_UTXO when raw tx hex is provided`() {
        // Regression guard for the Trezor firmware 2.4+ requirement
        // documented in section sec:test-chyby. Without this record,
        // signing fails with "Transaction has changed during signing".
        val cosigner = CosignerDto(
            idx = 0,
            fingerprint = "12345678",
            originPath = "84h/1h/0h",
            xpubRoot = makeTestnetXpub("000102030405060708090a0b0c0d0e0f")
        )
        val wallet = WalletDetailDto(
            walletId = "test-singlesig-nwu",
            network = "testnet",
            type = "SINGLE_SIG",
            scriptType = "WPKH",
            receiveDescriptor = "wpkh([12345678/84h/1h/0h]${cosigner.xpubRoot}/0/*)",
            changeDescriptor = "wpkh([12345678/84h/1h/0h]${cosigner.xpubRoot}/1/*)",
            cosigners = listOf(cosigner)
        )
        // Minimal valid raw tx (legacy, non-segwit): 1 input, 1 output 200 000 sats.
        val rawTxHex = "02000000" +                       // version 2
                       "01" +                              // 1 input
                       "00".repeat(32) + "00000000" +      // prev hash + index
                       "00" +                              // empty scriptSig
                       "ffffffff" +                        // sequence
                       "01" +                              // 1 output
                       "400d030000000000" +                // 200_000 sats LE
                       "16" + "0014" + "ab".repeat(20) +   // scriptPubKey 22 B
                       "00000000"                          // locktime
        val utxo = SelectedUtxo(
            txid = "00".repeat(32),
            vout = 0,
            value = 200_000L,
            scriptPubKey = "0014" + "ab".repeat(20),
            addressIndex = 5,
            addressType = "receive",
            address = TESTNET_ADDR,
            rawTxHex = rawTxHex
        )
        val output = TxOutput(address = TESTNET_ADDR, amountSats = 100_000L)

        val result = PsbtBuilder.createPsbt(
            wallet = wallet,
            utxos = listOf(utxo),
            outputs = listOf(output),
            changeAddress = TESTNET_ADDR,
            changeIndex = 7,
            feeRate = 10.0
        )
        val parsed = parseGeneratedPsbt(result.psbtBase64)
        val nonWit = parsed.inputKvs[0].firstOrNull {
            it.keyType == PsbtEncoding.PSBT_IN_NON_WITNESS_UTXO
        }
        assertNotNull(nonWit,
            "PSBT_IN_NON_WITNESS_UTXO must be present (Trezor firmware 2.4+)")
        // Value bytes must be the raw tx bytes verbatim.
        assertEquals(
            rawTxHex,
            PsbtEncoding.bytesToHex(nonWit.value),
            "NON_WITNESS_UTXO value must contain the raw previous transaction"
        )
    }

    @Test
    fun `multisig 2-of-3 PSBT contains witness script with BIP-67-sorted pubkeys`() {
        val cosigners = listOf(
            CosignerDto(0, "11111111", "48h/1h/0h/2h",
                makeTestnetXpub("000102030405060708090a0b0c0d0e0f")),
            CosignerDto(1, "22222222", "48h/1h/0h/2h",
                makeTestnetXpub("0102030405060708090a0b0c0d0e0f10")),
            CosignerDto(2, "33333333", "48h/1h/0h/2h",
                makeTestnetXpub("020304050607080a0b0c0d0e0f102030"))
        )
        val descriptor = "wsh(sortedmulti(2," +
            cosigners.joinToString(",") {
                "[${it.fingerprint}/48h/1h/0h/2h]${it.xpubRoot}/0/*"
            } + "))"
        val wallet = WalletDetailDto(
            walletId = "test-multisig",
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
            value = 200_000L,
            scriptPubKey = "0020" + "ab".repeat(32),
            addressIndex = 5,
            addressType = "receive",
            address = TESTNET_ADDR
        )
        val output = TxOutput(address = TESTNET_ADDR, amountSats = 100_000L)

        val result = PsbtBuilder.createPsbt(
            wallet = wallet,
            utxos = listOf(utxo),
            outputs = listOf(output),
            changeAddress = TESTNET_ADDR,
            changeIndex = 7,
            feeRate = 10.0
        )
        val parsed = parseGeneratedPsbt(result.psbtBase64)
        val inputKvs = parsed.inputKvs[0]

        // Multisig input MUST contain WITNESS_SCRIPT.
        val witnessScript = inputKvs.firstOrNull {
            it.keyType == PsbtEncoding.PSBT_IN_WITNESS_SCRIPT
        }
        assertNotNull(witnessScript, "Multisig input must contain PSBT_IN_WITNESS_SCRIPT")

        // Layout:
        //   OP_2 (0x52) | <push 33><pk1 33B> | <push 33><pk2 33B> | <push 33><pk3 33B> | OP_3 (0x53) | OP_CHECKMULTISIG (0xAE)
        //   = 1 + (1+33)*3 + 1 + 1 = 105 B
        assertEquals(105, witnessScript.value.size,
            "2-of-3 witness script must be exactly 105 bytes")
        assertEquals(0x52.toByte(), witnessScript.value[0],
            "Script must begin with OP_2 (0x52)")
        assertEquals(0x53.toByte(), witnessScript.value[103],
            "Script must end with OP_3 (0x53) before OP_CHECKMULTISIG")
        assertEquals(0xAE.toByte(), witnessScript.value[104],
            "Script must end with OP_CHECKMULTISIG (0xAE)")

        // Extract the three pubkeys and verify BIP-67 lexicographic ordering.
        val pk1Hex = PsbtEncoding.bytesToHex(witnessScript.value.sliceArray(2..34))
        val pk2Hex = PsbtEncoding.bytesToHex(witnessScript.value.sliceArray(36..68))
        val pk3Hex = PsbtEncoding.bytesToHex(witnessScript.value.sliceArray(70..102))
        assertTrue(
            pk1Hex <= pk2Hex,
            "BIP-67 violation: pk1 ($pk1Hex) > pk2 ($pk2Hex)"
        )
        assertTrue(
            pk2Hex <= pk3Hex,
            "BIP-67 violation: pk2 ($pk2Hex) > pk3 ($pk3Hex)"
        )

        // Each cosigner must have a per-input BIP32_DERIVATION entry.
        val derivations = inputKvs.count {
            it.keyType == PsbtEncoding.PSBT_IN_BIP32_DERIVATION
        }
        assertEquals(3, derivations,
            "Multisig input must have one BIP32_DERIVATION per cosigner")

        // Change output must also have 3 BIP32_DERIVATION entries.
        val changeDerivations = parsed.outputKvs[1].count {
            it.keyType == PsbtEncoding.PSBT_OUT_BIP32_DERIVATION
        }
        assertEquals(3, changeDerivations,
            "Multisig change must have one BIP32_DERIVATION per cosigner")
    }

    /*
     * Parameterized roundtrip across the M-of-N spectrum the application
     * supports. For each (M, N) combination the test builds a synthetic
     * sortedmulti P2WSH wallet with N cosigners, generates a PSBT and
     * verifies all per-input/per-output invariants from sec:test-chyby:
     *   - witness script size = 34*N + 3 bytes
     *   - first byte = OP_M, penultimate = OP_N, last = OP_CHECKMULTISIG
     *   - all N pubkeys in the witness script are in BIP-67 lex order
     *   - one PSBT_IN_BIP32_DERIVATION per cosigner on the input side
     *   - one PSBT_OUT_BIP32_DERIVATION per cosigner on the change output
     */
    @ParameterizedTest(name = "{0}-of-{1} multisig PSBT roundtrip")
    @CsvSource(
        "2, 2",
        "2, 3",
        "2, 4",
        "3, 4",
        "3, 5",
        "5, 7"
    )
    fun `multisig PSBT roundtrip preserves all invariants across M-of-N range`(m: Int, n: Int) {
        val cosigners = nCosigners(n)
        val descriptor = "wsh(sortedmulti($m," +
            cosigners.joinToString(",") {
                "[${it.fingerprint}/48h/1h/0h/2h]${it.xpubRoot}/0/*"
            } + "))"
        val wallet = WalletDetailDto(
            walletId = "test-multisig-$m-of-$n",
            network = "testnet",
            type = "MULTI_SIG",
            scriptType = "WSH",
            m = m,
            n = n,
            receiveDescriptor = descriptor,
            changeDescriptor = descriptor.replace("/0/*", "/1/*"),
            cosigners = cosigners
        )
        // Larger M, N → larger fee. Use a generous input value so change
        // stays well above the dust threshold for every tested combination.
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

        val result = PsbtBuilder.createPsbt(
            wallet = wallet,
            utxos = listOf(utxo),
            outputs = listOf(output),
            changeAddress = TESTNET_ADDR,
            changeIndex = 7,
            feeRate = 10.0
        )
        val parsed = parseGeneratedPsbt(result.psbtBase64)
        val inputKvs = parsed.inputKvs[0]

        // WITNESS_SCRIPT present and structurally correct.
        val witnessScript = inputKvs.firstOrNull {
            it.keyType == PsbtEncoding.PSBT_IN_WITNESS_SCRIPT
        }
        assertNotNull(witnessScript, "$m-of-$n must have PSBT_IN_WITNESS_SCRIPT")

        val expectedSize = 34 * n + 3
        assertEquals(expectedSize, witnessScript.value.size,
            "$m-of-$n witness script size must be 34*N + 3")
        assertEquals((0x50 + m).toByte(), witnessScript.value[0],
            "$m-of-$n script must begin with OP_$m")
        assertEquals((0x50 + n).toByte(), witnessScript.value[witnessScript.value.size - 2],
            "$m-of-$n script must have OP_$n before OP_CHECKMULTISIG")
        assertEquals(0xAE.toByte(), witnessScript.value[witnessScript.value.size - 1],
            "$m-of-$n script must end with OP_CHECKMULTISIG")

        // BIP-67 lex ordering across all N pubkeys.
        val pkHexes = (0 until n).map { i ->
            val start = 2 + i * 34
            PsbtEncoding.bytesToHex(witnessScript.value.sliceArray(start until start + 33))
        }
        for (i in 0 until n - 1) {
            assertTrue(
                pkHexes[i] <= pkHexes[i + 1],
                "$m-of-$n BIP-67 violation at position $i: ${pkHexes[i]} > ${pkHexes[i + 1]}"
            )
        }

        // One BIP32_DERIVATION per cosigner on input.
        val inputDerivations = inputKvs.count {
            it.keyType == PsbtEncoding.PSBT_IN_BIP32_DERIVATION
        }
        assertEquals(n, inputDerivations,
            "$m-of-$n must have $n PSBT_IN_BIP32_DERIVATION entries (one per cosigner)")

        // One OUT_BIP32_DERIVATION per cosigner on change.
        val changeDerivations = parsed.outputKvs[1].count {
            it.keyType == PsbtEncoding.PSBT_OUT_BIP32_DERIVATION
        }
        assertEquals(n, changeDerivations,
            "$m-of-$n change must have $n PSBT_OUT_BIP32_DERIVATION entries")
    }

    @Test
    fun `change below dust threshold is folded into fee and no change output is created`() {
        // Construct a scenario where input - output - estimated_fee falls below
        // the 546-sat dust limit. PsbtBuilder must drop the change output and
        // surface changeAmount = 0 in the result; the unsigned tx then has only
        // one output (the recipient).
        val cosigner = CosignerDto(
            idx = 0,
            fingerprint = "12345678",
            originPath = "84h/1h/0h",
            xpubRoot = makeTestnetXpub("000102030405060708090a0b0c0d0e0f")
        )
        val wallet = WalletDetailDto(
            walletId = "test-dust",
            network = "testnet",
            type = "SINGLE_SIG",
            scriptType = "WPKH",
            receiveDescriptor = "wpkh([12345678/84h/1h/0h]${cosigner.xpubRoot}/0/*)",
            changeDescriptor = "wpkh([12345678/84h/1h/0h]${cosigner.xpubRoot}/1/*)",
            cosigners = listOf(cosigner)
        )
        // Singlesig vsize for 1 input + 2 outputs = 140 vB.
        // At feeRate=5 sat/vB → fee = 700 sats.
        // input 1000 - output 200 - fee 700 = 100 sats change → below 546 dust.
        val utxo = SelectedUtxo(
            txid = "00".repeat(32),
            vout = 0,
            value = 1000L,
            scriptPubKey = "0014" + "ab".repeat(20),
            addressIndex = 5,
            addressType = "receive",
            address = TESTNET_ADDR
        )
        val output = TxOutput(address = TESTNET_ADDR, amountSats = 200L)

        val result = PsbtBuilder.createPsbt(
            wallet = wallet,
            utxos = listOf(utxo),
            outputs = listOf(output),
            changeAddress = TESTNET_ADDR,
            changeIndex = 7,
            feeRate = 5.0
        )
        assertEquals(0L, result.changeAmount,
            "Change below dust limit must be folded into fee (changeAmount=0)")

        val parsed = parseGeneratedPsbt(result.psbtBase64)
        assertEquals(1, parsed.outputKvs.size,
            "Unsigned tx must contain only the recipient output (no change)")
    }

    @Test
    fun `insufficient funds throws IllegalArgumentException`() {
        // Input < output + minimum fee → cannot construct a valid transaction.
        val cosigner = CosignerDto(
            idx = 0,
            fingerprint = "12345678",
            originPath = "84h/1h/0h",
            xpubRoot = makeTestnetXpub("000102030405060708090a0b0c0d0e0f")
        )
        val wallet = WalletDetailDto(
            walletId = "test-insufficient",
            network = "testnet",
            type = "SINGLE_SIG",
            scriptType = "WPKH",
            receiveDescriptor = "wpkh([12345678/84h/1h/0h]${cosigner.xpubRoot}/0/*)",
            changeDescriptor = "wpkh([12345678/84h/1h/0h]${cosigner.xpubRoot}/1/*)",
            cosigners = listOf(cosigner)
        )
        // 100 input, 100 output, ANY fee → negative change → throw.
        val utxo = SelectedUtxo(
            txid = "00".repeat(32),
            vout = 0,
            value = 100L,
            scriptPubKey = "0014" + "ab".repeat(20),
            addressIndex = 5,
            addressType = "receive",
            address = TESTNET_ADDR
        )
        val output = TxOutput(address = TESTNET_ADDR, amountSats = 100L)

        assertFailsWith<IllegalArgumentException> {
            PsbtBuilder.createPsbt(
                wallet = wallet,
                utxos = listOf(utxo),
                outputs = listOf(output),
                changeAddress = TESTNET_ADDR,
                changeIndex = 7,
                feeRate = 1.0
            )
        }
    }
}
