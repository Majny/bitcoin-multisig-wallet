package cz.majny.wallet.authservice

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class RsaKeyMaterial(
    val keyId: String,
    val privateKey: RSAPrivateKey,
    val publicKey: RSAPublicKey,
)

object RsaKeys {

    fun fromEnvOrGenerate(): RsaKeyMaterial {
        val kid = System.getenv("JWT_KID") ?: "dev-kid"
        val privPem = System.getenv("JWT_RSA_PRIVATE_PEM")
        val pubPem = System.getenv("JWT_RSA_PUBLIC_PEM")

        return if (!privPem.isNullOrBlank() && !pubPem.isNullOrBlank()) {
            val priv = parsePkcs8PrivateKey(privPem)
            val pub = parseX509PublicKey(pubPem)
            RsaKeyMaterial(kid, priv, pub)
        } else {
            val kp = generate()
            RsaKeyMaterial(
                keyId = kid,
                privateKey = kp.private as RSAPrivateKey,
                publicKey = kp.public as RSAPublicKey
            )
        }
    }

    private fun generate(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    private fun parsePkcs8PrivateKey(pem: String): RSAPrivateKey {
        val der = pemToDer(pem)
        val spec = PKCS8EncodedKeySpec(der)
        val pk: PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(spec)
        return pk as RSAPrivateKey
    }

    private fun parseX509PublicKey(pem: String): RSAPublicKey {
        val der = pemToDer(pem)
        val spec = X509EncodedKeySpec(der)
        val pk: PublicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
        return pk as RSAPublicKey
    }

    private fun pemToDer(pem: String): ByteArray {
        val clean = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(clean)
    }

    fun toJwksJson(kid: String, publicKey: RSAPublicKey): String {
        val n = base64Url(publicKey.modulus.toUnsignedBytes())
        val e = base64Url(publicKey.publicExponent.toUnsignedBytes())

        return """
        {
          "keys": [{
            "kty": "RSA",
            "kid": "$kid",
            "use": "sig",
            "alg": "RS256",
            "n": "$n",
            "e": "$e"
          }]
        }
        """.trimIndent()
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun java.math.BigInteger.toUnsignedBytes(): ByteArray {
        val b = this.toByteArray()
        return if (b.isNotEmpty() && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }
}
