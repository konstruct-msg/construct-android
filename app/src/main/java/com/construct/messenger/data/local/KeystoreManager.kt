package com.construct.messenger.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists auth tokens in Android Keystore-backed `EncryptedSharedPreferences`.
 *
 * **Canon:** `docs/IMPLEMENTATION_PLAN.md` → Phase 2.2; field set matches iOS
 * `KeychainManager` (`saveSessionToken`/`saveRefreshToken`/`saveUserID`/`saveDeviceID`).
 * Device crypto keys and per-contact session bytes are handled elsewhere
 * ([com.construct.messenger.crypto.CryptoManager], [com.construct.messenger.service.SessionManager])
 * — out of scope here.
 */
@Singleton
class KeystoreManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Saves the token set returned by [RegisterUseCase][com.construct.messenger.domain.usecase.RegisterUseCase]/[LoginUseCase][com.construct.messenger.domain.usecase.LoginUseCase], plus the device id used to obtain them. */
    fun saveTokens(tokens: AuthTokensResponse, deviceId: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .putString(KEY_USER_ID, tokens.userId)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)
    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)
    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    fun saveAccessToken(token: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
    }

    fun saveRefreshToken(token: String) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    /** Persists a recovered user id (last-resort `sub` extraction, `docs/TOKEN_AUTH.md` §3.4). */
    fun saveUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    /** Clears tokens on logout. Device id and private keys are kept — they identify the
     * device's identity, not a session. */
    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .apply()
    }

    /**
     * Persists [CryptoManager.exportPrivateKeys][com.construct.messenger.crypto.CryptoManager.exportPrivateKeys]
     * output (CFE binary) so [LoginUseCase][com.construct.messenger.domain.usecase.LoginUseCase]
     * can restore the same identity on a later app launch via `CryptoManager.loadOrCreate(bytes)`.
     *
     * Base64 here is **not** the JSON/stringification anti-pattern the CFE binary rule
     * forbids — `SharedPreferences` (even encrypted) only has a `String` value type, so this
     * is purely a storage-transport envelope. The bytes themselves are never parsed/re-encoded;
     * they round-trip through `loadOrCreate()` exactly as `exportPrivateKeys()` produced them.
     */
    fun savePrivateKeys(bytes: ByteArray): Boolean =
        // commit(): the record also holds the hybrid identity key once it exists, and what that key
        // signs goes to the server right after it is saved.
        prefs.edit().putString(KEY_PRIVATE_KEYS, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()

    fun getPrivateKeys(): ByteArray? =
        prefs.getString(KEY_PRIVATE_KEYS, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /**
     * Persists the core's Kyber prekey store ([CryptoManager.exportKyberPrekeys]
     * [com.construct.messenger.crypto.CryptoManager.exportKyberPrekeys]) — ML-KEM-1024 seeds, as
     * secret as the private keys beside them. Written with `commit()`, not `apply()`: the caller
     * uploads the public halves right after, and a key the server may serve must never exist only
     * in memory. Returns whether the write reached disk.
     */
    fun saveKyberPrekeys(bytes: ByteArray): Boolean =
        prefs.edit().putString(KEY_KYBER_PREKEYS, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()

    fun getKyberPrekeys(): ByteArray? =
        prefs.getString(KEY_KYBER_PREKEYS, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** With the identity it belongs to: a new identity must not inherit the old one's Kyber keys. */
    fun deleteKyberPrekeys() {
        prefs.edit().remove(KEY_KYBER_PREKEYS).commit()
    }

    private companion object {
        const val PREFS_FILE_NAME = "construct_auth_prefs"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PRIVATE_KEYS = "private_keys_cfe"
        const val KEY_KYBER_PREKEYS = "kyber_prekeys_cfe"
    }
}
