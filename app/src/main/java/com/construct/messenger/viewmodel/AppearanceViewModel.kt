package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.data.model.AppTheme
import com.construct.messenger.data.model.Appearance
import com.construct.messenger.data.model.ChatFace
import com.construct.messenger.data.model.TextSize
import com.construct.messenger.data.repository.AppearanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Settings → Appearance. A choice applies at once; there is nothing to save. */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val repository: AppearanceRepository,
) : ViewModel() {
    val appearance: StateFlow<Appearance> = repository.appearance

    fun setTheme(theme: AppTheme) = repository.setTheme(theme)
    fun setChatFace(face: ChatFace) = repository.setChatFace(face)
    fun setTextSize(size: TextSize) = repository.setTextSize(size)
}
