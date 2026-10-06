package com.construct.messenger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.data.model.AppTheme
import com.construct.messenger.data.repository.AppearanceRepository
import com.construct.messenger.ui.screens.calls.CallScreen
import com.construct.messenger.ui.theme.KonstructMessengerTheme
import com.construct.messenger.viewmodel.CallViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The call screen, as its own activity: it opens over the lock screen and turns the screen on for
 * a ringing call (the notification's full-screen intent), and it is what the notification and the
 * in-app mini bar return to. It shows no message and no chat, so it is not behind the app's PIN —
 * as CallKit's screen on iOS is not behind Face ID.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    @Inject lateinit var appearanceRepository: AppearanceRepository

    private val calls: CallViewModel by viewModels()

    /** Answering: the microphone, and the camera too for a video call. Without the camera it is answered with sound. */
    private val answerPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true || has(Manifest.permission.RECORD_AUDIO)) calls.answer() else refused()
    }

    private val camera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) calls.setCameraOn(true) else cameraRefused()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        handle(intent)
        setContent {
            val appearance by appearanceRepository.appearance.collectAsStateWithLifecycle()
            val dark = when (appearance.theme) {
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
                AppTheme.AUTOMATIC -> isSystemInDarkTheme()
            }
            KonstructMessengerTheme(darkTheme = dark) {
                val call by calls.call.collectAsStateWithLifecycle()
                val error by calls.startError.collectAsStateWithLifecycle()
                // Nothing to show: the call ended and its "ended" moment passed, or it never was.
                LaunchedEffect(call == null) { if (call == null) finish() }
                LaunchedEffect(error) {
                    if (error == com.construct.messenger.data.model.CallStartError.CAMERA_DENIED) {
                        cameraRefused()
                        calls.clearErrors()
                    }
                }
                call?.let {
                    CallScreen(
                        call = it,
                        onAnswer = ::answer,
                        onDecline = calls::end,
                        onEnd = calls::end,
                        onToggleMute = calls::toggleMute,
                        onToggleSpeaker = calls::toggleSpeaker,
                        onMinimize = ::finish,
                        frames = calls.frames,
                        onToggleCamera = { toggleCamera(it.video.cameraOn) },
                        onSwitchCamera = calls::switchCamera,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) answer()
    }

    /**
     * Answering needs the microphone, and a video call the camera; asked for here, the moment they
     * are needed (as on iOS, where AVFoundation asks when the camera first comes on).
     */
    private fun answer() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (calls.call.value?.isVideoCall == true) add(Manifest.permission.CAMERA)
        }.filterNot(::has)
        if (wanted.isEmpty()) calls.answer() else answerPermissions.launch(wanted.toTypedArray())
    }

    private fun toggleCamera(isOn: Boolean) {
        when {
            isOn -> calls.setCameraOn(false)
            has(Manifest.permission.CAMERA) -> calls.setCameraOn(true)
            else -> camera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun refused() {
        Toast.makeText(this, R.string.call_error_no_microphone, Toast.LENGTH_LONG).show()
    }

    private fun cameraRefused() {
        Toast.makeText(this, R.string.call_error_camera_denied, Toast.LENGTH_LONG).show()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    companion object {
        const val ACTION_SHOW = "com.construct.messenger.call.SHOW"
        const val ACTION_ANSWER = "com.construct.messenger.call.ANSWER"

        fun open(context: Context) {
            context.startActivity(
                Intent(context, CallActivity::class.java).setAction(ACTION_SHOW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }
}
