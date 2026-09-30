package com.construct.messenger.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.PeerDeviceRegistry
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.local.db.localName
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.IdentityFingerprint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One of their devices and the number for it with this device. [number] null: the core declined. */
data class DeviceSafetyNumber(val fingerprint: String, val number: String?)

data class SafetyNumberUiState(
    val contactName: String = "",
    val loading: Boolean = true,
    val devices: List<DeviceSafetyNumber> = emptyList(),
)

/**
 * Safety numbers with a contact — one per device of theirs this device knows, since a session is
 * between two devices and a substituted key is a substituted device. **Canon:** iOS
 * `SafetyNumberView` (which shows the one device it pins).
 */
@HiltViewModel
class SafetyNumberViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val cryptoManager: CryptoManager,
    private val keystoreManager: KeystoreManager,
    private val registry: PeerDeviceRegistry,
    private val userDao: UserDao,
) : ViewModel() {
    private val contactId: String = requireNotNull(savedStateHandle.get<String>("contactId"))
    private val state = MutableStateFlow(SafetyNumberUiState())
    val uiState: StateFlow<SafetyNumberUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val row = userDao.getById(contactId)
        val name = row?.localName
            ?: row?.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: row?.displayName?.takeIf { it.isNotBlank() }
            ?: DisplayNameGenerator.generate(contactId)
        val mine = keystoreManager.getDeviceId()
        // A contact added before the registry knew devices has one key on their row; resolving
        // records it, so the list below is never empty only for that reason.
        if (registry.knownDevices(contactId).isEmpty()) registry.resolveDeviceId(contactId)
        val devices = registry.knownDevices(contactId).map { device ->
            DeviceSafetyNumber(
                fingerprint = IdentityFingerprint.short(device.identityPublic).orEmpty(),
                number = mine?.let { cryptoManager.safetyNumber(it, device.deviceId) },
            )
        }
        state.value = SafetyNumberUiState(contactName = name, loading = false, devices = devices)
    }
}
