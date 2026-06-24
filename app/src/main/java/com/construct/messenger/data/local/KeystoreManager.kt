package com.construct.messenger.data.local

import android.content.Context
import android.content.SharedPreferences
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

    /** Clears tokens on logout. Device id is kept — it identifies hardware, not a session. */
    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "construct_auth_prefs"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_DEVICE_ID = "device_id"
    }
}
