package cz.majny.wallet.psbt.builder

import cz.majny.wallet.psbt.api.CosignerDto
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.crypto.HDKeyDerivation
import java.util.Base64

/*
 * Shared helpers for PSBT and Trezor test fixtures.
 * Keeps individual *Test files focused on assertions instead of setup.
 */
object TestFixtures {

    /*
     * BIP-173 testnet bech32 vector. Used as destination and change address
     * in tests - its bytes only need to be a valid bech32 decoding,
     * not derived from any specific wallet key.
     */
    const val TESTNET_ADDR =
        "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"

    /*
     * Generates a deterministic testnet xpub (tpub) from a hex-encoded seed.
     * Same seed always yields the same key, so test outputs are reproducible.
     */
    fun makeTestnetXpub(seedHex: String): String {
        val seed = PsbtEncoding.hexToBytes(seedHex)
        return HDKeyDerivation.createMasterPrivateKey(seed)
            .serializePubB58(BitcoinNetwork.TESTNET)
    }

    /*
     * Builds N synthetic cosigners with deterministic, distinct testnet xpubs.
     * Each seed differs in its trailing byte so master keys are pairwise distinct.
     * Origin path defaults to BIP-48 testnet P2WSH (m/48'/1'/0'/2').
     */
    fun nCosigners(n: Int, originPath: String = "48h/1h/0h/2h"): List<CosignerDto> =
        (0 until n).map { i ->
            val seedHex = "00".repeat(15) + "%02x".format(i + 1)
            CosignerDto(
                idx = i,
                fingerprint = "%08x".format(0x10000000L + i),
                originPath = originPath,
                xpubRoot = makeTestnetXpub(seedHex)
            )
        }

    /* Decodes a base64 PSBT and parses it into the structured ParsedPsbt form. */
    fun parseGeneratedPsbt(base64: String) =
        PsbtEncoding.parsePsbt(Base64.getDecoder().decode(base64))
}
