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
     * device's identity, not a session. The account's address goes with the account id: the next
     * account on this device has another, and a stale one would go into every invite it mints. */
    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_ACCOUNT_ADDRESS)
            .remove(KEY_CONTACT_CARD_SENT_TO)
            .remove(KEY_DEVICE_SETS_LISTED)
            .apply()
        prefs.all.keys.filter { it.startsWith(KEY_PEER_INTAKE_PREFIX) }
            .fold(prefs.edit()) { edit, key -> edit.remove(key) }
            .apply()
    }

    /**
     * This account's address — its Ed25519 recovery public key, derived from the phrase on this
     * device ([com.construct.messenger.invite.AccountAddress]). Never taken from the server.
     * Same storage envelope as [savePrivateKeys].
     */
    fun saveOwnAccountAddress(key: ByteArray) {
        prefs.edit().putString(KEY_ACCOUNT_ADDRESS, Base64.encodeToString(key, Base64.NO_WRAP)).commit()
    }

    /** Contact devices our card has reached. Not secret; kept with the account it belongs to. */
    fun contactCardSentTo(): Set<String> =
        prefs.getStringSet(KEY_CONTACT_CARD_SENT_TO, emptySet()).orEmpty()

    fun markContactCardSent(deviceId: String) {
        prefs.edit().putStringSet(KEY_CONTACT_CARD_SENT_TO, contactCardSentTo() + deviceId.lowercase()).apply()
    }

    /**
     * Accounts whose full device list this device has had from the server at least once. A device
     * that appears for one of them afterwards is a security event
     * (`decisions/a-new-device-is-the-security-event.md`); before it, devices are first sight.
     */
    fun deviceSetsListed(): Set<String> =
        prefs.getStringSet(KEY_DEVICE_SETS_LISTED, emptySet()).orEmpty()

    fun markDeviceSetListed(accountId: String) {
        prefs.edit().putStringSet(KEY_DEVICE_SETS_LISTED, deviceSetsListed() + accountId.lowercase()).apply()
    }

    /**
     * The intake key each contact account handed us in its card — what our envelopes to them
     * present instead of a token. Secret-ish (it lets anyone send to them free), hence here.
     */
    fun peerIntakeKey(accountId: String): ByteArray? =
        prefs.getString(KEY_PEER_INTAKE_PREFIX + accountId.lowercase(), null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun savePeerIntakeKey(accountId: String, key: ByteArray) {
        prefs.edit()
            .putString(KEY_PEER_INTAKE_PREFIX + accountId.lowercase(), Base64.encodeToString(key, Base64.NO_WRAP))
            .apply()
    }

    /** `null` until this device has seen the recovery phrase. */
    fun getOwnAccountAddress(): ByteArray? =
        prefs.getString(KEY_ACCOUNT_ADDRESS, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?.takeIf { it.size == com.construct.messenger.invite.AccountAddress.LENGTH }

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
     * Persists [CryptoManager.exportOneTimePrekeys][com.construct.messenger.crypto.CryptoManager.exportOneTimePrekeys]
     * — the OTPK privates, which [savePrivateKeys] does not include. Same storage envelope.
     * `commit()`, not `apply()`: callers upload the public halves right after, and a private
     * lost to a crash after that upload is exactly the defect this storage exists to prevent.
     */
    fun saveOneTimePrekeys(bytes: ByteArray) {
        prefs.edit().putString(KEY_OTPKS, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
    }

    /** `null` = never persisted (fresh install, or a build before this storage existed). */
    fun getOneTimePrekeys(): ByteArray? =
        prefs.getString(KEY_OTPKS, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun clearOneTimePrekeys() {
        prefs.edit().remove(KEY_OTPKS).commit()
    }

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
        const val KEY_OTPKS = "one_time_prekeys_cfe"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PRIVATE_KEYS = "private_keys_cfe"
        const val KEY_KYBER_PREKEYS = "kyber_prekeys_cfe"
        const val KEY_ACCOUNT_ADDRESS = "account_address"
        const val KEY_CONTACT_CARD_SENT_TO = "contact_card_sent_to"
        const val KEY_DEVICE_SETS_LISTED = "device_sets_listed"
        const val KEY_PEER_INTAKE_PREFIX = "peer_intake_key:"
    }
}
