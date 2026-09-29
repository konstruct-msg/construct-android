package com.construct.messenger.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.diagnostics.Diagnostics
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctRegular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The log files: share them, clear them, read the last lines. **Canon:** iOS `DiagnosticsView`,
 * its log half; debug builds only, as the collector itself.
 */
@Composable
fun DiagnosticsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableLongStateOf(-1L) }
    var recent by remember { mutableStateOf(emptyList<String>()) }
    suspend fun refresh() {
        bytes = withContext(Dispatchers.IO) { Diagnostics.collector?.totalBytes() ?: 0L }
        recent = Diagnostics.collector?.recentLines(RECENT_LINES).orEmpty()
    }
    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(title = stringResource(R.string.diagnostics_logs), showBack = true, onBack = onNavigateBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            CTSettingsSectionHeader(title = stringResource(R.string.diagnostics_title), color = DEBUG_ORANGE)
            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.diagnostics_share_logs).uppercase(),
                    value = if (bytes >= 0) Formatter.formatShortFileSize(context, bytes) else "",
                    valueColor = CTColor.textDim,
                    icon = Icons.Default.Share,
                    modifier = Modifier.clickable {
                        scope.launch {
                            withContext(Dispatchers.IO) { Diagnostics.collector?.flush() }
                            Diagnostics.share(context)
                        }
                    },
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.diagnostics_clear_logs).uppercase(),
                    icon = Icons.Default.Delete,
                    isDestructive = true,
                    modifier = Modifier.clickable {
                        scope.launch {
                            withContext(Dispatchers.IO) { Diagnostics.collector?.clear() }
                            refresh()
                        }
                    },
                )
            }

            if (recent.isNotEmpty()) {
                CTSettingsSectionHeader(title = stringResource(R.string.diagnostics_recent_logs), color = DEBUG_ORANGE)
                CTSectionGroup {
                    SelectionContainer {
                        Text(
                            text = recent.asReversed().joinToString("\n"),
                            style = ctRegular(10),
                            color = CTColor.text,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Newest first, as many as the collector keeps in memory. */
private const val RECENT_LINES = 60

/** iOS marks debug-only surfaces `.orange`. */
internal val DEBUG_ORANGE = Color(0xFFFF9500)
