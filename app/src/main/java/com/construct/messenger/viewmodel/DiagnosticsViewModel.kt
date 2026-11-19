package com.construct.messenger.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.construct.messenger.data.repository.IncomingShareRepository
import com.construct.messenger.diagnostics.Diagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val shares: IncomingShareRepository,
) : ViewModel() {
    /**
     * Puts the log archive into the share draft; IncomingShareHost then asks for the chat and the
     * person sends it as an ordinary encrypted file. False when there is no archive to send.
     */
    suspend fun sendLogsToChat(context: Context): Boolean {
        val archive = withContext(Dispatchers.IO) {
            Diagnostics.collector?.flush()
            Diagnostics.archive(context)
        } ?: return false
        shares.offerOwn(archive)
        return true
    }
}
