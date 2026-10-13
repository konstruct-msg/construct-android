package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.R
import com.construct.messenger.veil.VeilBeforeAccount
import com.construct.messenger.veil.VeilConfigImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** The "Can't connect?" door on the first screen: redeem a VEIL access code before any account exists. */
@HiltViewModel
class VeilBootstrapViewModel @Inject constructor(
    private val importer: VeilConfigImporter,
    private val beforeAccount: VeilBeforeAccount,
) : ViewModel() {
    /** What to say: the import's outcome. The route starts on a front that went in. */
    fun redeem(text: String): Int {
        val message = VeilConfigImporter.messageOf(importer.redeem(text))
        if (message == R.string.veil_config_import_ok) beforeAccount.startIfConfigured()
        return message
    }

    /** A front imported on an earlier run: registration goes through it on this one too. */
    fun startIfConfigured() = beforeAccount.startIfConfigured()
}
