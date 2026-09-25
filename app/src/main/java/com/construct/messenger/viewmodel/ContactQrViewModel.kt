package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.util.DisplayNameGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
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

    init {
        viewModelScope.launch { loadName() }
    }

    private suspend fun loadName() {
        val userId = keystoreManager.getUserId() ?: return
        val username = runCatching { contactsRepository.getProfile(userId) }.getOrNull()
            ?.username?.takeIf { it.isNotBlank() }
        state.update {
            it.copy(displayName = username?.let { u -> "@$u" } ?: DisplayNameGenerator.generate(userId))
        }
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
        startRotating()
    }

    fun copyLink() {
        viewModelScope.launch {
            try {
                val link = contactsRepository.mintLink().deepLink
                copied.tryEmit(link)
                state.update { it.copy(copiedCount = it.copiedCount + 1) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.update { it.copy(failed = true) }
            }
        }
    }

    private suspend fun mint() {
        try {
            val payload = contactsRepository.mintQr().payload
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
    }
}
