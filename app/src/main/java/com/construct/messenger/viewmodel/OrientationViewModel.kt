package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.OrientationStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs [OrientationScreen][com.construct.messenger.ui.screens.orientation.OrientationScreen].
 *
 * **Canon:** iOS `OrientationView.finish()` sets `@AppStorage(OrientationStore.completedKey)`.
 * The only side effect is persisting completion so the guide is not shown again on the
 * next launch (see [com.construct.messenger.viewmodel.SplashViewModel]).
 */
@HiltViewModel
class OrientationViewModel @Inject constructor(
    private val orientationStore: OrientationStore,
) : ViewModel() {

    /** Persist that the user has seen the guide (finished or skipped). Fire-and-forget. */
    fun markCompleted() {
        viewModelScope.launch { orientationStore.setCompleted() }
    }
}
