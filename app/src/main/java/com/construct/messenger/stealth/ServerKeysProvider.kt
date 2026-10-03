package com.construct.messenger.stealth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.construct.messenger.crypto.ServerTrust
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches and caches the two server keys stealth depends on, from
 * `https://<host>/.well-known/construct-server`:
 *
 *  - `token_encryption_key` — X25519 public key tokens are sealed to
 *    (`ppSealTokenBytes`), so relay operators can't read spent tokens;
 *  - `bundle_verification_key` / `bundle_signing_key` — Ed25519 key that
 *    verifies SenderCertificates (and prekey bundles / KT tree heads);
 *  - `server_trust.delegations` — the server's hybrid signing keys, each signed by the offline
 *    root. Public data: the core keeps only those a root pinned in its build signed
 *    (`decisions/server-keys-rooted-offline-and-hybrid.md`). **Canon:** iOS
 *    `ServerKeyManager.cachedServerDelegations`.
 *
 * Mirrors iOS `ServerKeyManager` + the bundle-key half of `VeilCertFetcher`
 * (including the cache-before-relay-guard fix from 2026-07-03). Cache TTL 24h —
 * both keys derive from server secrets that rotate rarely.
 */
@Singleton
class ServerKeysProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    /** Cached X25519 token-encryption key, or null if never fetched. */
    fun tokenEncryptionKey(): ByteArray? = getKey(KEY_TOKEN_ENC)

    /** Cached Ed25519 bundle verification key, or null if never fetched. */
    fun bundleVerificationKey(): ByteArray? = getKey(KEY_BUNDLE_VERIFY)

    /** Delegations of the server's hybrid keys as last served; empty before the first. */
    fun serverDelegations(): List<ByteArray> {
        val stored = prefs.getString(KEY_DELEGATIONS, null) ?: return emptyList()
        return runCatching { ServerDelegations.decode(JSONArray(stored)) }.getOrDefault(emptyList())
    }

    /** Everything a certificate is judged by, for the core. */
    fun trust(): ServerTrust = ServerTrust(listOfNotNull(bundleVerificationKey()), serverDelegations())

    /** Refresh the cache if stale ([CACHE_TTL_MS]) or empty. Failures keep the cache. */
    suspend fun prefetch() {
        val age = System.currentTimeMillis() - prefs.getLong(KEY_FETCHED_AT, 0L)
        if (age < CACHE_TTL_MS && tokenEncryptionKey() != null && bundleVerificationKey() != null) {
            return
        }
        fetchAndCache()
    }

    private suspend fun fetchAndCache() = withContext(Dispatchers.IO) {
        try {
            val conn = URL(WELL_KNOWN_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            if (conn.responseCode != 200) return@withContext

            val json = JSONObject(body)
            // token_encryption_key: root or nested under "server" (gateway response shape).
            val tokenKeyB64 = json.optString("token_encryption_key").ifEmpty {
                json.optJSONObject("server")?.optString("token_encryption_key").orEmpty()
            }
            // bundle key: both field names occur in the wild.
            val bundleKeyB64 = json.optString("bundle_verification_key").ifEmpty {
                json.optString("bundle_signing_key")
            }

            val editor = prefs.edit()
            decode32(tokenKeyB64)?.let {
                editor.putString(KEY_TOKEN_ENC, Base64.encodeToString(it, Base64.NO_WRAP))
            }
            decode32(bundleKeyB64)?.let {
                editor.putString(KEY_BUNDLE_VERIFY, Base64.encodeToString(it, Base64.NO_WRAP))
            }
            // Replaced whole when served: the gateway serves every delegation a client may still
            // need. Kept when absent — a server that does not serve them yet says nothing.
            ServerDelegations.served(json)?.let { list ->
                editor.putString(KEY_DELEGATIONS, list.toString())
                Log.i(TAG, "${ServerDelegations.decode(list).size} server key delegation(s) cached")
            }
            editor.putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply()
            Log.i(TAG, "server keys cached from well-known")
        } catch (e: Exception) {
            Log.w(TAG, "well-known fetch failed — keeping cached keys", e)
        }
    }

    private fun getKey(prefKey: String): ByteArray? =
        prefs.getString(prefKey, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    private fun decode32(b64: String): ByteArray? {
        if (b64.isEmpty()) return null
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        return bytes.takeIf { it.size == 32 }
    }

    private companion object {
        const val TAG = "ServerKeysProvider"

        // Same host as GrpcClient — the dynamic well-known served by the gateway.
        const val WELL_KNOWN_URL = "https://ams.konstruct.cc/.well-known/construct-server"

        const val PREFS_FILE_NAME = "stealth_server_keys_prefs"
        const val KEY_TOKEN_ENC = "token_encryption_key"
        const val KEY_BUNDLE_VERIFY = "bundle_verification_key"
        const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_DELEGATIONS = "trust_delegations"
        const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

/** `server_trust.delegations` in the well-known document: base64, one per delegated key. */
internal object ServerDelegations {
    /** The list as served, or null when the document has none — then the cache is kept. */
    fun served(json: JSONObject): JSONArray? = json.optJSONObject("server_trust")?.optJSONArray("delegations")

    /** Each entry that decodes; one that does not is skipped, the core judges the rest. */
    fun decode(list: JSONArray): List<ByteArray> = (0 until list.length()).mapNotNull { i ->
        list.optString(i).takeIf { it.isNotEmpty() }
            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
    }
}
