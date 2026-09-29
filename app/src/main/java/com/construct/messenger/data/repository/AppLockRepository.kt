package com.construct.messenger.data.repository

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.PinRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** iOS `LockDelay`: how long the app may sit in the background before the PIN is asked again. */
enum class LockDelay(val seconds: Int) {
    IMMEDIATE(0), THIRTY_SECONDS(30), ONE_MINUTE(60), FIVE_MINUTES(300), TEN_MINUTES(600);

    companion object {
        fun from(seconds: Int) = entries.firstOrNull { it.seconds == seconds } ?: IMMEDIATE
    }
}

data class AppLockState(
    val pinEnabled: Boolean = false,
    /** False while the lock screen is up. Always true without a PIN. */
    val unlocked: Boolean = true,
    val biometricEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    val lockDelay: LockDelay = LockDelay.IMMEDIATE,
    /** Digits the lock screen expects (iOS `pinLength`). */
    val pinLength: Int = PIN_LENGTH,
) {
    val requiresUnlock: Boolean get() = pinEnabled && !unlocked
}

/** New PINs are six digits, as on iOS (`PinDotsField(length: 6)`). */
const val PIN_LENGTH = 6

/**
 * The app lock: a PIN, optionally biometrics, and when to ask again.
 *
 * **Canon:** iOS `SecurityViewModel` + `SecurityGateView`. The PIN is stored as iOS stores it —
 * PBKDF2-HMAC-SHA256, 100 000 iterations, a 32-byte random salt, hash version 2 — in the
 * Keystore-backed preferences ([KeystoreManager]), and compared in constant time.
 *
 * **Invariant (iOS):** a PIN is never reset in place to get back into the same account; that
 * would make the lock cosmetic. The only way past a forgotten PIN is the lock screen's
 * destructive reset, which erases everything this app holds on the device.
 */
@Singleton
class AppLockRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keystore: KeystoreManager,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(initialState())
    val lock: StateFlow<AppLockState> = state.asStateFlow()

    /** [SystemClock.elapsedRealtime] of the last move to the background; null in the foreground. */
    private var backgroundedAt: Long? = null

    private fun initialState(): AppLockState {
        val record = keystore.pinRecord()
        val biometricAvailable = biometricAvailable()
        return AppLockState(
            pinEnabled = record != null,
            // A cold start with a PIN starts locked.
            unlocked = record == null,
            biometricEnabled = record != null && biometricAvailable && prefs.getBoolean(KEY_BIOMETRIC, false),
            biometricAvailable = biometricAvailable,
            lockDelay = LockDelay.from(prefs.getInt(KEY_LOCK_DELAY, 0)),
            pinLength = record?.length ?: PIN_LENGTH,
        )
    }

    fun verify(pin: String): Boolean {
        val record = keystore.pinRecord() ?: return false
        if (record.version != HASH_VERSION) return false
        return MessageDigest.isEqual(hash(pin, record.salt), record.hash)
    }

    /** Sets or replaces the PIN, and leaves the app unlocked. */
    fun setPin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        keystore.savePinRecord(PinRecord(hash(pin, salt), salt, HASH_VERSION, pin.length))
        state.update { it.copy(pinEnabled = true, unlocked = true, pinLength = pin.length) }
    }

    /** Only after the current PIN was entered (the Disable screen asks for it). */
    fun disablePin() {
        keystore.clearPinRecord()
        prefs.edit().remove(KEY_BIOMETRIC).apply()
        state.update { it.copy(pinEnabled = false, unlocked = true, biometricEnabled = false, pinLength = PIN_LENGTH) }
    }

    fun unlock() = state.update { it.copy(unlocked = true) }

    /**
     * The lock screen's escape hatch (iOS `wipeSecurityState` + `wipeAndReregister`): erases
     * everything this app holds on the device — keys, messages, tokens, the PIN — as the system's
     * "Clear storage" does, and ends the process. The next start is a fresh onboarding; the
     * identity comes back only through the recovery phrase. Nothing is sent to the server.
     */
    fun eraseDevice() {
        context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
    }

    fun setBiometricEnabled(enabled: Boolean) {
        val applied = enabled && state.value.pinEnabled && state.value.biometricAvailable
        prefs.edit().putBoolean(KEY_BIOMETRIC, applied).apply()
        state.update { it.copy(biometricEnabled = applied) }
    }

    fun setLockDelay(delay: LockDelay) {
        prefs.edit().putInt(KEY_LOCK_DELAY, delay.seconds).apply()
        state.update { it.copy(lockDelay = delay) }
    }

    /** The app left the foreground: lock now, or remember when, per [LockDelay]. */
    fun onBackground() {
        if (!state.value.pinEnabled) return
        backgroundedAt = SystemClock.elapsedRealtime()
        if (state.value.lockDelay == LockDelay.IMMEDIATE) state.update { it.copy(unlocked = false) }
    }

    /** The app is back: lock if it was away longer than the delay. Re-reads biometrics. */
    fun onForeground() {
        val available = biometricAvailable()
        state.update {
            it.copy(biometricAvailable = available, biometricEnabled = it.biometricEnabled && available)
        }
        val since = backgroundedAt
        backgroundedAt = null
        val current = state.value
        if (!current.pinEnabled || !current.unlocked || since == null) return
        val elapsedSec = (SystemClock.elapsedRealtime() - since) / 1000
        if (elapsedSec >= current.lockDelay.seconds) state.update { it.copy(unlocked = false) }
    }

    private fun biometricAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun hash(pin: String, salt: ByteArray): ByteArray = pbkdf2Sha256(pin, salt, ITERATIONS)

    private companion object {
        const val PREFS = "app_lock_prefs"
        const val KEY_BIOMETRIC = "security.useBiometrics"
        const val KEY_LOCK_DELAY = "security.lockDelay"
        const val HASH_VERSION = 2
        const val ITERATIONS = 100_000
        const val SALT_BYTES = 32
    }
}

/**
 * PBKDF2-HMAC-SHA256 to 32 bytes — iOS `CCKeyDerivationPBKDF(kCCPBKDF2, kCCPRFHmacAlgSHA256)`.
 * The JDK encodes the password as UTF-8, as iOS does with `pin.data(using: .utf8)`.
 */
internal fun pbkdf2Sha256(password: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}
