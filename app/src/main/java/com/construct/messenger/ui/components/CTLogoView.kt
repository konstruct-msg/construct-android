package com.construct.messenger.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor

/**
 * Construct Messenger logo.
 *
 * **Canon:** iOS `CTLogoView.swift` → `Image("KonstructLogo")` rendered as a template
 * with a tint color. Android mirrors this with a vector drawable tinted at runtime.
 *
 * @param size Bounding square side-length.
 * @param color Tint applied to the logo.
 */
@Composable
fun CTLogoView(
    size: Dp = 100.dp,
    color: Color = CTColor.text,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(id = R.drawable.ic_logo),
        contentDescription = stringResource(R.string.app_name),
        tint = color,
        modifier = modifier.size(size),
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTLogoViewPreview() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(160.dp),
    ) {
        CTLogoView(size = 100.dp, color = CTColor.text)
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTLogoViewAccentPreview() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(120.dp),
    ) {
        CTLogoView(size = 72.dp, color = CTColor.accent)
    }
}
