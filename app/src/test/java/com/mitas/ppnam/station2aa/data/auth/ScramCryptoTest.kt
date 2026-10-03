package com.mitas.ppnam.station2aa.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RFC 7677 §3 test vector is the whole point of this class: it is the only way to know our
 * SCRAM matches Station 2's without a live exchange. Everything else here guards a specific way
 * the computation can be subtly wrong while still producing plausible-looking base64.
 */
class ScramCryptoTest {

    // RFC 7677 §3, verbatim.
    //   username = "user", password = "pencil"
    //   clientNonce = "rOprNGfwEbeRWgbNEkqO"
    //   serverNonce = "rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0"
    //   salt = "W22ZaJ0SNY7soEsUEjb6gQ==", iterations = 4096
    private val username = "user"
    private val password = "pencil"
    private val clientNonce = "rOprNGfwEbeRWgbNEkqO"
    private val serverNonce = "rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF\$k0"
    private val salt = "W22ZaJ0SNY7soEsUEjb6gQ=="
    private val iterations = 4096
    private val serverFirstMessage = "r=$serverNonce,s=$salt,i=$iterations"

    private fun rfcAuthMessage(): String = ScramCrypto.authMessage(
        ScramCrypto.clientFirstBare(username, clientNonce),
        serverFirstMessage,
        ScramCrypto.clientFinalWithoutProof(serverNonce),
    )

    @Test
    fun `RFC 7677 test vector produces the published client proof`() {
        val proof = ScramCrypto.computeProof(password, salt, iterations, rfcAuthMessage())

        assertEquals(
            "dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=",
            proof.clientProofBase64,
        )
    }

    @Test
    fun `RFC 7677 test vector produces the published server signature`() {
        val proof = ScramCrypto.computeProof(password, salt, iterations, rfcAuthMessage())

        assertEquals(
            "6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=",
            proof.expectedServerSignatureBase64,
        )
    }

    @Test
    fun `AuthMessage is assembled in the contract's exact order`() {
        assertEquals(
            "n=user,r=$clientNonce,$serverFirstMessage,c=biws,r=$serverNonce",
            rfcAuthMessage(),
        )
    }

    @Test
    fun `username escaping covers both SCRAM separators`() {
        // '=' must be escaped before ',' — the other order would re-escape the '=' it introduced,
        // turning "a,b" into "a=3D2Cb" instead of "a=2Cb".
        assertEquals("a=2Cb", ScramCrypto.escapeUsername("a,b"))
        assertEquals("a=3Db", ScramCrypto.escapeUsername("a=b"))
        assertEquals("a=3D=2Cb", ScramCrypto.escapeUsername("a=,b"))
    }

    @Test
    fun `NFKC normalization changes the derived proof`() {
        // U+FB01 LATIN SMALL LIGATURE FI normalizes under KC to "fi". A build that skipped
        // normalization would send a different proof for a password a user typed either way.
        val ligature = ScramCrypto.computeProof("ﬁle", salt, iterations, rfcAuthMessage())
        val expanded = ScramCrypto.computeProof("file", salt, iterations, rfcAuthMessage())

        assertEquals(expanded.clientProofBase64, ligature.clientProofBase64)
    }

    @Test
    fun `password is hashed over UTF-8 bytes not low bytes of chars`() {
        // The PKCS#5-vs-PKCS#12 trap: 'ä' (U+00E4) is one byte as latin-1 and two as UTF-8. A
        // provider using only the low byte of each char would make these two collide.
        val nonAscii = ScramCrypto.computeProof("pässwörd", salt, iterations, rfcAuthMessage())
        val lowByte = ScramCrypto.computeProof("pässwörd", salt, iterations, rfcAuthMessage())

        // Same string two ways — must agree...
        assertEquals(nonAscii.clientProofBase64, lowByte.clientProofBase64)
        // ...and must differ from the ASCII-folded spelling, proving the accents reached the hash.
        val folded = ScramCrypto.computeProof("password", salt, iterations, rfcAuthMessage())
        assertTrue(nonAscii.clientProofBase64 != folded.clientProofBase64)
    }

    @Test
    fun `iteration count is honoured`() {
        val low = ScramCrypto.computeProof(password, salt, 4096, rfcAuthMessage())
        val high = ScramCrypto.computeProof(password, salt, 8192, rfcAuthMessage())

        assertTrue(low.clientProofBase64 != high.clientProofBase64)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero iterations is rejected rather than silently producing a key`() {
        ScramCrypto.computeProof(password, salt, 0, rfcAuthMessage())
    }

    @Test
    fun `server signature verification accepts the matching value`() {
        val proof = ScramCrypto.computeProof(password, salt, iterations, rfcAuthMessage())

        assertTrue(
            ScramCrypto.verifyServerSignature(
                proof.expectedServerSignatureBase64,
                proof.expectedServerSignatureBase64,
            )
        )
    }

    @Test
    fun `server signature verification rejects a wrong, empty or malformed value`() {
        val proof = ScramCrypto.computeProof(password, salt, iterations, rfcAuthMessage())
        val expected = proof.expectedServerSignatureBase64

        // The client proof: valid base64, correct 32-byte length, definitely not the signature.
        // (Deliberately not "the expected value with its last character nudged" — in a padded
        // base64 group the trailing bits are discarded, so such a string decodes to the same
        // bytes and would make this assertion test nothing.)
        assertFalse(ScramCrypto.verifyServerSignature(expected, proof.clientProofBase64))
        assertFalse(ScramCrypto.verifyServerSignature(expected, ""))
        assertFalse(ScramCrypto.verifyServerSignature(expected, "not base64 at all!!"))
        // An empty expected value must not vacuously verify.
        assertFalse(ScramCrypto.verifyServerSignature("", ""))
    }

    @Test
    fun `server nonce must extend the client nonce`() {
        assertEquals(serverNonce, ScramCrypto.parseServerNonce(serverFirstMessage, clientNonce))

        // A server nonce that doesn't start with ours means the exchange has been substituted.
        assertNull(ScramCrypto.parseServerNonce("r=somethingElse,s=$salt,i=$iterations", clientNonce))
        // Echoing our nonce back unextended adds no server entropy — also invalid.
        assertNull(ScramCrypto.parseServerNonce("r=$clientNonce,s=$salt,i=$iterations", clientNonce))
        // Missing attribute entirely.
        assertNull(ScramCrypto.parseServerNonce("s=$salt,i=$iterations", clientNonce))
    }

    @Test
    fun `client nonces are unpredictable and unpadded`() {
        val nonces = List(50) { ScramCrypto.generateClientNonce() }

        assertEquals(50, nonces.toSet().size)
        nonces.forEach { assertFalse("nonce should not be padded: $it", it.contains("=")) }
    }
}
