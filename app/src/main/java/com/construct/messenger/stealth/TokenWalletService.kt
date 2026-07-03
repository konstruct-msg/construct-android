package com.construct.messenger.stealth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/** A finalized Privacy Pass token: 32-byte [nonce] (plaintext on the wire) +
 * 32-byte [token] (sealed to the server key before sending). */
data class BlindToken(val nonce: ByteArray, val token: ByteArray)

/**
 * Wallet of finalized Privacy Pass blind tokens — mirrors iOS `TokenWalletService`
 * (deposit, unconditional consume, balance). Tokens are single-use bearer
 * credentials, so they live in Keystore-backed `EncryptedSharedPreferences`
 * (same pattern as [com.construct.messenger.data.local.KeystoreManager]).
 */
@Singleton
class TokenWalletService @Inject constructor(
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

    @get:Synchronized
    val balance: Int
        get() = loadTokens().size

    /** Adds tokens up to [MAX_WALLET_SIZE]; silently drops the overflow. */
    @Synchronized
    fun deposit(tokens: List<BlindToken>) {
        val existing = loadTokens().toMutableList()
        val room = MAX_WALLET_SIZE - existing.size
        if (room <= 0) return
        existing.addAll(tokens.take(room))
        saveTokens(existing)
    }

    /** Removes and returns one token, or null if the wallet is empty. */
    @Synchronized
    fun consumeToken(): BlindToken? {
        val tokens = loadTokens().toMutableList()
        val token = tokens.removeFirstOrNull() ?: return null
        saveTokens(tokens)
        return token
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_TOKENS).apply()
    }

    private fun loadTokens(): List<BlindToken> {
        val raw = prefs.getString(KEY_TOKENS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val nonce = Base64.decode(obj.getString("n"), Base64.NO_WRAP)
                val token = Base64.decode(obj.getString("t"), Base64.NO_WRAP)
                if (nonce.size == 32 && token.size == 32) BlindToken(nonce, token) else null
            }
        }.getOrDefault(emptyList())
    }

    private fun saveTokens(tokens: List<BlindToken>) {
        val arr = JSONArray()
        tokens.forEach {
            arr.put(
                JSONObject()
                    .put("n", Base64.encodeToString(it.nonce, Base64.NO_WRAP))
                    .put("t", Base64.encodeToString(it.token, Base64.NO_WRAP)),
            )
        }
        prefs.edit().putString(KEY_TOKENS, arr.toString()).apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "stealth_token_wallet_prefs"
        const val KEY_TOKENS = "blind_tokens"
        const val MAX_WALLET_SIZE = 40
    }
}
