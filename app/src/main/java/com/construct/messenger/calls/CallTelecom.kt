package com.construct.messenger.calls

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.construct.messenger.R
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import shared.proto.signaling.v1.Webrtc.HangupReason

/**
 * The phone's own call system, told about ours. **Canon:** iOS `CallKitProvider` — a
 * self-managed `ConnectionService` is Android's CallKit for an app that draws its own call screen.
 *
 * What it buys: the system knows a call is on, so a cellular call or another app's call is held
 * or refused properly, a headset button or a car answers and hangs up, Telecom routes the audio
 * (earpiece, speaker, Bluetooth), and the call may keep the microphone in the background.
 *
 * It follows [CallManager.state] and never decides anything itself. If Telecom refuses — no
 * registered account, another call it will not hold — the call goes on without it, ringing by
 * notification and routing audio through [CallAudio].
 *
 * Telecom gets the call id as the address, not the peer's account id, and self-managed calls stay
 * out of the system call log. The caller's name goes to it (a car or a watch shows it), as CallKit
 * shows it on iOS.
 */
@Singleton
class CallTelecom @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calls: CallManager,
    private val notifications: CallNotifications,
    private val audio: CallAudio,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val account = PhoneAccountHandle(ComponentName(context, CallConnectionService::class.java), ACCOUNT_ID)
    private var registered = false

    private var connection: KonstructConnection? = null
    /** The call Telecom was asked about and has not answered yet. */
    private var requested: String? = null

    private val _speaker = MutableStateFlow(false)
    val speaker: StateFlow<Boolean> = _speaker.asStateFlow()

    /** Where Telecom last said the sound goes, for a call it routes. */
    private var routeIsEarpiece = true

    fun start() {
        register()
        notifications.ensureChannels()
        var previous: CallState = CallState.Idle
        scope.launch {
            calls.state.collect { state ->
                onState(previous, state)
                previous = state
            }
        }
        var wasSending = false
        scope.launch {
            calls.video.collect { video ->
                val sending = video.capturing
                if (CallVideoAudio.movesToSpeaker(wasSending, sending, outputIsEarpiece())) {
                    Log.i(TAG, "camera on — sound off the earpiece, to the speaker")
                    setSpeaker(true)
                }
                wasSending = sending
            }
        }
    }

    private fun outputIsEarpiece(): Boolean = if (connection != null) routeIsEarpiece else audio.outputIsEarpiece()

    private fun register() {
        registered = runCatching {
            telecom.registerPhoneAccount(
                PhoneAccount.builder(account, context.getString(R.string.app_name))
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .setSupportedUriSchemes(listOf(SCHEME))
                    .build(),
            )
        }.onFailure { Log.w(TAG, "phone account not registered: ${it.message}") }.isSuccess
    }

    private fun onState(previous: CallState, state: CallState) {
        val session = state.session()
        val changedCall = previous.session()?.id != session?.id
        when (state) {
            is CallState.Incoming -> if (changedCall) ringIncoming(state.session)
            is CallState.Dialing -> if (changedCall) {
                placeOutgoing(state.session)
                CallService.start(context)
                audio.startRingback()
            }
            is CallState.Ringing -> connection?.setDialing()
            is CallState.Connecting -> {
                notifications.cancelIncoming()
                CallService.start(context)
                connection?.setActive()
            }
            is CallState.Active -> {
                audio.stopRingback()
                notifications.cancelIncoming()
                connection?.setActive()
            }
            is CallState.Ended -> finish(state.reason)
            CallState.Idle -> if (previous !is CallState.Ended && previous !is CallState.Idle) finish(null)
        }
    }

    private fun ringIncoming(session: CallSession) {
        _speaker.value = false
        if (registered && runCatching { telecom.isIncomingCallPermitted(account) }.getOrDefault(false)) {
            requested = session.id
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(SCHEME, session.id, null))
                putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_CALL_ID, session.id) })
            }
            val asked = runCatching { telecom.addNewIncomingCall(account, extras) }
                .onFailure { Log.w(TAG, "Telecom refused the incoming call: ${it.message}") }
                .isSuccess
            // Telecom shows nothing for a self-managed call: it asks us to, through the connection.
            if (asked) return
        }
        Log.i(TAG, "ringing without Telecom")
        notifications.showIncoming(session)
    }

    private fun placeOutgoing(session: CallSession) {
        _speaker.value = false
        if (!registered) return
        requested = session.id
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_CALL_ID, session.id) })
        }
        runCatching { telecom.placeCall(Uri.fromParts(SCHEME, session.id, null), extras) }
            .onFailure { Log.w(TAG, "Telecom refused the outgoing call: ${it.message} — going on without it") }
    }

    private fun finish(reason: CallEndReason?) {
        audio.stopRingback()
        notifications.cancelIncoming()
        requested = null
        connection?.let {
            it.setDisconnected(disconnectCause(reason))
            it.destroy()
        }
        connection = null
        _speaker.value = false
        routeIsEarpiece = true
    }

    fun setSpeaker(on: Boolean) {
        val c = connection
        if (c == null) {
            audio.setSpeaker(on)
            _speaker.value = on
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val wanted = if (on) CallEndpoint.TYPE_SPEAKER else CallEndpoint.TYPE_EARPIECE
            val endpoint = c.endpoints.firstOrNull { it.endpointType == wanted }
                ?: c.endpoints.firstOrNull { !on && it.endpointType != CallEndpoint.TYPE_SPEAKER }
                ?: return
            c.requestCallEndpointChange(endpoint, Executor(Runnable::run), object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) = Unit
                override fun onError(error: CallEndpointException) {
                    Log.w(TAG, "speaker ${if (on) "on" else "off"}: ${error.message}")
                }
            })
        } else {
            @Suppress("DEPRECATION")
            c.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        }
    }

    // ── From CallConnectionService ──────────────────────────────────────────

    fun incomingConnection(callId: String?): Connection? {
        val session = calls.state.value.session()
        if (callId == null || callId != requested || session?.id != callId || calls.state.value !is CallState.Incoming) {
            Log.w(TAG, "Telecom created an incoming connection for no ringing call (${callId?.take(8)})")
            return null
        }
        return KonstructConnection(session).also {
            it.setRinging()
            connection = it
        }
    }

    fun outgoingConnection(callId: String?): Connection? {
        val session = calls.state.value.session()
        if (callId == null || callId != requested || session?.id != callId) return null
        return KonstructConnection(session).also {
            it.setDialing()
            connection = it
        }
    }

    fun connectionFailed(callId: String?, incoming: Boolean) {
        Log.w(TAG, "Telecom could not create the ${if (incoming) "incoming" else "outgoing"} connection (${callId?.take(8)}) — going on without it")
        requested = null
        val session = calls.state.value.session()
        if (incoming && session != null && session.id == callId && calls.state.value is CallState.Incoming) notifications.showIncoming(session)
    }

    private fun disconnectCause(reason: CallEndReason?): DisconnectCause = when (reason) {
        null -> DisconnectCause(DisconnectCause.LOCAL)
        is CallEndReason.Hangup -> when (reason.reason) {
            HangupReason.HANGUP_REASON_DECLINED -> DisconnectCause(DisconnectCause.REJECTED)
            HangupReason.HANGUP_REASON_BUSY -> DisconnectCause(DisconnectCause.BUSY)
            HangupReason.HANGUP_REASON_TIMEOUT -> DisconnectCause(DisconnectCause.MISSED)
            else -> DisconnectCause(DisconnectCause.REMOTE)
        }
        is CallEndReason.Error, is CallEndReason.Local -> DisconnectCause(DisconnectCause.ERROR)
    }

    /**
     * One call as Telecom holds it. Every action on it goes to [CallManager], which decides; the
     * state coming back is what moves the connection.
     */
    private inner class KonstructConnection(session: CallSession) : Connection() {
        var endpoints: List<CallEndpoint> = emptyList()

        init {
            connectionProperties = PROPERTY_SELF_MANAGED
            connectionCapabilities = CAPABILITY_MUTE
            audioModeIsVoip = true
            setAddress(Uri.fromParts(SCHEME, session.id, null), TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(session.peerName, TelecomManager.PRESENTATION_ALLOWED)
        }

        override fun onShowIncomingCallUi() {
            calls.state.value.let { if (it is CallState.Incoming) notifications.showIncoming(it.session) }
        }

        override fun onAnswer() = calls.answer()

        override fun onAnswer(videoState: Int) = calls.answer()

        override fun onReject() = calls.end()

        override fun onDisconnect() = calls.end()

        override fun onAbort() = calls.end()

        /** The user silenced the ringer (a volume key): the call still rings on screen. */
        override fun onSilence() = notifications.cancelIncoming()

        @Deprecated("Deprecated in Java")
        override fun onCallAudioStateChanged(state: CallAudioState) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                _speaker.value = state.route == CallAudioState.ROUTE_SPEAKER
                routeIsEarpiece = state.route == CallAudioState.ROUTE_EARPIECE
            }
        }

        @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onAvailableCallEndpointsChanged(available: List<CallEndpoint>) {
            endpoints = available
        }

        @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onCallEndpointChanged(endpoint: CallEndpoint) {
            _speaker.value = endpoint.endpointType == CallEndpoint.TYPE_SPEAKER
            routeIsEarpiece = endpoint.endpointType == CallEndpoint.TYPE_EARPIECE
        }
    }

    companion object {
        private const val TAG = "CallTelecom"
        private const val ACCOUNT_ID = "konstruct"
        const val SCHEME = "konstruct-call"
        const val EXTRA_CALL_ID = "com.construct.messenger.call.ID"
    }
}
