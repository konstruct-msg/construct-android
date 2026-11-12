package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.LocalStores
import com.construct.messenger.data.repository.AppLockRepository
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.IncomingShareRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class IncomingShareViewModel @Inject constructor(
    private val shares: IncomingShareRepository,
    auth: AuthRepository,
    appLock: AppLockRepository,
    stores: LocalStores,
) : ViewModel() {
    val state = shares.state
    val authState = auth.authState
    val inputChanges = shares.inputChanges
    val hasInput: Boolean get() = shares.hasInput
    val canUse = combine(auth.authState, appLock.lock, stores.state) { account, lock, store ->
        account.isInitialized && !lock.requiresUnlock && store == LocalStores.State.Ready
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var preparing: Job? = null

    init {
        viewModelScope.launch {
            combine(canUse, inputChanges) { allowed, _ -> allowed }.collect { allowed ->
                preparing?.cancel()
                if (allowed) preparing = viewModelScope.launch { shares.prepare() }
            }
        }
    }

    fun choose(id: String, contactId: String) { viewModelScope.launch { shares.select(id, contactId) } }
    fun cancel() {
        preparing?.cancel()
        viewModelScope.launch { shares.cancel() }
    }
    fun keepCurrent() = shares.keepCurrent()
    fun replaceCurrent() { viewModelScope.launch { shares.replaceCurrent() } }
    fun clearProblem() = shares.clearProblem()
}
