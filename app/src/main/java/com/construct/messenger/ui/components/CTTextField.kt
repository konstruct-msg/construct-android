package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.HairlineBorder
import com.construct.messenger.ui.theme.ctRegular

/**
 * Single-line text input.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTTextField`.
 * - `ctRegular(14)`, text color `text`, cursor `accent`.
 * - 12dp horizontal / 11dp vertical padding, `bgMsg` background.
 * - Corner radius 8 with a 0.5dp `noise` border.
 * - [isSecure] masks input (password). [textAlign] controls alignment.
 */
@Composable
fun CTTextField(
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isSecure: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
) {
    val shape = RoundedCornerShape(CornerRadius.small)
    val textStyle = ctRegular(14).copy(color = CTColor.text, textAlign = textAlign)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CTColor.bgMsg)
            .border(HairlineBorder, CTColor.noise, shape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        textStyle = textStyle,
        singleLine = true,
        cursorBrush = SolidColor(CTColor.accent),
        visualTransformation = if (isSecure) PasswordVisualTransformation()
                               else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isSecure) KeyboardType.Password else KeyboardType.Text,
        ),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = CTColor.textDim,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                innerTextField()
            }
        },
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 320)
@Composable
private fun CTTextFieldPreview() {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        CTTextField(placeholder = "username", value = "", onValueChange = {})
        CTTextField(placeholder = "username", value = "silent_fox", onValueChange = {})
        CTTextField(placeholder = "passphrase", value = "secret", onValueChange = {}, isSecure = true)
    }
}
