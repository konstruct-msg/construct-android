package com.construct.messenger.ui.screens.security

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.repository.PIN_LENGTH
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.AppLockViewModel
import kotlinx.coroutines.launch

/** What the screen is for: iOS `PinSetupView(isChanging:)` and `PinDisableView`. */
enum class PinFlow { CREATE, CHANGE, DISABLE }

private enum class Step { CURRENT, ENTER, CONFIRM, BIOMETRIC }

/**
 * Create, change or turn off the PIN.
 *
 * **Canon:** iOS `PinSetupView` — current PIN (when changing) → create → confirm → offer
 * biometrics if the device has them; `PinDisableView` — the current PIN, then off. Dots over a
 * hidden numeric field, the action button at the bottom.
 */
@Composable
fun PinSetupScreen(
    flow: PinFlow,
    onDone: () -> Unit,
    viewModel: AppLockViewModel = hiltViewModel(),
) {
    val lock by viewModel.lock.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf(if (flow == PinFlow.CREATE) Step.ENTER else Step.CURRENT) }
    var current by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var biometric by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    fun shakeDots() = scope.launch {
        for (x in listOf(10f, -8f, 6f, -4f, 0f)) shake.animateTo(x, tween(80))
    }

    fun finishSetup() {
        busy = true
        viewModel.setPin(newPin, biometric) { onDone() }
    }

    fun primary() {
        errorRes = null
        when (step) {
            Step.CURRENT -> {
                busy = true
                viewModel.verify(current) { ok ->
                    busy = false
                    when {
                        !ok -> { errorRes = R.string.pin_wrong; current = ""; shakeDots() }
                        flow == PinFlow.DISABLE -> { viewModel.disablePin(); onDone() }
                        else -> step = Step.ENTER
                    }
                }
            }
            Step.ENTER -> step = Step.CONFIRM
            Step.CONFIRM -> when {
                confirm != newPin -> { errorRes = R.string.pin_mismatch; confirm = ""; shakeDots() }
                lock.biometricAvailable -> step = Step.BIOMETRIC
                else -> finishSetup()
            }
            Step.BIOMETRIC -> finishSetup()
        }
    }

    val (value, setValue, length) = when (step) {
        Step.CURRENT -> Triple(current, { v: String -> current = v }, lock.pinLength)
        Step.ENTER -> Triple(newPin, { v: String -> newPin = v }, PIN_LENGTH)
        Step.CONFIRM -> Triple(confirm, { v: String -> confirm = v }, newPin.length)
        Step.BIOMETRIC -> Triple("", { _: String -> }, 0)
    }
    val canProceed = !busy && (step == Step.BIOMETRIC || value.length >= length)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        CTNavBar(
            title = stringResource(
                when {
                    flow == PinFlow.DISABLE -> R.string.pin_disable
                    step == Step.CURRENT -> R.string.pin_enter
                    step == Step.ENTER -> R.string.pin_create
                    step == Step.CONFIRM -> R.string.pin_confirm
                    else -> R.string.security_title
                },
            ),
            showBack = true,
            onBack = onDone,
        )
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (step == Step.BIOMETRIC) {
                Text(
                    text = stringResource(R.string.pin_enable_biometric),
                    style = ctBold(15),
                    color = CTColor.text,
                    textAlign = TextAlign.Center,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.security_use_biometric),
                        style = ctRegular(13),
                        color = CTColor.text,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = biometric,
                        onCheckedChange = { biometric = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CTColor.bg,
                            checkedTrackColor = CTColor.accent,
                            uncheckedThumbColor = CTColor.textDim,
                            uncheckedTrackColor = CTColor.outMsgBg,
                            uncheckedBorderColor = CTColor.noise,
                        ),
                    )
                }
            } else {
                Text(
                    text = stringResource(
                        when (step) {
                            Step.CURRENT -> R.string.pin_enter
                            Step.ENTER -> R.string.pin_create
                            else -> R.string.pin_confirm
                        },
                    ),
                    style = ctBold(15),
                    color = CTColor.text,
                )
                HiddenPinField(
                    key = step,
                    value = value,
                    length = length,
                    shake = shake.value,
                    onValueChange = setValue,
                    onComplete = { primary() },
                    // Creating: the new PIN is only a length check, so wait for the button.
                    autoSubmit = step != Step.ENTER,
                )
                if (step == Step.ENTER) {
                    Text(text = stringResource(R.string.pin_length_hint), style = ctRegular(11), color = CTColor.textDim)
                }
            }
            errorRes?.let {
                Text(text = stringResource(it), style = ctRegular(13), color = CTColor.danger, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .padding(bottom = 16.dp)
                .fillMaxWidth()
                .background(if (canProceed) CTColor.accent else CTColor.noise)
                .clickable(enabled = canProceed) { primary() }
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    when {
                        flow == PinFlow.DISABLE -> R.string.pin_disable
                        step == Step.BIOMETRIC -> R.string.pin_done
                        else -> R.string.pin_continue
                    },
                ),
                style = ctRegular(14),
                color = CTColor.outMsgTextDark,
            )
        }
    }
}

/** iOS `PinDotsView`: the dots, over a hidden numeric field that brings up the system keypad. */
@Composable
private fun HiddenPinField(
    key: Any,
    value: String,
    length: Int,
    shake: Float,
    onValueChange: (String) -> Unit,
    onComplete: () -> Unit,
    autoSubmit: Boolean,
) {
    val focus = remember(key) { FocusRequester() }
    LaunchedEffect(key) { focus.requestFocus() }
    Box(contentAlignment = Alignment.Center) {
        BasicTextField(
            value = value,
            onValueChange = { raw ->
                val digits = raw.filter(Char::isDigit).take(length)
                onValueChange(digits)
                if (autoSubmit && digits.length == length && value.length < length) onComplete()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focus),
        )
        PinDots(
            length = length,
            filled = value.length,
            modifier = Modifier
                .offset { IntOffset(shake.dp.roundToPx(), 0) }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    focus.requestFocus()
                },
        )
    }
}
