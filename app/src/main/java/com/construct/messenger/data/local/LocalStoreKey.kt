package com.construct.messenger.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The key the core's store opens under (TODO 136): 256 random bits, kept on disk only wrapped by
 * a key that never leaves this device's Keystore. **Canon:** iOS `LocalStoreKey` — there the 32
 * bytes sit in the Keychain, this device only, readable after the first unlock; a Keystore key
 * with no user authentication is the same: usable once the phone is unlocked after boot, never
 * exported, gone with the app's data.
 *
 * **A key that cannot be read is never replaced.** Minting a new one over a wrapped key the
 * Keystore would not open orphans the whole store — the 2026-08-09 lesson of iOS
 * `DeviceKeyAvailability`. Only a key that is not there at all is minted; an existing store then
 * reports `WrongKey`, and that is a recovery screen, not a fresh empty store.
 */
class LocalStoreKey(
    private val file: File,
    private val wrap: KeyWrap,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Seals the store key for the disk; the Keystore in the app ([KeystoreKeyWrap]). */
    interface KeyWrap {
        fun wrap(key: ByteArray): ByteArray

        fun unwrap(wrapped: ByteArray): ByteArray

        /** Deletes the wrapping key; nothing it wrapped opens again. */
        fun forget()
    }

    /**
     * The key, minted on first use; the caller zeroes it once the store is open. Null when it is
     * held but would not open — and then no new key is made.
     */
    fun current(): ByteArray? {
        if (!file.exists()) return mint()
        val key = try {
            wrap.unwrap(file.readBytes())
        } catch (e: Exception) {
            Log.e(TAG, "the store key would not unwrap — not replacing it", e)
            return null
        }
        if (key.size != SIZE) {
            Log.e(TAG, "the store key is ${key.size} bytes — not replacing it")
            key.fill(0)
            return null
        }
        return key
    }

    /** The wrapping key first, then the file: whatever the second step leaves can no longer open. */
    fun destroy() {
        wrap.forget()
        file.delete()
    }

    private fun mint(): ByteArray? {
        val key = ByteArray(SIZE).also(random::nextBytes)
        return try {
            // A wrapping key left without its file wrapped nothing that is still here.
            wrap.forget()
            val wrapped = wrap.wrap(key)
            file.parentFile?.mkdirs()
            val staged = File(file.path + ".new")
            staged.writeBytes(wrapped)
            check(staged.renameTo(file)) { "could not move the wrapped key into place" }
            key
        } catch (e: Exception) {
            Log.e(TAG, "could not keep a new store key", e)
            key.fill(0)
            null
        }
    }

    companion object {
        const val SIZE = 32
        private const val TAG = "LocalStoreKey"
    }
}

/**
 * AES-256-GCM under a Keystore key: `iv(12) ‖ ciphertext ‖ tag(16)`, the alias as associated data.
 * No user authentication — the store is read by the foreground service while the screen is
 * locked — and not StrongBox: one unwrap per start does not need it, and StrongBox key generation
 * fails outright on some devices.
 */
class KeystoreKeyWrap(private val alias: String = ALIAS) : LocalStoreKey.KeyWrap {
    override fun wrap(key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, existing() ?: generate())
            updateAAD(alias.toByteArray())
        }
        return cipher.iv + cipher.doFinal(key)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val secret = existing() ?: error("no Keystore key $alias")
        require(wrapped.size > IV_SIZE) { "the wrapped key is ${wrapped.size} bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, wrapped, 0, IV_SIZE))
            updateAAD(alias.toByteArray())
        }
        return cipher.doFinal(wrapped, IV_SIZE, wrapped.size - IV_SIZE)
    }

    override fun forget() {
        keyStore().deleteEntry(alias)
    }

    private fun existing(): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    private fun generate(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    companion object {
        const val ALIAS = "construct.localStore.key.v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
