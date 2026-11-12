package com.construct.messenger.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.construct.messenger.R
import com.construct.messenger.data.model.IncomingShareStage
import com.construct.messenger.data.repository.IncomingShareRepository
import com.construct.messenger.ui.components.FilterSearchBar
import com.construct.messenger.ui.navigation.Screen
import com.construct.messenger.ui.screens.chats.ChatRow
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.IncomingShareViewModel
import com.construct.messenger.viewmodel.MainViewModel

/** Over navigation, under the PIN/store failure screens. No content is composed while gated. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingShareHost(
    nav: NavHostController,
    model: IncomingShareViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val canUse by model.canUse.collectAsStateWithLifecycle()
    val auth by model.authState.collectAsStateWithLifecycle()
    val inputGeneration by model.inputChanges.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val draft = state.draft
    // The key changes only on a local request/recipient transition, not on each text edit.
    LaunchedEffect(canUse, draft?.id, draft?.contactId, draft?.stage) {
        val target = draft?.contactId ?: return@LaunchedEffect
        if (!canUse || draft.stage == IncomingShareStage.FINISHED) return@LaunchedEffect
        if (nav.currentBackStackEntry?.destination?.route == Screen.Chat.route &&
            nav.currentBackStackEntry?.arguments?.getString("contactId") == target) return@LaunchedEffect
        val route = Screen.Chat.createRoute(target)
        if (!nav.popBackStack(route, inclusive = false)) nav.navigate(route) { launchSingleTop = true }
    }
    if (!canUse) {
        // Do not confuse auth restoration in Splash with an account that is not set up.
        if (!auth.isInitialized && entry?.destination?.route == Screen.Onboarding.route && inputGeneration > 0 && model.hasInput) {
            AlertDialog(
                onDismissRequest = model::cancel,
                title = { Text(stringResource(R.string.incoming_share_title)) },
                text = { Text(stringResource(R.string.incoming_share_sign_in)) },
                confirmButton = { TextButton(onClick = model::cancel) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        return
    }
    if (state.replacement) {
        AlertDialog(
            onDismissRequest = model::keepCurrent,
            title = { Text(stringResource(R.string.incoming_share_another_title)) },
            text = { Text(stringResource(if (state.submitting) R.string.incoming_share_sending else R.string.incoming_share_another_body)) },
            confirmButton = { TextButton(onClick = model::keepCurrent) { Text(stringResource(R.string.incoming_share_keep)) } },
            dismissButton = {
                if (!state.submitting) TextButton(onClick = model::replaceCurrent) { Text(stringResource(R.string.incoming_share_replace)) }
            },
        )
        return
    }
    if (state.problem != null) {
        val problem = requireNotNull(state.problem)
        val dismiss = if (draft?.stage == IncomingShareStage.READY) model::clearProblem else model::cancel
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.incoming_share_title)) },
            text = { Text(stringResource(when (problem) {
                IncomingShareRepository.Problem.TOO_LARGE -> R.string.incoming_share_too_large
                IncomingShareRepository.Problem.INVALID -> R.string.incoming_share_invalid
                IncomingShareRepository.Problem.RESTORE -> R.string.incoming_share_restore_failed
                IncomingShareRepository.Problem.INTERRUPTED -> R.string.incoming_share_interrupted
                IncomingShareRepository.Problem.REVIEW -> R.string.incoming_share_review
                IncomingShareRepository.Problem.UNREADABLE -> R.string.incoming_share_unreadable
            })) },
            confirmButton = { TextButton(onClick = dismiss) {
                Text(stringResource(when {
                    problem == IncomingShareRepository.Problem.REVIEW -> R.string.incoming_share_review_close
                    draft?.stage == IncomingShareStage.READY -> R.string.close
                    else -> R.string.action_cancel
                }))
            } },
        )
        return
    }
    // A crash after durable consent is uncertain; never submit it again on restoration.
    if (draft?.stage == IncomingShareStage.SUBMITTED && !state.submitting) {
        AlertDialog(
            onDismissRequest = model::cancel,
            title = { Text(stringResource(R.string.incoming_share_title)) },
            text = { Text(stringResource(R.string.incoming_share_review)) },
            confirmButton = { TextButton(onClick = model::cancel) { Text(stringResource(R.string.incoming_share_review_close)) } },
        )
        return
    }
    if (draft == null || draft.stage == IncomingShareStage.FINISHED ||
        (draft.stage == IncomingShareStage.READY && draft.contactId != null) || state.submitting) return

    val chatsModel: MainViewModel = hiltViewModel()
    val chats by chatsModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable(draft.id) { mutableStateOf("") }
    val shown = chats.chats.filter { it.displayName.contains(query, true) || it.username.contains(query, true) }
    BackHandler(onBack = model::cancel)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.incoming_share_title)) },
                navigationIcon = { IconButton(onClick = model::cancel) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (state.busy || draft.stage == IncomingShareStage.IMPORTING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.incoming_share_preparing), Modifier.padding(CTLayout.edgePad))
            } else {
                Text(
                    stringResource(R.string.incoming_share_summary, draft.items.count { it.isVisual }, draft.items.count { !it.isVisual }),
                    Modifier.padding(horizontal = CTLayout.edgePad, vertical = CTSpace.s),
                )
                if (draft.text.isNotBlank()) Text(stringResource(R.string.incoming_share_has_text), Modifier.padding(horizontal = CTLayout.edgePad))
                FilterSearchBar(query, { query = it }, Modifier.padding(horizontal = CTLayout.edgePad))
                if (shown.isEmpty()) Text(stringResource(if (chats.chats.isEmpty()) R.string.incoming_share_no_chats else R.string.incoming_share_no_matches), Modifier.padding(CTLayout.edgePad))
                LazyColumn {
                    items(shown, key = { it.contactId }) { chat ->
                        ChatRow(chat, onClick = { model.choose(draft.id, chat.contactId) })
                    }
                }
            }
        }
    }
}
