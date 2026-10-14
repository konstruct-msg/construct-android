package com.construct.messenger.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.transcription.Transcriber
import com.construct.messenger.transcription.TranscriptionSettings
import com.construct.messenger.transcription.WhisperModel
import com.construct.messenger.transcription.WhisperModels
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.components.DialogButton
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.TranscriptionUiState
import com.construct.messenger.viewmodel.TranscriptionViewModel
import java.util.Locale

@Composable
fun TranscriptionRoute(onNavigateBack: () -> Unit, viewModel: TranscriptionViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TranscriptionScreen(
        state = state,
        onNavigateBack = onNavigateBack,
        actions = TranscriptionActions(
            autoTranscribe = viewModel::setAutoTranscribe,
            continuousVoice = viewModel::setContinuousVoice,
            language = viewModel::setLanguage,
            translate = viewModel::setTranslate,
            download = viewModel::download,
            cancel = viewModel::cancel,
            use = viewModel::use,
            delete = viewModel::delete,
        ),
    )
}

class TranscriptionActions(
    val autoTranscribe: (Boolean) -> Unit = {},
    val continuousVoice: (Boolean) -> Unit = {},
    val language: (String) -> Unit = {},
    val translate: (Boolean) -> Unit = {},
    val download: (WhisperModel) -> Unit = {},
    val cancel: (WhisperModel) -> Unit = {},
    val use: (WhisperModel) -> Unit = {},
    val delete: (WhisperModel) -> Unit = {},
)

/**
 * Settings → Transcription (TODO 116). **Canon:** iOS `STTSettingsSection` — automatic reading of
 * voice notes, the language, translation to English, and the models: download, use, delete, the
 * room they take. One engine on Android (whisper.cpp), so no engine picker. Above them, as on iOS
 * `TranscriptionSettingsView`, how voice notes play: one after another (TODO 149).
 */
@Composable
private fun TranscriptionScreen(state: TranscriptionUiState, onNavigateBack: () -> Unit, actions: TranscriptionActions) {
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WhisperModel?>(null) }
    val inUse = state.settings.model ?: state.models.filterValues { it == WhisperModels.State.Installed }.keys.maxOrNull()
    Column(Modifier.fillMaxSize().background(CTColor.bg).statusBarsPadding().navigationBarsPadding()) {
        CTNavBar(title = stringResource(R.string.stt_section_title), showBack = true, onBack = onNavigateBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = CTLayout.edgePad)) {
            CTSettingsSectionHeader(title = stringResource(R.string.voice_section_title))
            CTSectionGroup {
                SwitchRow(stringResource(R.string.voice_continuous_playback), state.continuousVoice, actions.continuousVoice)
            }
            Footer(stringResource(R.string.voice_continuous_playback_footer))

            if (!state.available) {
                Footer(stringResource(R.string.stt_error_unavailable))
                return@Column
            }
            CTSectionGroup {
                SwitchRow(stringResource(R.string.stt_auto_transcribe), state.settings.autoTranscribe, actions.autoTranscribe)
                HorizontalDivider()
                CTSettingsRow(
                    label = stringResource(R.string.stt_language_label),
                    value = languageName(state.settings.language),
                    disclosure = true,
                    modifier = Modifier.clickable { picking = true },
                )
            }
            Footer(stringResource(R.string.stt_footer))

            CTSectionGroup {
                SwitchRow(stringResource(R.string.stt_translate_toggle), state.settings.translate, actions.translate)
            }
            Footer(stringResource(R.string.stt_translate_footer))

            CTSettingsSectionHeader(title = stringResource(R.string.stt_models))
            CTSectionGroup {
                WhisperModel.entries.forEachIndexed { i, model ->
                    if (i > 0) HorizontalDivider()
                    ModelRow(model, state.models[model] ?: WhisperModels.State.Absent, model == inUse, actions) { deleting = model }
                }
            }
            Footer(stringResource(R.string.stt_models_on_disk, formatBytes(state.bytesOnDisk)))
        }
    }
    if (picking) LanguageDialog(state.settings.language, onPick = { actions.language(it); picking = false }, onDismiss = { picking = false })
    deleting?.let { model ->
        CTConfirmDialog(
            title = stringResource(R.string.stt_model_delete),
            message = stringResource(R.string.stt_model_delete_confirm, model.label()),
            confirmLabel = stringResource(R.string.stt_model_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { actions.delete(model); deleting = null },
            onDismiss = { deleting = null },
            isDestructive = true,
        )
    }
}

private fun WhisperModel.label() = name.lowercase(Locale.ROOT)

@Composable
private fun ModelRow(model: WhisperModel, state: WhisperModels.State, inUse: Boolean, actions: TranscriptionActions, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text("${model.label()} · ${formatBytes(model.bytes)}") },
        supportingContent = when (state) {
            is WhisperModels.State.Downloading -> {
                { LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth().padding(top = CTSpace.xs)) }
            }
            WhisperModels.State.Failed -> {
                { Text(stringResource(R.string.stt_model_failed), color = MaterialTheme.colorScheme.error) }
            }
            WhisperModels.State.Installed -> if (inUse) {
                { Text(stringResource(R.string.stt_model_in_use), color = MaterialTheme.colorScheme.primary) }
            } else {
                null
            }
            WhisperModels.State.Absent -> null
        },
        trailingContent = {
            when (state) {
                is WhisperModels.State.Downloading -> IconButton(onClick = { actions.cancel(model) }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel))
                }
                WhisperModels.State.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!inUse) TextButton(onClick = { actions.use(model) }) { Text(stringResource(R.string.stt_model_use)) }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.stt_model_delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
                else -> TextButton(onClick = { actions.download(model) }) { Text(stringResource(R.string.stt_model_download)) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = checked, role = Role.Switch) { onChange(!checked) },
    )
}

@Composable
private fun languageName(code: String): String =
    if (code == Transcriber.AUTO) stringResource(R.string.stt_language_auto)
    else Locale.forLanguageTag(code).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }

@Composable
private fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stt_language_label)) },
        text = {
            LazyColumn {
                items(listOf(Transcriber.AUTO) + TranscriptionSettings.LANGUAGES) { code ->
                    ListItem(
                        headlineContent = { Text(languageName(code)) },
                        leadingContent = { RadioButton(selected = code == current, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(selected = code == current, role = Role.RadioButton) { onPick(code) },
                    )
                }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.action_cancel), onDismiss) },
    )
}

@Composable
private fun Footer(text: String) {
    Text(
        text = text,
        style = CTFont.caption,
        color = CTColor.textDim,
        modifier = Modifier.padding(horizontal = CTLayout.edgePad * 2, vertical = CTSpace.s),
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun TranscriptionScreenPreview() {
    TranscriptionScreen(
        state = TranscriptionUiState(
            settings = TranscriptionSettings.Values(language = "ru"),
            models = mapOf(
                WhisperModel.TINY to WhisperModels.State.Installed,
                WhisperModel.BASE to WhisperModels.State.Downloading(0.42f),
                WhisperModel.SMALL to WhisperModels.State.Absent,
            ),
            bytesOnDisk = WhisperModel.TINY.bytes,
        ),
        onNavigateBack = {},
        actions = TranscriptionActions(),
    )
}
