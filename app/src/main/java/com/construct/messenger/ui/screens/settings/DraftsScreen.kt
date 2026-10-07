package com.construct.messenger.ui.screens.settings

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.Draft
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.viewmodel.DraftsViewModel
import java.util.Date

@Composable
fun DraftsRoute(
    onNavigateBack: () -> Unit,
    viewModel: DraftsViewModel = hiltViewModel(),
) {
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    DraftsScreen(
        drafts = drafts,
        onNavigateBack = onNavigateBack,
        onSave = viewModel::add,
        onDelete = viewModel::delete,
    )
}

/**
 * Notes kept on this device only.
 *
 * **Canon:** iOS `DraftsView` — an editor, "Save draft", then the list newest first (three lines
 * and the date each); a swipe removes one. Empty, it says the drafts stay on this device.
 */
@Composable
private fun DraftsScreen(
    drafts: List<Draft>,
    onNavigateBack: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val canSave = text.isNotBlank()
    val shape = RoundedCornerShape(CornerRadius.small)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        CTNavBar(
            title = stringResource(R.string.drafts_title),
            showBack = true,
            onBack = onNavigateBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(CTSpace.l),
            verticalArrangement = Arrangement.spacedBy(CTSpace.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(CTSpace.s)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = CTFont.ui(14).copy(color = CTColor.text),
                    cursorBrush = SolidColor(CTColor.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        // iOS: the editor takes its 180 maximum, plus 8 of padding each side.
                        .height(196.dp)
                        .clip(shape)
                        .background(CTColor.bgMsg)
                        .border(1.dp, CTColor.noise, shape)
                        .padding(CTSpace.s),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(CTColor.bgMsg)
                        .border(1.dp, CTColor.accent, shape)
                        .clickable(enabled = canSave) {
                            onSave(text)
                            text = ""
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.drafts_save),
                        style = CTFont.ui(14),
                        color = if (canSave) CTColor.text else CTColor.textDim,
                    )
                }
            }

            if (drafts.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.drafts_stored_locally),
                        // iOS `.footnote`: the system face, not the chrome's mono.
                        style = CTFont.body,
                        color = CTColor.textDim,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(drafts, key = { it.id }) { draft ->
                        DraftRow(draft = draft, onDelete = { onDelete(draft.id) })
                        CTSep()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftRow(draft: Draft, onDelete: () -> Unit) {
    val context = LocalContext.current
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            value == SwipeToDismissBoxValue.EndToStart
        },
    )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CTColor.danger)
                    .padding(horizontal = CTSpace.l),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(text = stringResource(R.string.delete), style = CTFont.body, color = CTColor.outMsgTextDark)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CTColor.bg)
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = draft.text,
                // What a person wrote: in the face they chose for messages (iOS DraftsView).
                style = CTFont.message(15),
                color = CTColor.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = DateFormat.getMediumDateFormat(context).format(Date(draft.createdAt)),
                style = CTFont.secondary,
                color = CTColor.textDim,
            )
        }
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, heightDp = 700, widthDp = 360)
@Composable
private fun DraftsScreenPreview() {
    DraftsScreen(
        drafts = listOf(
            Draft("1", "Remember to verify safety numbers with Alex", 1_759_100_000_000),
            Draft("2", "Invite link for the team — send tomorrow", 1_759_000_000_000),
        ),
        onNavigateBack = {},
        onSave = {},
        onDelete = {},
    )
}
