package com.construct.messenger.recovery

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a phrase is waiting for its copy on this device. */
enum class HeldPhrase {
    NONE,
    HELD,
    /**
     * It was held, and the platform destroyed the key it was sealed with — the screen lock was
     * removed. The key on the server stays and nothing here can show its phrase any more; said
     * plainly instead of looking as if all were well (decision, «Open»).
     */
    LOST,
}

/**
 * What the provisioner and the backup screen need from the vault. An interface so the flow runs
 * in JVM tests, which have no Keystore. **Canon:** iOS `RecoveryPhraseStore`.
 */
interface RecoveryPhraseStore {
    /** A screen lock is set: a phrase can wait behind it. Without one it is shown at once
     * (owner's decision, 2026-10-04). */
    val canHold: Boolean
    fun storePending(phrase: String, account: String): Boolean
    fun pendingPhrase(account: String): String?
    /** Pending → held. False leaves the pending phrase where it is. */
    fun promotePending(account: String): Boolean
    fun held(account: String): HeldPhrase
    fun forgetPending()
    fun forgetHeld()
}

/**
 * Where the recovery phrase waits between being made and being written down.
 * **Canon:** iOS `RecoveryPhraseVault`; `decisions/recovery-key-backup-is-deferred-not-skipped.md` §2.
 *
 * The key is created without asking ([RecoveryKeyProvisioner]) and the copy is made later, from
 * Settings. Whoever reads the phrase can recover the account — revoke every device and take the
 * address — so it is kept as briefly and as tightly as the flow allows, in two stages:
 *
 * - **Pending:** made, not yet accepted by the server. In [KeystoreManager]'s encrypted
 *   preferences, readable without a prompt: the upload may be retried on a later launch. Lasts one
 *   `SetRecoveryKey` round trip in the ordinary case.
 * - **Held:** accepted, copy not made. Sealed with RSA-OAEP to a Keystore key (StrongBox where
 *   there is one) whose private half opens only after the person authenticates — on API 30+ for
 *   each use, by strong biometric or the device credential; on 26–29 within a few seconds of
 *   confirming the device credential. Sealing needs only the public half, so it happens without a
 *   prompt. Deleted the moment the copy is confirmed.
 *
 * Neither leaves the device: the app opts out of backup (`allowBackup="false"`), and Keystore
 * keys never leave it.
 */
@Singleton
class RecoveryPhraseVault @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystore: KeystoreManager,
) : RecoveryPhraseStore {

    override val canHold: Boolean
        get() = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override fun storePending(phrase: String, account: String): Boolean {
        forgetPending()
        return keystore.saveRecoveryPhraseSlot(SLOT_PENDING, phrase, account)
    }

    override fun pendingPhrase(account: String): String? =
        keystore.recoveryPhraseSlot(SLOT_PENDING)?.takeIf { it.second == account }?.first

    override fun promotePending(account: String): Boolean {
        val phrase = pendingPhrase(account) ?: return false
        return try {
            forgetHeld()
            val public = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(generateKey().public.encoded))
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, public, OAEP) }
            val sealed = Base64.encodeToString(cipher.doFinal(phrase.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            if (!keystore.saveRecoveryPhraseSlot(SLOT_HELD, sealed, account)) return false
            forgetPending()
            true
        } catch (e: Exception) {
            // No screen lock any more, Keystore refused: the phrase stays pending, never lost.
            Log.w(TAG, "could not hold the phrase: ${e.javaClass.simpleName}")
            runCatching { keyStore().deleteEntry(ALIAS) }
            false
        }
    }

    override fun held(account: String): HeldPhrase {
        keystore.recoveryPhraseSlot(SLOT_LOST)?.let { if (it.second == account) return HeldPhrase.LOST }
        val slot = keystore.recoveryPhraseSlot(SLOT_HELD) ?: return HeldPhrase.NONE
        if (slot.second != account) return HeldPhrase.NONE
        return if (unlock() == Unlock.Lost) {
            markLost(account)
            HeldPhrase.LOST
        } else {
            HeldPhrase.HELD
        }
    }

    /** How the held phrase can be opened right now. */
    sealed interface Unlock {
        /** API 30+: hand [cipher] to the prompt as its `CryptoObject`, then [open] with it. */
        data class Prompt(val cipher: Cipher) : Unlock
        /** API 26–29: confirm the device credential, then ask [unlock] again. */
        data object Credential : Unlock
        /** The key is gone ([HeldPhrase.LOST]). */
        data object Lost : Unlock
    }

    /** Initialising the cipher shows whether the key still exists, without a prompt. */
    fun unlock(): Unlock = try {
        val key = keyStore().getKey(ALIAS, null) as? PrivateKey
        if (key == null) Unlock.Lost
        else Unlock.Prompt(Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, OAEP) })
    } catch (_: KeyPermanentlyInvalidatedException) {
        Unlock.Lost
    } catch (_: UserNotAuthenticatedException) {
        // API 26–29: the key is there and waits for the credential.
        Unlock.Credential
    }

    /** Opens the held phrase with an authenticated [cipher]. Null if it does not belong to
     * [account] or the authentication did not take. [markLost] runs on [held]'s next look. */
    fun open(cipher: Cipher, account: String): String? {
        val slot = keystore.recoveryPhraseSlot(SLOT_HELD)?.takeIf { it.second == account } ?: return null
        return try {
            String(cipher.doFinal(Base64.decode(slot.first, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "held phrase did not open: ${e.javaClass.simpleName}")
            null
        }
    }

    override fun forgetPending() = keystore.deleteRecoveryPhraseSlot(SLOT_PENDING)

    override fun forgetHeld() {
        keystore.deleteRecoveryPhraseSlot(SLOT_HELD)
        keystore.deleteRecoveryPhraseSlot(SLOT_LOST)
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun markLost(account: String) {
        Log.w(TAG, "the held phrase's key is gone — the screen lock was removed")
        keystore.deleteRecoveryPhraseSlot(SLOT_HELD)
        runCatching { keyStore().deleteEntry(ALIAS) }
        keystore.saveRecoveryPhraseSlot(SLOT_LOST, "1", account)
    }

    private fun generateKey(): java.security.KeyPair {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            // A new fingerprint must not make the phrase unreadable (iOS: not `.biometryCurrentSet`).
            .setInvalidatedByBiometricEnrollment(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(CREDENTIAL_VALIDITY_SECONDS)
                }
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
            }
            .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
        return try {
            generator.initialize(spec(strongBox = true))
            generator.generateKeyPair()
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && e is StrongBoxUnavailableException ||
                e.cause is StrongBoxUnavailableException
            ) {
                generator.initialize(spec(strongBox = false))
                generator.generateKeyPair()
            } else {
                throw e
            }
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val TAG = "RecoveryVault"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "construct.recovery.phrase.held"
        const val SLOT_PENDING = "pending"
        const val SLOT_HELD = "held"
        const val SLOT_LOST = "lost"
        const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        /** API 26–29: how long after confirming the credential the key opens. */
        const val CREDENTIAL_VALIDITY_SECONDS = 15

        /** SHA-256 for OAEP, SHA-1 for MGF1: the pair every Keystore implementation accepts. */
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    }
}
