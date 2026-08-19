package com.alexleoreeves.novelapp.tv.mediacache

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.alexleoreeves.novelapp.data.mediacache.EncryptedChunk
import com.alexleoreeves.novelapp.data.mediacache.MediaCryptoException
import com.alexleoreeves.novelapp.data.mediacache.MediaCryptoPort
import com.alexleoreeves.novelapp.data.mediacache.ivForChunk
import com.alexleoreeves.novelapp.data.mediacache.toHex
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * AES-256-CBC + HMAC-SHA256 (encrypt-then-MAC) implementation for Smart TV.
 *
 * Both keys live inside the Android Keystore and are marked non-exportable, so
 * the raw bytes can never be extracted from the device — the downloaded bundle
 * is unreadable without the TV's own keystore, satisfying the at-rest binding
 * requirement. The HMAC key also acts as the device-binding: a bundle copied to
 * another device (or a PC) cannot be decrypted or even integrity-verified.
 *
 * On-disk layout per chunk (matches MediaCryptoPort contract):
 *   [tag(32)][iv(16)][ciphertext]
 *
 * The provider is deliberately context-free: AndroidKeyStore is process-wide.
 * It lazily provisions both keys on first access, so [keysAvailable] doubles as
 * an ensure-provisioned call and can be invoked on the engine's IO dispatcher.
 */
class TvMediaCryptoProvider : MediaCryptoPort {

    override val keysAvailable: Boolean
        get() = provision()

    override fun hmacKeyFingerprint(): String {
        provision()
        val hmacKey = requireNotNull(keystore.getKey(HMAC_ALIAS, null) as? SecretKey) {
            "HMAC key missing"
        }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(hmacKey)
        return mac.doFinal("novelapp_device_binding_fingerprint".toByteArray(Charsets.UTF_8)).toHex()
    }

    override fun generateIvSeed(): ByteArray {
        val seed = ByteArray(16)
        SecureRandom().nextBytes(seed)
        return seed
    }

    override fun encryptChunk(plaintext: ByteArray, ivSeed: ByteArray, chunkIndex: Int): EncryptedChunk {
        val iv = ivForChunk(ivSeed, chunkIndex)
        val ciphertext = aesCipher(Cipher.ENCRYPT_MODE, iv).doFinal(plaintext)
        val tag = hmac(iv, ciphertext)
        return EncryptedChunk(
            bytes = tag + iv + ciphertext,
            tag = tag,
            iv = iv,
            ciphertext = ciphertext
        )
    }

    override fun decryptChunk(data: ByteArray, ivSeed: ByteArray, chunkIndex: Int): ByteArray {
        val tag = data.copyOfRange(0, 32)
        val iv = data.copyOfRange(32, 48)
        val ciphertext = data.copyOfRange(48, data.size)

        // Authenticate BEFORE decrypting — never touch tampered bytes.
        val expected = hmac(iv, ciphertext)
        if (!MessageDigest.isEqual(tag, expected)) {
            throw MediaCryptoException("HMAC verification failed for chunk (corruption or wrong device)")
        }
        return try {
            aesCipher(Cipher.DECRYPT_MODE, iv).doFinal(ciphertext)
        } catch (e: Exception) {
            throw MediaCryptoException("Decryption failed: ${e.message}", e)
        }
    }

    override fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    // ── internals ──────────────────────────────────────────────────────────

    private fun aesCipher(mode: Int, iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(
                mode,
                requireNotNull(keystore.getKey(AES_ALIAS, null) as? SecretKey) { "AES key missing" },
                IvParameterSpec(iv)
            )
        }

    private fun hmac(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(requireNotNull(keystore.getKey(HMAC_ALIAS, null) as? SecretKey) { "HMAC key missing" })
        mac.update(iv)
        return mac.doFinal(ciphertext)
    }

    /** Provision missing keystore keys. Returns true only when BOTH exist. */
    private fun provision(): Boolean {
        if (keystore.containsAlias(AES_ALIAS) && keystore.containsAlias(HMAC_ALIAS)) return true

        // Single-threaded provisioning: engine serializes via its command channel,
        // but guard anyway against double creation from concurrent restores.
        if (provisioning.compareAndSet(false, true)) {
            try {
                if (!keystore.containsAlias(AES_ALIAS)) {
                    generateKey(AES_ALIAS, KeyProperties.KEY_ALGORITHM_AES, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                }
                if (!keystore.containsAlias(HMAC_ALIAS)) {
                    generateKey(HMAC_ALIAS, KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Keystore provisioning failed", e)
            } finally {
                provisioning.set(false)
            }
        }
        return keystore.containsAlias(AES_ALIAS) && keystore.containsAlias(HMAC_ALIAS)
    }

    private fun generateKey(alias: String, algorithm: String, purposes: Int) {
        val generator = KeyGenerator.getInstance(algorithm, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, purposes)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(false) // we supply deterministic IVs
                .build()
        )
        generator.generateKey()
    }

    private companion object {
        const val TAG = "TvMediaCrypto"
        const val AES_ALIAS = "novelapp_media_cache_aes"
        const val HMAC_ALIAS = "novelapp_media_cache_hmac"
        const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
        const val HMAC_ALGORITHM = "HmacSHA256"

        val keystore: KeyStore by lazy {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        }
        val provisioning = AtomicBoolean(false)
    }
}
