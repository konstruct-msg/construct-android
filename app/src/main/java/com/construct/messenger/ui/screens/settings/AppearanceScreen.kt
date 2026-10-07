package com.construct.messenger.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.AppTheme
import com.construct.messenger.data.model.Appearance
import com.construct.messenger.data.model.ChatFace
import com.construct.messenger.data.model.TextSize
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTRowDivider
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTFontFamily
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.viewmodel.AppearanceViewModel

@Composable
fun AppearanceRoute(
    onNavigateBack: () -> Unit,
    viewModel: AppearanceViewModel = hiltViewModel(),
) {
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    AppearanceScreen(
        appearance = appearance,
        onNavigateBack = onNavigateBack,
        onTheme = viewModel::setTheme,
        onChatFace = viewModel::setChatFace,
        onTextSize = viewModel::setTextSize,
    )
}

/**
 * Theme, message face, message size.
 *
 * **Canon:** iOS `AppearanceSettingsView` — three sections, each a group of choice rows with a
 * check on the chosen one and a footer saying what the choice touches. Face and size apply to
 * message text and the composer only; the chrome stays monospace at its own size.
 */
@Composable
fun AppearanceScreen(
    appearance: Appearance,
    onNavigateBack: () -> Unit,
    onTheme: (AppTheme) -> Unit,
    onChatFace: (ChatFace) -> Unit,
    onTextSize: (TextSize) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(
            title = stringResource(R.string.appearance_title),
            showBack = true,
            onBack = onNavigateBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = CTLayout.edgePad),
        ) {
            ChoiceSection(
                title = stringResource(R.string.appearance_theme),
                footer = stringResource(R.string.appearance_theme_footer),
                options = AppTheme.entries,
                selected = appearance.theme,
                onSelect = onTheme,
            ) { theme ->
                ChoiceLabel(
                    text = stringResource(theme.label),
                    icon = theme.icon,
                    iconTint = theme.tint,
                )
            }
            ChoiceSection(
                title = stringResource(R.string.appearance_chat_font),
                footer = stringResource(R.string.appearance_chat_font_footer),
                options = ChatFace.entries,
                selected = appearance.chatFace,
                onSelect = onChatFace,
            ) { face ->
                // Set in the face it offers, so the choice is visible before it is made.
                // `CTFont.message` cannot be used: it reads the current choice and would render
                // both rows alike.
                ChoiceLabel(
                    text = stringResource(face.label),
                    style = TextStyle(
                        fontFamily = if (face == ChatFace.MONO) CTFontFamily else FontFamily.Default,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    ),
                )
            }
            ChoiceSection(
                title = stringResource(R.string.appearance_text_size),
                footer = stringResource(R.string.appearance_text_size_footer),
                options = TextSize.entries,
                selected = appearance.textSize,
                onSelect = onTextSize,
            ) { size ->
                ChoiceLabel(text = stringResource(size.label))
            }
        }
    }
}

@Composable
private fun <T> ChoiceSection(
    title: String,
    footer: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> Unit,
) {
    CTSettingsSectionHeader(title = title)
    CTSectionGroup {
        options.forEachIndexed { index, option ->
            if (index > 0) CTRowDivider(indent = 52.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    label(option)
                }
                if (option == selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = CTColor.accent,
                        modifier = Modifier.size(CTIcon.nav),
                    )
                }
            }
        }
    }
    Text(
        text = footer,
        style = CTFont.caption,
        color = CTColor.textDim,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 14.dp),
    )
}

@Composable
private fun ChoiceLabel(
    text: String,
    icon: ImageVector? = null,
    iconTint: Color = CTColor.textDim,
    style: TextStyle = CTFont.ui(16, FontWeight.Bold),
) {
    if (icon != null) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(CTIcon.nav))
        Spacer(Modifier.width(14.dp))
    }
    Text(text = text, style = style, color = CTColor.text)
}

private val AppTheme.label: Int
    get() = when (this) {
        AppTheme.AUTOMATIC -> R.string.appearance_theme_automatic
        AppTheme.LIGHT -> R.string.appearance_theme_light
        AppTheme.DARK -> R.string.appearance_theme_dark
    }

private val AppTheme.icon: ImageVector
    get() = when (this) {
        AppTheme.AUTOMATIC -> Icons.Default.Contrast
        AppTheme.LIGHT -> Icons.Default.LightMode
        AppTheme.DARK -> Icons.Default.DarkMode
    }

/** iOS `AppTheme.color`: automatic dim, light orange, dark accent. */
private val AppTheme.tint: Color
    get() = when (this) {
        AppTheme.AUTOMATIC -> CTColor.textDim
        AppTheme.LIGHT -> CTColor.warning
        AppTheme.DARK -> CTColor.accent
    }

private val ChatFace.label: Int
    get() = when (this) {
        ChatFace.SYSTEM -> R.string.appearance_chat_font_system
        ChatFace.MONO -> R.string.appearance_chat_font_mono
    }

private val TextSize.label: Int
    get() = when (this) {
        TextSize.COMPACT -> R.string.appearance_text_size_compact
        TextSize.STANDARD -> R.string.appearance_text_size_standard
        TextSize.LARGE -> R.string.appearance_text_size_large
    }

@Preview(backgroundColor = 0xFF090909, showBackground = true, heightDp = 800, widthDp = 360)
@Composable
private fun AppearanceScreenPreview() {
    AppearanceScreen(
        appearance = Appearance(),
        onNavigateBack = {},
        onTheme = {},
        onChatFace = {},
        onTextSize = {},
    )
}
