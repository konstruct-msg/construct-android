package com.construct.messenger.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTTextField
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.HairlineBorder
import com.construct.messenger.viewmodel.RestoreAccountViewModel

/**
 * Sign in to an existing account with its recovery phrase.
 *
 * **Canon:** iOS `RecoveryEntryView` — instructions, the account field under its label, the
 * phrase as a two-column grid of twelve numbered cells, then "Restore Account" as an outlined
 * button. Differences, on purpose: the whole phrase pasted into any cell fills the grid; iOS
 * shows "invalid phrase" under an empty grid, Android only once the core has refused the words;
 * and the line about other devices being signed out is kept — it is what restoring does.
 */
@Composable
fun RestoreAccountScreen(
    onBack: () -> Unit,
    onRestored: () -> Unit,
    viewModel: RestoreAccountViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(ui.done) {
        if (ui.done) onRestored()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        CTNavBar(title = stringResource(R.string.restore_nav_title), showBack = true, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CTLayout.sectionGap, vertical = CTLayout.sectionGap),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.restore_body), style = CTFont.ui(14), color = CTColor.textDim)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.restore_identifier_label), style = CTFont.secondary, color = CTColor.textDim)
                CTTextField(
                    placeholder = stringResource(R.string.restore_identifier_placeholder),
                    value = ui.identifier,
                    onValueChange = viewModel::setIdentifier,
                )
            }
            Text(stringResource(R.string.restore_phrase_label), style = CTFont.secondary, color = CTColor.textDim)
            WordGrid(words = ui.words, onWord = viewModel::setWord)
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(CTSpace.s)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = CTColor.danger, modifier = Modifier.size(CTIcon.row))
                Text(stringResource(R.string.restore_other_devices), style = CTFont.secondary, color = CTColor.textDim)
            }
            ui.errorRes?.let { Text(stringResource(it), style = CTFont.secondary, color = CTColor.danger) }
            if (ui.working) {
                CircularProgressIndicator(color = CTColor.accent, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                val enabled = ui.canSubmit
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CTColor.bgMsg)
                        .border(1.dp, if (enabled) CTColor.accent else CTColor.noise)
                        .clickable(enabled = enabled, onClick = viewModel::submit)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.restore_action),
                        style = CTFont.body,
                        color = if (enabled) CTColor.text else CTColor.textDim,
                    )
                }
            }
        }
    }
}

/**
 * iOS `LazyVGrid` of two flexible columns, 8 apart: each cell "n." and the word, compact input
 * chrome. The keyboard's next action moves to the following cell.
 */
@Composable
private fun WordGrid(words: List<String>, onWord: (Int, String) -> Unit) {
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.indices.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { index ->
                    val last = index == words.lastIndex
                    val shape = RoundedCornerShape(CornerRadius.small)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(shape)
                            .background(CTColor.bgMsg)
                            .border(HairlineBorder, CTColor.noise, shape)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${index + 1}.",
                            style = CTFont.caption,
                            color = CTColor.textDim,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(20.dp),
                        )
                        BasicTextField(
                            value = words[index],
                            onValueChange = { typed ->
                                onWord(index, typed)
                                // A space after one word finishes it: typing the phrase word by
                                // word walks the grid. A pasted phrase is spread by the model.
                                val oneWord = typed.isNotBlank() && typed.trim().none { it.isWhitespace() }
                                if (oneWord && typed.last().isWhitespace() && !last) {
                                    focus.moveFocus(FocusDirection.Next)
                                }
                            },
                            singleLine = true,
                            textStyle = CTFont.ui(14).copy(color = CTColor.text),
                            cursorBrush = SolidColor(CTColor.accent),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrect = false,
                                keyboardType = KeyboardType.Password,
                                imeAction = if (last) ImeAction.Done else ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focus.moveFocus(FocusDirection.Next) },
                                onDone = { focus.clearFocus() },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
