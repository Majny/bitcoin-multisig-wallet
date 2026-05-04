package cz.majny.wallet.psbt.builder

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Tests for PsbtBuilder.estimateVsize. Virtual size in vBytes is the basis
 * of fee calculation, so a regression here would mean every transaction
 * gets the wrong fee. Numbers below come from BIP-141 weight rules:
 *
 *   weight = non_witness_bytes * 4 + witness_bytes * 1
 *   vsize  = weight / 4
 *
 * Per-input contributions:
 *   P2WPKH (singlesig): 68 vB        - 41 B non-witness + 108 wu witness
 *   P2WSH multisig:     41 + (5 + 73*M + 34*N) / 4 vB
 *   - 2-of-3:           41 + (5 + 146 + 102) / 4 = 104 vB
 *   - 3-of-5:           41 + (5 + 219 + 170) / 4 = 139 vB
 *
 * Per-output (P2WPKH or P2WSH): ~31 vB
 * Fixed overhead: 10 vB (version, locktime, marker+flag, varints)
 */
class PsbtBuilderTest {

    @Test
    fun `singlesig P2WPKH typical send is 140 vB`() {
        // 1 input + 2 outputs (recipient + change) + overhead
        // = 68 + 2*31 + 10 = 140 vB
        val vsize = PsbtBuilder.estimateVsize(
            inputCount = 1,
            outputCount = 2,
            isMultisig = false,
            m = 1,
            n = 1
        )
        assertEquals(140, vsize)
    }

    @Test
    fun `singlesig with multiple inputs scales linearly`() {
        // 3 inputs + 2 outputs + overhead
        // = 3*68 + 2*31 + 10 = 276 vB
        val vsize = PsbtBuilder.estimateVsize(
            inputCount = 3,
            outputCount = 2,
            isMultisig = false,
            m = 1,
            n = 1
        )
        assertEquals(276, vsize)
    }

    @Test
    fun `multisig 2-of-3 single input matches BIP-141 calculation`() {
        // Per-input vsize for 2-of-3 P2WSH = 41 + (5 + 73*2 + 34*3) / 4
        //                                  = 41 + (5 + 146 + 102) / 4
        //                                  = 41 + 253/4 (integer)
        //                                  = 41 + 63 = 104 vB
        // Total: 1*104 + 2*31 + 10 = 176 vB
        val vsize = PsbtBuilder.estimateVsize(
            inputCount = 1,
            outputCount = 2,
            isMultisig = true,
            m = 2,
            n = 3
        )
        assertEquals(176, vsize)
    }

    @Test
    fun `multisig 3-of-5 input is larger than 2-of-3`() {
        val vsize2of3 = PsbtBuilder.estimateVsize(
            inputCount = 1, outputCount = 2, isMultisig = true, m = 2, n = 3
        )
        val vsize3of5 = PsbtBuilder.estimateVsize(
            inputCount = 1, outputCount = 2, isMultisig = true, m = 3, n = 5
        )
        // Adding signatures and pubkeys must grow the witness; 3-of-5 has
        // one more sig + two more pubkeys than 2-of-3.
        assertTrue(
            vsize3of5 > vsize2of3,
            "3-of-5 ($vsize3of5 vB) should be larger than 2-of-3 ($vsize2of3 vB)"
        )
    }

    @Test
    fun `multisig is larger per input than singlesig`() {
        val singlesig = PsbtBuilder.estimateVsize(
            inputCount = 1, outputCount = 2, isMultisig = false, m = 1, n = 1
        )
        val multisig2of3 = PsbtBuilder.estimateVsize(
            inputCount = 1, outputCount = 2, isMultisig = true, m = 2, n = 3
        )
        // Regression guard for the bug fixed in commit history: the original
        // implementation reused 68 vB for multisig inputs, which produced
        // a roughly half-sized fee estimate.
        assertTrue(
            multisig2of3 > singlesig,
            "Multisig per-input vsize must exceed singlesig - " +
            "regression of the 68 vB hardcode bug"
        )
    }

    /*
     * Parameterized check that the per-input multisig vsize matches the
     * BIP-141 formula across the full M-of-N spectrum the application
     * supports. The expected per-input value below is computed as
     *   41 + (5 + 73*M + 34*N) / 4   (integer division, BIP-141 weight rule)
     * Range covers single-key edges (1-of-2), M=N edge cases, common
     * 2-of-3, recovery 2-of-4, corporate 3-of-5 and the BIP-67 upper
     * limit 15-of-15 (OP_M opcode range = OP_1..OP_16).
     */
    @ParameterizedTest(name = "{0}-of-{1} per-input vsize = {2} vB")
    @CsvSource(
        "1, 2,  77",
        "2, 2,  95",
        "2, 3, 104",
        "2, 4, 112",
        "3, 4, 131",
        "3, 5, 139",
        "5, 9, 210",
        "7, 10, 255",
        "15, 15, 443"
    )
    fun `multisig per-input vsize matches BIP-141 formula across M-of-N range`(
        m: Int, n: Int, expectedPerInput: Int
    ) {
        // Isolate per-input contribution: 1 input, 0 outputs, 10 vB overhead.
        val total = PsbtBuilder.estimateVsize(
            inputCount = 1, outputCount = 0, isMultisig = true, m = m, n = n
        )
        assertEquals(10 + expectedPerInput, total,
            "$m-of-$n total vsize (10 overhead + per-input)")
    }
}
