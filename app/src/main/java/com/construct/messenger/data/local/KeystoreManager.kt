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
        val edit = prefs.edit()
        // An address belongs to one account. Tokens for another one, without a sign-out between,
        // must not inherit it: it would go into every card and invite (construct-docs TODO 109).
        if (prefs.getString(KEY_USER_ID, null).let { it != null && it != tokens.userId }) {
            edit.remove(KEY_ACCOUNT_ADDRESS)
        }
        edit
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .putString(KEY_USER_ID, tokens.userId)
            .putString(KEY_DEVICE_ID, deviceId)
            .putLong(KEY_ACCESS_TOKEN_EXPIRES_AT, tokens.expiresAt)
            .apply()
    }

    /**
     * The veil-front capability for [relayAddress] — base64 of the blob `IssueVeilCapability`
     * returned — and its expiry in Unix seconds. Bearer auth material for the relay: kept with the
     * tokens, wiped with them.
     */
    fun veilCapability(relayAddress: String): Pair<String, Long>? {
        val blob = prefs.getString(KEY_VEIL_CAPABILITY_PREFIX + relayAddress, null) ?: return null
        val notAfter = prefs.getLong(KEY_VEIL_CAPABILITY_EXP_PREFIX + relayAddress, 0L)
        return blob to notAfter
    }

    fun saveVeilCapability(relayAddress: String, blobB64: String, notAfter: Long) {
        prefs.edit()
            .putString(KEY_VEIL_CAPABILITY_PREFIX + relayAddress, blobB64)
            .putLong(KEY_VEIL_CAPABILITY_EXP_PREFIX + relayAddress, notAfter)
            .apply()
    }

    /** The key-bound (B1) capability for [relayAddress], as [veilCapability]; kept beside the bearer one. */
    fun veilKeyBoundCapability(relayAddress: String): Pair<String, Long>? {
        val blob = prefs.getString(KEY_VEIL_CAPABILITY_V2_PREFIX + relayAddress, null) ?: return null
        return blob to prefs.getLong(KEY_VEIL_CAPABILITY_V2_EXP_PREFIX + relayAddress, 0L)
    }

    fun saveVeilKeyBoundCapability(relayAddress: String, blobB64: String, notAfter: Long) {
        prefs.edit()
            .putString(KEY_VEIL_CAPABILITY_V2_PREFIX + relayAddress, blobB64)
            .putLong(KEY_VEIL_CAPABILITY_V2_EXP_PREFIX + relayAddress, notAfter)
            .apply()
    }

    /**
     * The VEIL fronts learned from signed config links, as [com.construct.messenger.veil.VeilFrontStore]
     * encodes them. Not wiped by [clearTokens]: a front is the device's way out, not the account's.
     */
    fun veilLearnedFronts(): String? = prefs.getString(KEY_VEIL_LEARNED_FRONTS, null)

    fun saveVeilLearnedFronts(encoded: String) {
        prefs.edit().putString(KEY_VEIL_LEARNED_FRONTS, encoded).commit()
    }

    /** The 32-byte Ed25519 seed of this device's veil access key ([com.construct.messenger.veil.VeilAccessKey]). */
    fun veilAccessSeed(): ByteArray? =
        prefs.getString(KEY_VEIL_ACCESS_SEED, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun saveVeilAccessSeed(seed: ByteArray) {
        prefs.edit().putString(KEY_VEIL_ACCESS_SEED, Base64.encodeToString(seed, Base64.NO_WRAP)).commit()
    }

    /**
     * When the access token stops being accepted, in Unix seconds, as the server stated it with
     * the token — never parsed out of it (`docs/TOKEN_AUTH.md` §3.4). Null for a token saved
     * before this was kept: the caller refreshes once, and the answer carries it.
     */
    fun getAccessTokenExpiresAt(): Long? =
        prefs.getLong(KEY_ACCESS_TOKEN_EXPIRES_AT, 0L).takeIf { it > 0 }

    fun saveAccessTokenExpiresAt(expiresAt: Long) {
        prefs.edit().putLong(KEY_ACCESS_TOKEN_EXPIRES_AT, expiresAt).apply()
    }

    /**
     * The app-lock PIN record: PBKDF2 hash, salt, hash version and PIN length (iOS keeps the same
     * four in the Keychain). Survives logout on purpose — it locks the app, not the account.
     */
    fun pinRecord(): PinRecord? {
        val hash = prefs.getString(KEY_PIN_HASH, null) ?: return null
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return null
        return PinRecord(
            hash = Base64.decode(hash, Base64.NO_WRAP),
            salt = Base64.decode(salt, Base64.NO_WRAP),
            version = prefs.getInt(KEY_PIN_VERSION, 0),
            length = prefs.getInt(KEY_PIN_LENGTH, 6),
        )
    }

    fun savePinRecord(record: PinRecord) {
        prefs.edit()
            .putString(KEY_PIN_HASH, Base64.encodeToString(record.hash, Base64.NO_WRAP))
            .putString(KEY_PIN_SALT, Base64.encodeToString(record.salt, Base64.NO_WRAP))
            .putInt(KEY_PIN_VERSION, record.version)
            .putInt(KEY_PIN_LENGTH, record.length)
            .commit()
    }

    fun clearPinRecord() {
        prefs.edit()
            .remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).remove(KEY_PIN_VERSION).remove(KEY_PIN_LENGTH)
            .commit()
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
            .remove(KEY_ACCESS_TOKEN_EXPIRES_AT)
            .remove(KEY_USER_ID)
            .remove(KEY_ACCOUNT_ADDRESS)
            .remove(KEY_CONTACT_CARD_SENT_TO)
            .remove(KEY_CONTACT_CARD_SENT_TO_V1)
            .remove(KEY_OWN_INTAKE)
            .remove(KEY_DEVICE_SETS_LISTED)
            .remove(KEY_DEVICE_METADATA_PUBLISHED_FOR)
            .remove(KEY_VEIL_ACCESS_SEED)
            .apply()
        val wiped = listOf(
            KEY_PEER_INTAKE_PREFIX, KEY_VEIL_CAPABILITY_PREFIX, KEY_VEIL_CAPABILITY_EXP_PREFIX,
            KEY_VEIL_CAPABILITY_V2_PREFIX, KEY_VEIL_CAPABILITY_V2_EXP_PREFIX,
        )
        prefs.all.keys.filter { key -> wiped.any { key.startsWith(it) } }
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

    /**
     * A slot of [com.construct.messenger.recovery.RecoveryPhraseVault]: the value and the account
     * it belongs to. Not cleared by [clearTokens] — an ended session is not the account leaving
     * this device, and a phrase dropped then could be the only one; sign-out wipes it explicitly.
     * Written with `commit()`: the pending phrase must be on disk before its key is sent.
     */
    fun recoveryPhraseSlot(slot: String): Pair<String, String>? {
        val value = prefs.getString(KEY_RECOVERY_PREFIX + slot, null) ?: return null
        val account = prefs.getString(KEY_RECOVERY_PREFIX + slot + KEY_RECOVERY_ACCOUNT_SUFFIX, null) ?: return null
        return value to account
    }

    fun saveRecoveryPhraseSlot(slot: String, value: String, account: String): Boolean =
        prefs.edit()
            .putString(KEY_RECOVERY_PREFIX + slot, value)
            .putString(KEY_RECOVERY_PREFIX + slot + KEY_RECOVERY_ACCOUNT_SUFFIX, account)
            .commit()

    fun deleteRecoveryPhraseSlot(slot: String) {
        prefs.edit()
            .remove(KEY_RECOVERY_PREFIX + slot)
            .remove(KEY_RECOVERY_PREFIX + slot + KEY_RECOVERY_ACCOUNT_SUFFIX)
            .commit()
    }

    /** Forget the stored address: the server names another key for this account
     * ([com.construct.messenger.invite.OwnAccountAddress]). */
    fun deleteOwnAccountAddress() {
        prefs.edit().remove(KEY_ACCOUNT_ADDRESS).commit()
    }

    /**
     * Contact devices our card has reached. Not secret; kept with the account it belongs to.
     * `v2` since 2026-09-29: the card carries our intake key now, and a device that got the
     * address-only card under v1 has not got it — every contact gets the card once more, lazily.
     */
    fun contactCardSentTo(): Set<String> {
        if (prefs.contains(KEY_CONTACT_CARD_SENT_TO_V1)) prefs.edit().remove(KEY_CONTACT_CARD_SENT_TO_V1).apply()
        return prefs.getStringSet(KEY_CONTACT_CARD_SENT_TO, emptySet()).orEmpty()
    }

    fun markContactCardSent(deviceId: String) {
        prefs.edit().putStringSet(KEY_CONTACT_CARD_SENT_TO, contactCardSentTo() + deviceId.lowercase()).apply()
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

    /** This device's intake key for our account, or null before one is minted. */
    /**
     * The device set this device's description was last sealed for (iOS `publishedForKey`).
     * Leaves with the account: a stale set after a re-registration would match the new account's
     * and keep it from publishing at all.
     */
    fun deviceMetadataPublishedFor(): Set<String> =
        prefs.getStringSet(KEY_DEVICE_METADATA_PUBLISHED_FOR, null).orEmpty().toSet()

    fun saveDeviceMetadataPublishedFor(deviceIds: Set<String>) {
        prefs.edit().putStringSet(KEY_DEVICE_METADATA_PUBLISHED_FOR, deviceIds.toSet()).apply()
    }

    fun ownIntakeKey(): ByteArray? =
        prefs.getString(KEY_OWN_INTAKE, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun saveOwnIntakeKey(key: ByteArray) {
        prefs.edit().putString(KEY_OWN_INTAKE, Base64.encodeToString(key, Base64.NO_WRAP)).commit()
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
        const val KEY_ACCESS_TOKEN_EXPIRES_AT = "access_token_expires_at"
        const val KEY_VEIL_CAPABILITY_PREFIX = "veil_capability:"
        const val KEY_VEIL_CAPABILITY_EXP_PREFIX = "veil_capability_exp:"
        const val KEY_VEIL_CAPABILITY_V2_PREFIX = "veil_capability_v2:"
        const val KEY_VEIL_CAPABILITY_V2_EXP_PREFIX = "veil_capability_v2_exp:"
        const val KEY_VEIL_ACCESS_SEED = "veil_access_seed"
        const val KEY_VEIL_LEARNED_FRONTS = "veil_learned_fronts"
        const val KEY_PIN_HASH = "app_pin_hash"
        const val KEY_PIN_SALT = "app_pin_salt"
        const val KEY_PIN_VERSION = "app_pin_hash_version"
        const val KEY_PIN_LENGTH = "app_pin_length"
        const val KEY_USER_ID = "user_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PRIVATE_KEYS = "private_keys_cfe"
        const val KEY_KYBER_PREKEYS = "kyber_prekeys_cfe"
        const val KEY_ACCOUNT_ADDRESS = "account_address"
        const val KEY_RECOVERY_PREFIX = "recovery_phrase."
        const val KEY_RECOVERY_ACCOUNT_SUFFIX = ".account"
        const val KEY_CONTACT_CARD_SENT_TO = "contact_card_sent_to.v2"
        const val KEY_CONTACT_CARD_SENT_TO_V1 = "contact_card_sent_to"
        const val KEY_OWN_INTAKE = "own_intake_key"
        /** Nothing writes it since the new-device alarm went (2026-09-30); still cleared for installs that did. */
        const val KEY_DEVICE_SETS_LISTED = "device_sets_listed"
        const val KEY_DEVICE_METADATA_PUBLISHED_FOR = "device_metadata_published_for"
        const val KEY_PEER_INTAKE_PREFIX = "peer_intake_key:"
    }
}

/** See [KeystoreManager.pinRecord]. */
class PinRecord(val hash: ByteArray, val salt: ByteArray, val version: Int, val length: Int)
