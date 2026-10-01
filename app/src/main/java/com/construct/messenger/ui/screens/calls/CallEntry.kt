package com.construct.messenger.ui.screens.calls

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.CallStartError
import com.construct.messenger.viewmodel.CallViewModel

/**
 * What a call button does: ask for the microphone if it has not been given (the first time a call
 * needs it, never before — as for voice notes), then call. Why a call did not start is said once,
 * as a toast. Returns the action, or null while a call is on (iOS shows no call button then).
 */
@Composable
fun rememberCallAction(peerId: String, vm: CallViewModel = hiltViewModel()): (() -> Unit)? {
    val context = LocalContext.current
    val call by vm.call.collectAsStateWithLifecycle()
    val error by vm.startError.collectAsStateWithLifecycle()
    val refused by vm.micRefused.collectAsStateWithLifecycle()
    val texts = mapOf(
        CallStartError.NOT_A_CONTACT to stringResource(R.string.call_error_not_contacts),
        CallStartError.BUSY to stringResource(R.string.call_error_busy),
        CallStartError.SETUP_FAILED to stringResource(R.string.call_error_setup_failed),
    )
    val noMic = stringResource(R.string.call_error_no_microphone)
    LaunchedEffect(error, refused) {
        val text = error?.let(texts::get) ?: noMic.takeIf { refused } ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        vm.clearErrors()
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.start(peerId) else vm.microphoneRefused()
    }
    if (call != null) return null
    return {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.start(peerId)
        } else {
            ask.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
