package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor

/**
 * iOS `glassCapsule()`: `.ultraThinMaterial` over the background at 35 %, a 0.5pt `noise` hairline
 * and a soft shadow — the chat's floating nav bar and composer. Compose has no cheap material blur;
 * a mostly opaque card fill reads the same over the transcript.
 *
 * [cornerRadius]: iOS `glassCapsule(cornerRadius:)`. Null is a true capsule, which is right only
 * for something that never grows — on a field that wraps, half its height would cut into the text.
 */
@Composable
fun Modifier.glassCapsule(cornerRadius: Dp? = null): Modifier {
    val shape = cornerRadius?.let(::RoundedCornerShape) ?: RoundedCornerShape(percent = 50)
    return this
        .shadow(12.dp, shape, ambientColor = CTColor.bg, spotColor = CTColor.bg)
        .clip(shape)
        .background(CTColor.bgMsg.copy(alpha = 0.88f))
        .border(0.5.dp, CTColor.noise.copy(alpha = 0.5f), shape)
}
