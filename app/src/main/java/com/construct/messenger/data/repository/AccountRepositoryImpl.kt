package com.construct.messenger.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.IdentityFingerprint
import dagger.hilt.android.qualifiers.ApplicationContext
import io.grpc.Status
import io.grpc.StatusException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import shared.proto.services.v1.UserServiceOuterClass.CheckUsernameAvailabilityRequest
import shared.proto.services.v1.UserServiceOuterClass.GetUserProfileRequest
import shared.proto.services.v1.UserServiceOuterClass.SetDiscoverableRequest
import shared.proto.services.v1.UserServiceOuterClass.UpdateUserProfileRequest

@Singleton
class AccountRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val keystoreManager: KeystoreManager,
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
) : AccountRepository {

    // Keyed by account id, so a new account on this install never inherits the last one's
    // values and sign-out has nothing to wipe. Not secret: the alias is public by design.
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val state = MutableStateFlow<OwnAccount?>(null)
    override val account: StateFlow<OwnAccount?> = state.asStateFlow()

    override suspend fun refresh() {
        val userId = keystoreManager.getUserId()
        if (userId == null) {
            state.value = null
            return
        }
        // Cached first: Settings should not show an empty identity while the network answers.
        state.value = OwnAccount(
            userId = userId,
            displayName = prefs.getString(key(KEY_DISPLAY_NAME, userId), null)
                ?.takeIf { it.isNotBlank() } ?: DisplayNameGenerator.generate(userId),
            username = prefs.getString(key(KEY_USERNAME, userId), null).orEmpty(),
            // The server does not return this flag (iOS keeps it in UserDefaults for the same
            // reason): what this device last set is the only record.
            discoverable = prefs.getBoolean(key(KEY_DISCOVERABLE, userId), false),
            fingerprint = cryptoManager.currentIdentityPublic()?.let(IdentityFingerprint::short),
        )
        try {
            val profile = grpcClient.user.getUserProfile(
                GetUserProfileRequest.newBuilder().setUserId(userId).build(),
            ).profile
            // The server keeps only an HMAC of the alias and answers with neither field, so an
            // empty answer means "not told", never "cleared" — as iOS, which keeps its own copy
            // when the profile has none. Writing it through erased the alias after every save.
            val username = profile.username.takeIf { profile.hasUsername() && it.isNotBlank() }
            val displayName = profile.displayName.takeIf { profile.hasDisplayName() && it.isNotBlank() }
            prefs.edit().apply {
                username?.let { putString(key(KEY_USERNAME, userId), it) }
                displayName?.let { putString(key(KEY_DISPLAY_NAME, userId), it) }
            }.apply()
            state.update { current ->
                current?.copy(
                    username = username ?: current.username,
                    displayName = displayName ?: current.displayName,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "profile refresh failed; showing cached values", e)
        }
    }

    override suspend fun changeUsername(raw: String): UsernameChange {
        val userId = keystoreManager.getUserId() ?: return UsernameChange.Failed
        val username = raw.trim().removePrefix("@").lowercase()
        if (username.length !in AccountRepository.USERNAME_LENGTH) return UsernameChange.InvalidLength
        return try {
            val availability = grpcClient.user.checkUsernameAvailability(
                CheckUsernameAvailabilityRequest.newBuilder().setUsername(username).build(),
            )
            // "taken" cannot tell whose: the alias may be ours already (set on another device,
            // or before this install). The update is the one that knows — it refuses only a name
            // held by someone else, with ALREADY_EXISTS.
            if (!availability.available && availability.reason != REASON_TAKEN) {
                return UsernameChange.Unavailable(if (availability.hasReason()) availability.reason else null)
            }
            val saved = grpcClient.user.updateUserProfile(
                UpdateUserProfileRequest.newBuilder().setUserId(userId).setUsername(username).build(),
            ).profile.let { if (it.hasUsername()) it.username else username }
            prefs.edit().putString(key(KEY_USERNAME, userId), saved).apply()
            state.update { it?.copy(username = saved) }
            UsernameChange.Saved(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StatusException) {
            if (e.status.code == Status.Code.ALREADY_EXISTS) return UsernameChange.Unavailable(REASON_TAKEN)
            Log.w(TAG, "username change failed", e)
            UsernameChange.Failed
        } catch (e: Exception) {
            Log.w(TAG, "username change failed", e)
            UsernameChange.Failed
        }
    }

    override suspend fun setDiscoverable(enabled: Boolean): Boolean {
        val current = state.value ?: return false
        // A searchable account with no alias has nothing to be found by.
        if (enabled && current.username.isEmpty()) return false
        return try {
            val applied = grpcClient.user.setDiscoverable(
                SetDiscoverableRequest.newBuilder().setDiscoverable(enabled).build(),
            ).discoverable
            prefs.edit().putBoolean(key(KEY_DISCOVERABLE, current.userId), applied).apply()
            state.update { it?.copy(discoverable = applied) }
            applied == enabled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "setDiscoverable failed", e)
            false
        }
    }

    private fun key(name: String, userId: String) = "$name:$userId"

    private companion object {
        const val TAG = "AccountRepository"
        const val PREFS_FILE = "account_prefs"
        const val KEY_USERNAME = "username"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_DISCOVERABLE = "discoverable"
        /** CheckUsernameAvailability's reason for an alias someone holds. */
        const val REASON_TAKEN = "taken"
    }
}
