package com.construct.messenger.ui.screens.security

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTLogoView
import com.construct.messenger.ui.components.CTNoise
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.AppLockViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** iOS `PinLockView.resetCountdownSeconds`. */
private const val RESET_COUNTDOWN_SECONDS = 10

/**
 * The lock screen, drawn over the whole app while the PIN is required.
 *
 * **Canon:** iOS `PinLockView` — logo, dots, round-key numpad (no system keyboard), biometrics
 * offered first when enabled, and the escape hatch: "Can't sign in?" → confirm → a ten-second
 * countdown that entering the PIN cancels → everything on this device is erased. There is no
 * way back into the same account without the PIN (see [com.construct.messenger.data.repository.AppLockRepository]).
 */
@Composable
fun PinLockScreen(viewModel: AppLockViewModel = hiltViewModel()) {
    val lock by viewModel.lock.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val biometricMode = lock.biometricEnabled && lock.biometricAvailable
    var showPinEntry by remember { mutableStateOf(!biometricMode) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    val shake = remember { Animatable(0f) }
    val wrongPin = stringResource(R.string.pin_wrong)

    val promptBiometric = {
        (context as? FragmentActivity)?.let { activity ->
            showBiometricPrompt(
                activity = activity,
                title = context.getString(R.string.pin_unlock),
                cancel = context.getString(R.string.pin_use_pin),
                onSuccess = { countdown = null; viewModel.unlockWithBiometrics() },
                // Dismissed ("Use PIN code", back): go to the numpad. A real failure is shown.
                onError = { message -> if (message == null) showPinEntry = true else error = message },
            )
        }
    }
    LaunchedEffect(Unit) { if (biometricMode) promptBiometric() }

    // The countdown ends in the erase; entering the PIN (or cancel) stops it by clearing it.
    LaunchedEffect(countdown) {
        val left = countdown ?: return@LaunchedEffect
        delay(1_000)
        if (left <= 1) viewModel.eraseDevice() else countdown = left - 1
    }

    fun submit(entered: String) {
        checking = true
        viewModel.tryUnlock(entered) {
            checking = false
            pin = ""
            error = wrongPin
            scope.launch {
                for (x in listOf(10f, -8f, 6f, -4f, 0f)) shake.animateTo(x, androidx.compose.animation.core.tween(80))
            }
        }
    }

    if (confirmReset) {
        CTConfirmDialog(
            title = stringResource(R.string.pin_reset_title),
            message = stringResource(R.string.pin_reset_explain),
            confirmLabel = stringResource(R.string.pin_reset_action),
            dismissLabel = stringResource(R.string.action_cancel),
            isDestructive = true,
            onConfirm = { confirmReset = false; countdown = RESET_COUNTDOWN_SECONDS },
            onDismiss = { confirmReset = false },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            // Swallows every touch: nothing under the lock is reachable.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        CTNoise(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            CTLogoView(size = 140.dp, color = CTColor.text)
            Spacer(Modifier.height(44.dp))
            if (showPinEntry || !biometricMode) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp)) {
                    PinDots(
                        length = lock.pinLength,
                        filled = pin.length,
                        modifier = Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) },
                    )
                    Numpad(
                        biometric = biometricMode,
                        canDelete = pin.isNotEmpty() && !checking,
                        onDigit = { digit ->
                            if (!checking && pin.length < lock.pinLength) {
                                error = null
                                pin += digit
                                // A PIN typed during the countdown cancels it — the person is back.
                                countdown = null
                                if (pin.length == lock.pinLength) submit(pin)
                            }
                        },
                        onDelete = { pin = pin.dropLast(1) },
                        onBiometric = { pin = ""; error = null; promptBiometric() },
                    )
                    Text(text = error ?: " ", style = ctRegular(13), color = CTColor.danger, textAlign = TextAlign.Center)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = CTColor.accent,
                        modifier = Modifier.size(56.dp).clickable { promptBiometric() },
                    )
                    Text(text = stringResource(R.string.security_use_biometric), style = ctRegular(14), color = CTColor.text)
                    error?.let { Text(text = it, style = ctRegular(12), color = CTColor.danger, textAlign = TextAlign.Center) }
                    Text(
                        text = stringResource(R.string.pin_use_pin),
                        style = ctRegular(14),
                        color = CTColor.accent,
                        modifier = Modifier.clickable { showPinEntry = true; error = null },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (showPinEntry || !biometricMode) {
                val left = countdown
                if (left != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 24.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.pin_reset_countdown, left),
                            style = ctRegular(12),
                            color = CTColor.danger,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.action_cancel),
                            style = ctRegular(13),
                            color = CTColor.accent,
                            modifier = Modifier.clickable { countdown = null },
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.pin_cant_unlock),
                        style = ctRegular(12).copy(textDecoration = TextDecoration.Underline),
                        color = CTColor.textDim,
                        modifier = Modifier
                            .padding(bottom = 24.dp)
                            .clickable { confirmReset = true },
                    )
                }
            }
        }
    }
}

/** iOS dot indicator: 14dp circles, filled as digits arrive. */
@Composable
fun PinDots(length: Int, filled: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(length) { index ->
            val on = index < filled
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(if (on) CTColor.text else CTColor.bg, CircleShape)
                    .border(1.5.dp, CTColor.text.copy(alpha = if (on) 1f else 0.3f), CircleShape),
            )
        }
    }
}

@Composable
private fun Numpad(
    biometric: Boolean,
    canDelete: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onBiometric: () -> Unit,
) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { digit -> DigitKey(digit, onDigit) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                if (biometric) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = stringResource(R.string.security_use_biometric),
                        tint = CTColor.accent,
                        modifier = Modifier.size(32.dp).clickable(onClick = onBiometric),
                    )
                }
            }
            DigitKey('0', onDigit)
            Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = stringResource(R.string.delete),
                    tint = if (canDelete) CTColor.text else CTColor.textDim.copy(alpha = 0.4f),
                    modifier = Modifier.size(26.dp).clickable(enabled = canDelete, onClick = onDelete),
                )
            }
        }
    }
}

private val KEY_SIZE = 74.dp

@Composable
private fun DigitKey(digit: Char, onDigit: (Char) -> Unit) {
    Box(
        modifier = Modifier
            .size(KEY_SIZE)
            .background(CTColor.bgMsg, CircleShape)
            .border(1.dp, CTColor.noise, CircleShape)
            .clickable { onDigit(digit) },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = digit.toString(), style = ctRegular(28), color = CTColor.text)
    }
}

/** BiometricPrompt with the system's own UI; the negative button returns to the numpad. */
fun showBiometricPrompt(
    activity: FragmentActivity,
    title: String,
    cancel: String,
    onSuccess: () -> Unit,
    onError: (String?) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val dismissed = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onError(if (dismissed) null else errString.toString())
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(cancel)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
            .build(),
    )
}
