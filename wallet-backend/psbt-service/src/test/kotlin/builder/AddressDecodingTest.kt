package cz.majny.wallet.psbt.builder

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/*
 * Address checksum and format validation in PsbtEncoding.addressToScript.
 * Segwit vectors are copied verbatim from the "segwit address" tables of
 * BIP-173 and BIP-350. A wrong address here would silently produce an
 * output script nobody can spend, so every invalid vector must be rejected.
 */
class AddressDecodingTest {

    // Valid segwit addresses → scriptPubKey (BIP-350 table, which includes the BIP-173 v0 rows)

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4, 0014751e76e8199196d454941c45d1b3a323f1433bd6",
        "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7, 00201863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262",
        "bc1pw508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kt5nd6y, 5128751e76e8199196d454941c45d1b3a323f1433bd6751e76e8199196d454941c45d1b3a323f1433bd6",
        "BC1SW50QGDZ25J, 6002751e",
        "bc1zw508d6qejxtdg4y5r3zarvaryvaxxpcs, 5210751e76e8199196d454941c45d1b3a323",
        "tb1qqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesrxh6hy, 0020000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433",
        "tb1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesf3hn0c, 5120000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433",
        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0, 512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    )
    fun `valid segwit address decodes to the BIP scriptPubKey`(address: String, scriptPubKey: String) {
        assertEquals(scriptPubKey, PsbtEncoding.addressToScriptHex(address))
    }

    // Invalid segwit addresses (BIP-173 and BIP-350 tables)

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [
        // BIP-173
        "tc1qw508d6qejxtdg4y5r3zarvary0c5xw7kg3g4ty",                                 // invalid human-readable part
        "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5",                                 // invalid checksum
        "BC13W508D6QEJXTDG4Y5R3ZARVARY0C5XW7KN40WF2",                                 // invalid witness version
        "bc1rw5uspcuh",                                                               // invalid program length
        "bc10w508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kw5rljs90", // invalid program length
        "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P",                                       // v0 program length (BIP-141)
        "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sL5k7",             // mixed case
        "bc1zw508d6qejxtdg4y5r3zarvaryvqyzf3du",                                      // zero padding of more than 4 bits
        "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3pjxtptv",             // non-zero padding
        "bc1gmk9yu",                                                                  // empty data section
        // BIP-350
        "tc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq5zuyut",             // invalid human-readable part
        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd",             // bech32 instead of bech32m
        "tb1z0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqglt7rf",             // bech32 instead of bech32m
        "BC1S0XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ54WELL",             // bech32 instead of bech32m
        "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh",                                 // bech32m instead of bech32
        "tb1q0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq24jc47",             // bech32m instead of bech32
        "bc1p38j9r5y49hruaue7wxjce0updqjuyyx0kh56v8s25huc6995vvpql3jow4",             // invalid character in checksum
        "BC130XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ7ZWS8R",             // invalid witness version
        "bc1pw5dgrnzv",                                                               // program length 1 byte
        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7v8n0nx0muaewav253zgeav", // program length 41 bytes
        "tb1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq47Zagq",             // mixed case
        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7v07qwwzcrf",           // zero padding of more than 4 bits
        "tb1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vpggkg4j",             // non-zero padding
    ])
    fun `invalid segwit address is rejected`(address: String) {
        assertFailsWith<IllegalArgumentException> { PsbtEncoding.decodeSegwitAddress(address) }
        assertFailsWith<IllegalArgumentException> { PsbtEncoding.addressToScript(address) }
    }

    /*
     * BIP-173 listed these v1+ addresses as valid with a bech32 checksum.
     * BIP-350 replaced that checksum with bech32m for v1+, so they must now fail.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [
        "bc1pw508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7k7grplx",
        "BC1SW50QA3JX3S",
        "bc1zw508d6qejxtdg4y5r3zarvaryvg6kdaj",
    ])
    fun `BIP-173 v1+ address with a bech32 checksum is rejected after BIP-350`(address: String) {
        assertFailsWith<IllegalArgumentException> { PsbtEncoding.decodeSegwitAddress(address) }
    }

    @Test
    fun `non-ASCII look-alike character is rejected`() {
        // BIP-173 allows only US-ASCII 33..126. U+212A (Kelvin sign) lowercases to 'k', so without
        // that rule this would decode as the valid BIP-173 address BC1QW508...XW7KV8F3T4.
        val e = assertFailsWith<IllegalArgumentException> {
            PsbtEncoding.addressToScript("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4")
        }
        assertTrue(e.message!!.contains("non-ASCII"))
    }

    // Base58Check (P2PKH / P2SH)

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa, 76a91462e907b15cbf27d5425399ebf6f0fb50ebb88f1888ac", // mainnet P2PKH
        "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn, 76a914243f1394f44554f4ce3fd68649c19adc483ce92488ac", // testnet P2PKH
        "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy, a914b472a266d0bd89c13706a4132ccfb16f7c3b9fcb87",     // mainnet P2SH
        "2MzQwSSnBHWHqSAqtTVQ6v47XtaisrJa1Vc, a9144e9f39ca4688ff102128ea4ccda34105324305b087"     // testnet P2SH
    )
    fun `valid Base58Check address decodes to its scriptPubKey`(address: String, scriptPubKey: String) {
        assertEquals(scriptPubKey, PsbtEncoding.addressToScriptHex(address))
    }

    @Test
    fun `Base58Check address with a bad checksum is rejected`() {
        // Last character of 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa changed: same hash, wrong checksum.
        val e = assertFailsWith<IllegalArgumentException> {
            PsbtEncoding.addressToScript("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb")
        }
        assertTrue(e.message!!.contains("checksum"))
    }

    @Test
    fun `Base58Check address with a truncated payload is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PsbtEncoding.addressToScript("1A1zP1eP5QGefi2DMPTfTL5SLmv7Divf")
        }
    }
}
