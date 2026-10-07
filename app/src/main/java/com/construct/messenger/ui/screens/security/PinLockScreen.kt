package com.construct.messenger.ui.screens.security

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.automirrored.outlined.Backspace
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTLogoView
import com.construct.messenger.ui.components.CTMatrixBackground
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
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
        CTMatrixBackground(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            CTLogoView(size = 140.dp, color = CTColor.accent)
            Spacer(Modifier.height(44.dp))
            if (showPinEntry || !biometricMode) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp)) {
                    PinDots(
                        length = lock.pinLength,
                        filled = pin.length,
                        dotSize = 13.dp,
                        spacing = 16.dp,
                        pop = 1.2f,
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
                    Text(
                        text = error ?: " ",
                        style = CTFont.body,
                        color = CTColor.danger,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp).height(18.dp),
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = CTColor.accent,
                        modifier = Modifier.size(CTIcon.hero).clickable { promptBiometric() },
                    )
                    Text(text = stringResource(R.string.security_use_biometric), style = CTFont.ui(16, FontWeight.Medium), color = CTColor.textDim)
                    error?.let {
                        Text(
                            text = it,
                            style = CTFont.body,
                            color = CTColor.danger,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.pin_use_pin),
                        style = CTFont.ui(14),
                        color = CTColor.accent,
                        modifier = Modifier
                            .padding(top = 8.dp - LINK_PAD_V)
                            .linkTarget { showPinEntry = true; error = null },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (showPinEntry || !biometricMode) {
                val left = countdown
                if (left != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp - LINK_PAD_V),
                        modifier = Modifier.padding(bottom = 24.dp - LINK_PAD_V),
                    ) {
                        Text(
                            text = stringResource(R.string.pin_reset_countdown, left),
                            style = CTFont.ui(14, FontWeight.Medium),
                            color = CTColor.danger,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.action_cancel),
                            style = CTFont.body,
                            color = CTColor.accent,
                            modifier = Modifier.linkTarget { countdown = null },
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.pin_cant_unlock),
                        style = CTFont.body.copy(textDecoration = TextDecoration.Underline),
                        color = CTColor.textDim,
                        // The text stays 24 dp above the bottom; the rest of that is touch area.
                        modifier = Modifier
                            .padding(bottom = 24.dp - LINK_PAD_V)
                            .linkTarget { confirmReset = true },
                    )
                }
            }
        }
    }
}

/**
 * Room around a text link that still counts as the link. The screen's own catch-all click wins
 * every tap outside a target, so Compose's usual widening of small targets never applies here:
 * without this, only a tap on the 17 dp line of text itself opened "Can't sign in?".
 */
private val LINK_PAD_V = 14.dp

private fun Modifier.linkTarget(onClick: () -> Unit): Modifier =
    clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = LINK_PAD_V)

/**
 * The PIN dots. Defaults are iOS `PinDotsView` (setup): 14 dp, 14 apart, the newest dot popping
 * to 1.15. The lock screen passes its own `PinLockView.dotsIndicator` values: 13, 16, 1.2.
 */
@Composable
fun PinDots(
    length: Int,
    filled: Int,
    modifier: Modifier = Modifier,
    dotSize: Dp = 14.dp,
    spacing: Dp = 14.dp,
    pop: Float = 1.15f,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        repeat(length) { index ->
            val on = index < filled
            val scale by animateFloatAsState(
                targetValue = if (index == filled - 1) pop else 1f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessHigh),
                label = "dot",
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .scale(scale)
                    .background(if (on) CTColor.text else Color.Transparent, CircleShape)
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
                        modifier = Modifier
                            .size(KEY_SIZE)
                            .clip(CircleShape)
                            .clickable(onClick = onBiometric)
                            .padding((KEY_SIZE - 26.dp) / 2),
                    )
                }
            }
            DigitKey('0', onDigit)
            Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = stringResource(R.string.delete),
                    tint = if (canDelete) CTColor.text else CTColor.textDim.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(KEY_SIZE)
                        .clip(CircleShape)
                        .clickable(enabled = canDelete, onClick = onDelete)
                        .padding((KEY_SIZE - 24.dp) / 2),
                )
            }
        }
    }
}

private val KEY_SIZE = 74.dp

/**
 * iOS `KeypadButtonStyle`: `noise` at 60 % with a `text` 8 % ring; pressed, the fill flashes
 * `accent` at 25 % and the key springs to 0.92.
 */
@Composable
private fun DigitKey(digit: Char, onDigit: (Char) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "key",
    )
    Box(
        modifier = Modifier
            .size(KEY_SIZE)
            .scale(scale)
            .clip(CircleShape)
            .background(if (pressed) CTColor.accent.copy(alpha = 0.25f) else CTColor.noise.copy(alpha = 0.6f))
            .border(1.dp, CTColor.text.copy(alpha = 0.08f), CircleShape)
            .clickable(interactionSource = interaction, indication = null) { onDigit(digit) },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = digit.toString(), style = CTFont.ui(30), color = CTColor.text)
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
