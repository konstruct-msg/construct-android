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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.repository.StorageSettings
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.DataStorageUiState
import com.construct.messenger.viewmodel.DataStorageViewModel

/** The limit's steps, as iOS `DataStorageSettingsView.quotaOptions`; 0 = no limit, last. */
private val LIMITS = listOf(256L shl 20, 512L shl 20, 1L shl 30, 2L shl 30, 5L shl 30, 0L)
private val LIMIT_LABELS = listOf("256 MB", "512 MB", "1 GB", "2 GB", "5 GB")
private val KEEP_DAYS = listOf(0, 7, 30, 90)

/** Above this share of the limit the bar turns to the warning colour (iOS `usageWarningThreshold`). */
private const val WARNING_SHARE = 0.85f

@Composable
fun DataStorageRoute(
    onNavigateBack: () -> Unit,
    viewModel: DataStorageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    DataStorageScreen(
        state = state,
        onNavigateBack = onNavigateBack,
        onLimit = viewModel::setLimit,
        onKeepDays = viewModel::setKeepDays,
        onClear = viewModel::clear,
    )
}

/**
 * Settings → Data & storage: what the media cache takes and clearing it, its limit, and how long
 * media is kept. Auto-download is not here yet: every media bubble downloads what it shows.
 *
 * **Canon:** iOS `DataStorageSettingsView` — the same sections and steps. The texts differ where
 * Android does: media lives in `files/`, which the system never clears, and the confirmation says
 * what clearing cannot undo.
 */
@Composable
private fun DataStorageScreen(
    state: DataStorageUiState,
    onNavigateBack: () -> Unit,
    onLimit: (Long) -> Unit,
    onKeepDays: (Int) -> Unit,
    onClear: () -> Unit,
) {
    fun size(bytes: Long) = formatBytes(bytes)
    var confirmClear by remember { mutableStateOf(false) }
    val limit = state.settings.limitBytes
    val cached = state.cachedBytes ?: 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(title = stringResource(R.string.data_and_storage), showBack = true, onBack = onNavigateBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = CTLayout.edgePad),
        ) {
            CTSettingsSectionHeader(title = stringResource(R.string.storage_media_cache))
            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.storage_media_cache),
                    value = state.cachedBytes?.let(::size).orEmpty(),
                    valueColor = if (cached > 0) MaterialTheme.colorScheme.primary else null,
                )
                if (limit > 0) {
                    val share = (cached.toFloat() / limit).coerceIn(0f, 1f)
                    Column(Modifier.padding(horizontal = CTSpace.l).padding(bottom = CTSpace.m)) {
                        LinearProgressIndicator(
                            progress = { share },
                            color = if (share > WARNING_SHARE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.storage_of_quota, size(cached), size(limit)),
                            style = CTFont.micro,
                            color = CTColor.textDim,
                            modifier = Modifier.padding(top = CTSpace.xs),
                        )
                    }
                }
                HorizontalDivider()
                CTSettingsRow(
                    label = stringResource(R.string.storage_clear_media_cache),
                    icon = Icons.Outlined.Delete,
                    isDestructive = true,
                    modifier = Modifier.clickable(enabled = cached > 0 && !state.clearing) { confirmClear = true },
                )
            }
            Footer(stringResource(R.string.storage_media_cache_footer))

            CTSettingsSectionHeader(title = stringResource(R.string.storage_limit))
            CTSectionGroup {
                LimitSlider(limit = limit, onLimit = onLimit)
            }
            Footer(stringResource(if (limit == 0L) R.string.storage_no_limit_footer else R.string.storage_limit_footer))

            CTSettingsSectionHeader(title = stringResource(R.string.storage_auto_clear))
            CTSectionGroup {
                KEEP_DAYS.forEachIndexed { i, days ->
                    if (i > 0) HorizontalDivider()
                    ChoiceRow(
                        label = stringResource(
                            when (days) {
                                7 -> R.string.storage_keep_7_days
                                30 -> R.string.storage_keep_30_days
                                90 -> R.string.storage_keep_90_days
                                else -> R.string.storage_keep_forever
                            },
                        ),
                        selected = state.settings.keepDays == days,
                        onSelect = { onKeepDays(days) },
                    )
                }
            }
            Footer(stringResource(R.string.storage_auto_clear_footer))
        }
    }

    if (confirmClear) {
        CTConfirmDialog(
            title = stringResource(R.string.storage_clear_confirm_title),
            message = stringResource(R.string.storage_clear_confirm_message),
            confirmLabel = stringResource(R.string.storage_clear_media_cache),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { confirmClear = false; onClear() },
            onDismiss = { confirmClear = false },
            isDestructive = true,
        )
    }
}

/** The limit as a stepped slider: 256 MB … 5 GB, then no limit; the steps labelled beneath. */
@Composable
private fun LimitSlider(limit: Long, onLimit: (Long) -> Unit) {
    val index = LIMITS.indexOf(limit).takeIf { it >= 0 } ?: LIMITS.lastIndex
    // The thumb moves with the finger; the setting is written when it is let go.
    var position by remember(index) { mutableFloatStateOf(index.toFloat()) }
    val shown = position.toInt().coerceIn(0, LIMITS.lastIndex)
    val noLimit = stringResource(R.string.storage_no_limit)
    val labels = LIMIT_LABELS + noLimit
    Column(Modifier.padding(horizontal = CTSpace.l, vertical = CTSpace.m)) {
        Row {
            Text(stringResource(R.string.storage_limit), style = CTFont.body, color = CTColor.textDim, modifier = Modifier.weight(1f))
            Text(labels[shown], style = CTFont.headline, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onLimit(LIMITS[position.toInt().coerceIn(0, LIMITS.lastIndex)]) },
            valueRange = 0f..LIMITS.lastIndex.toFloat(),
            steps = LIMITS.size - 2,
        )
        Row {
            labels.forEachIndexed { i, label ->
                Text(
                    text = label,
                    style = CTFont.micro,
                    color = if (i == shown) MaterialTheme.colorScheme.primary else CTColor.textDim,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}

/**
 * Bytes in the units the limit's steps are named in — binary, as iOS's — so the cache reads
 * "1 GB of 1 GB" at the limit rather than the system formatter's decimal "1.1 GB".
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.US, bytes / (1L shl 30).toDouble()).replace(".0 ", " ")
    bytes >= 1L shl 20 -> "${(bytes + (1L shl 19)) shr 20} MB"
    bytes > 0 -> "${maxOf(1, (bytes + 512) shr 10)} KB"
    else -> "0 MB"
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
private fun DataStorageScreenPreview() {
    DataStorageScreen(
        state = DataStorageUiState(
            settings = StorageSettings(limitBytes = 1L shl 30, keepDays = 30),
            cachedBytes = 912L shl 20,
        ),
        onNavigateBack = {},
        onLimit = {},
        onKeepDays = {},
        onClear = {},
    )
}
