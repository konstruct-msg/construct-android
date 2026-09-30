package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.util.DisplayNameGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ContactQrUiState(
    val displayName: String = "",
    /** base64url(CIv1) — the text the code carries; `null` until the first mint. */
    val payload: String? = null,
    val failed: Boolean = false,
    /** Links copied in this sitting — drives "Copied · N" (iOS `InviteShareDecision`). */
    val copiedCount: Int = 0,
    /** The last copy minted nothing; said under the rule, the code stays. */
    val copyFailed: Boolean = false,
)

/**
 * The one surface for inviting someone: a QR that refreshes itself and a copy-link button.
 *
 * **Canon:** iOS `ContactQRCodeView`. An invite is burned by its first redeemer and the sender
 * is never told, so a code left on screen is re-minted every [ROTATE_MS] — that is what lets a
 * few people scan the same screen. "New code" is the same mint on demand. Every copy mints a
 * fresh 12 h link.
 */
@HiltViewModel
class ContactQrViewModel @Inject constructor(
    private val contactsRepository: ContactsRepository,
    private val keystoreManager: KeystoreManager,
) : ViewModel() {
    private val state = MutableStateFlow(ContactQrUiState())
    val uiState: StateFlow<ContactQrUiState> = state.asStateFlow()

    // The newest link wins: a double tap must leave the second, unused link on the clipboard,
    // not the first — which the counter would already claim was replaced.
    private val copied = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Links to put on the clipboard — the UI owns the clipboard. */
    val copiedLinks: SharedFlow<String> = copied.asSharedFlow()

    private var rotation: Job? = null

    /** This visit to the screen: every code it shows is one showing in Issued invites (iOS sitting). */
    private val sitting = UUID.randomUUID().toString()
    private var lastCopyAtMs = 0L

    init {
        viewModelScope.launch { loadName() }
    }

    /**
     * The generated name at once, the username when the server gives one. It waited for the
     * server before, and on a slow path the header stayed empty for as long as the call hung.
     */
    private suspend fun loadName() {
        val userId = keystoreManager.getUserId() ?: return
        state.update { it.copy(displayName = DisplayNameGenerator.generate(userId)) }
        val username = runCatching { contactsRepository.getProfile(userId) }.getOrNull()
            ?.username?.takeIf { it.isNotBlank() } ?: return
        state.update { it.copy(displayName = "@$username") }
    }

    /** While the screen is visible: a code now, then a new one every [ROTATE_MS]. */
    fun startRotating() {
        if (rotation?.isActive == true) return
        rotation = viewModelScope.launch {
            while (isActive) {
                mint()
                delay(ROTATE_MS)
            }
        }
    }

    /** Off screen, nothing is minted — a code nobody can see only fills the journal. */
    fun stopRotating() {
        rotation?.cancel()
        rotation = null
    }

    /** For the person the current code did not scan for — restarts the rotation clock. */
    fun newCode() {
        stopRotating()
        state.update { it.copy(payload = null, failed = false) }
        startRotating()
    }

    /**
     * Mint a fresh link for the clipboard. A tap within [COPY_DEBOUNCE_MS] of the last is the
     * same tap (iOS `InviteShareDecision.shouldMint`). A failure is said, never swallowed — and
     * it is the link that failed, not the code on screen.
     */
    fun copyLink(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastCopyAtMs < COPY_DEBOUNCE_MS) return
        lastCopyAtMs = nowMs
        viewModelScope.launch {
            try {
                val link = contactsRepository.mintLink().deepLink
                copied.tryEmit(link)
                state.update { it.copy(copiedCount = it.copiedCount + 1, copyFailed = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.update { it.copy(copyFailed = true) }
            }
        }
    }

    private suspend fun mint() {
        try {
            val payload = contactsRepository.mintQr(sitting).payload
            state.update { it.copy(payload = payload, failed = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state.update { it.copy(payload = null, failed = true) }
        }
    }

    companion object {
        /** iOS `InviteConfig.qrRotateIntervalSeconds`. */
        const val ROTATE_MS = 30_000L

        /** iOS `SettingsShareLayout.copyDebounce`. */
        const val COPY_DEBOUNCE_MS = 300L
    }
}
