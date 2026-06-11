package org.hogel.pocketssh.ssh

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import com.trilead.ssh2.auth.SignatureProxy
import com.trilead.ssh2.crypto.SimpleDERReader
import com.trilead.ssh2.packets.TypesWriter
import java.security.Signature
import java.security.interfaces.ECPublicKey

/**
 * SSH publickey-authentication signer that routes signing through an
 * Android Keystore entry guarded by [BiometricAuthenticator].
 *
 * sshlib's [SignatureProxy] is the supported extension point for custom key
 * stores: passing this proxy to `Connection.authenticateWithPublicKey` makes
 * sshlib call back into [sign] instead of touching the private key directly.
 * That lets the real key stay in the TEE — we only hand sshlib the public key
 * and the signature bytes it needs to assemble the auth packet.
 *
 * The keystore entry uses a time-based validity window
 * (`setUserAuthenticationParameters(VALIDITY_SECONDS, BIOMETRIC_STRONG)`), so
 * inside that window [sign] succeeds with no UI; outside it the keystore
 * throws [UserNotAuthenticatedException] and we drive a [BiometricAuthenticator]
 * prompt before retrying.
 */
class KeystoreSignatureProxy(
    publicKey: ECPublicKey,
    private val keyManager: SshKeyManager,
    private val authenticator: BiometricAuthenticator,
) : SignatureProxy(publicKey) {

    override fun sign(message: ByteArray, hashAlgorithm: String): ByteArray {
        val derSignature = try {
            try {
                signOnce(message)
            } catch (_: UserNotAuthenticatedException) {
                // Validity window expired (or this is the first sign after key
                // generation). Prompt for biometrics and retry exactly once.
                authenticator.authenticate()
                signOnce(message)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // The keystore entry was generated with
            // setInvalidatedByBiometricEnrollment(true), so enrolling a new
            // fingerprint permanently invalidates the SSH key. The user must
            // regenerate it and re-register the public key on the server;
            // surface that as an actionable message instead of a raw crash.
            throw BiometricAuthenticationException(
                "SSH key was invalidated by a new biometric enrollment. " +
                    "Generate a new key in the app and add its public key to the server.",
                e,
            )
        }
        return encodeSshEcdsaSignature(derSignature)
    }

    private fun signOnce(message: ByteArray): ByteArray {
        val privateKey = keyManager.loadPrivateKey()
            ?: throw BiometricAuthenticationException("Keystore private key is missing")
        // SHA256withECDSA matches the DIGEST_SHA256 authorization on the
        // keystore entry; sshlib requests SHA-256 for P-256 keys via
        // ECDSASHA2Verify.getDigestAlgorithmForParams.
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(message)
        }
        return signature.sign()
    }

    /**
     * Converts a DER-encoded ECDSA signature (`SEQUENCE { INTEGER r, INTEGER s }`)
     * into the SSH wire format (RFC 5656 §3.1.2):
     * `string "ecdsa-sha2-nistp256"` followed by `string mpint(r) || mpint(s)`.
     *
     * This mirrors `ECDSASHA2Verify.encodeSSHECDSASignature`, which is private
     * in sshlib, so we reimplement it here using the library's public
     * `SimpleDERReader` / `TypesWriter` helpers.
     */
    private fun encodeSshEcdsaSignature(derSignature: ByteArray): ByteArray {
        val reader = SimpleDERReader(derSignature)
        reader.resetInput(reader.readSequenceAsByteArray())
        val r = reader.readInt()
        val s = reader.readInt()

        val rs = TypesWriter().apply {
            writeMPInt(r)
            writeMPInt(s)
        }.getBytes()

        return TypesWriter().apply {
            writeString(KEY_FORMAT)
            writeString(rs, 0, rs.size)
        }.getBytes()
    }

    companion object {
        private const val KEY_FORMAT = "ecdsa-sha2-nistp256"
    }
}
