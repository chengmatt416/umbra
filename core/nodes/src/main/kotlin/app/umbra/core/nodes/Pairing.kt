package app.umbra.core.nodes

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing a phone with a node (architecture spec §10.1): the node shows a one-time code; both sides prove they know it
 * with an HMAC over both TLS certificates' fingerprints, so a machine in the middle (which would present its own
 * certificate) cannot complete the pairing without the code. Afterwards each side trusts exactly the other's pinned
 * certificate. Pairing establishes identity only: it is not hardware attestation.
 */
object Pairing {
    /** Crockford base32: no I, L, O, U, so a code read aloud or typed is not misread. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 12 characters, 60 bits: far beyond guessing within a pairing window, even offline. */
    fun newCode(random: SecureRandom = SecureRandom()): String =
        (1..12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.chunked(4).joinToString("-") { it.joinToString("") }

    /** The code as typed: case, spaces and dashes do not matter; common look-alikes are read as Crockford does. */
    fun normalize(code: String): String =
        code.uppercase().filter { it.isLetterOrDigit() }.map { c -> when (c) { 'I', 'L' -> '1'; 'O' -> '0'; else -> c } }.joinToString("")

    private fun key(code: String, nodeFingerprint: String): ByteArray {
        val spec = PBEKeySpec(normalize(code).toCharArray(), ("umbra-node-pairing:" + nodeFingerprint).toByteArray(), 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun hmac(key: ByteArray, text: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mac.doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** What the phone sends: it knows the code, and binds both certificates it saw. */
    fun phoneProof(code: String, nodeFingerprint: String, phoneFingerprint: String) = hmac(key(code, nodeFingerprint), "phone|$nodeFingerprint|$phoneFingerprint")

    /** What the node answers: it knows the code too, for the same two certificates. */
    fun nodeProof(code: String, nodeFingerprint: String, phoneFingerprint: String) = hmac(key(code, nodeFingerprint), "node|$nodeFingerprint|$phoneFingerprint")

    /** Constant-time comparison of two proofs. */
    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/** SHA-256 of a certificate's DER encoding, lower-case hex: how a node or a phone is pinned. */
fun X509Certificate.fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }

/** A short form for people to compare: the first 16 hex digits in groups of four. */
fun shortFingerprint(fp: String) = fp.take(16).chunked(4).joinToString(":")
