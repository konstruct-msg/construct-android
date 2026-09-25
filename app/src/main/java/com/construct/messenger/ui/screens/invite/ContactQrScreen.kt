package com.construct.messenger.ui.screens.invite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.invite.InviteConfig
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.InviteQrImage
import com.construct.messenger.ui.components.inviteQrSize
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.ContactQrViewModel

/**
 * My invite: a QR that refreshes itself, "new code", and "copy link".
 *
 * **Canon:** iOS `ContactQRCodeView` — one surface for inviting someone.
 */
@Composable
fun ContactQrScreen(
    onNavigateBack: () -> Unit,
    viewModel: ContactQrViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    // Mint only while visible: STARTED → rotate, STOPPED → stop.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startRotating()
                Lifecycle.Event.ON_STOP -> viewModel.stopRotating()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopRotating()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.copiedLinks.collect { clipboard.setText(AnnotatedString(it)) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(
            title = stringResource(R.string.invite_title),
            showBack = true,
            onBack = onNavigateBack,
        )
        CTSep()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.padding(vertical = Spacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.compact),
            ) {
                Text(text = ui.displayName, style = ctBold(15), color = CTColor.text)
                Text(
                    text = stringResource(R.string.qr_caption_trust),
                    style = ctRegular(12),
                    color = CTColor.accent.copy(alpha = 0.5f),
                )
            }
            CTSep()

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                val size = inviteQrSize(maxWidth)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.large - Spacing.compact),
                ) {
                    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                        val payload = ui.payload
                        when {
                            payload != null -> InviteQrImage(
                                payload = payload,
                                size = size,
                                contentDescription = stringResource(R.string.show_my_qr),
                            )
                            ui.failed -> Text(
                                text = stringResource(R.string.qr_failed),
                                style = ctRegular(13),
                                color = CTColor.textDim,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .clickable(onClick = viewModel::newCode)
                            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = CTColor.accent,
                            modifier = Modifier.size(CTLayout.navIconSize),
                        )
                        Spacer(Modifier.width(Spacing.small))
                        Text(
                            text = stringResource(R.string.qr_new_code).lowercase(),
                            style = ctRegular(13),
                            color = CTColor.accent,
                        )
                    }
                }
            }
            CTSep()

            val copyLabel = when (ui.copiedCount) {
                0 -> stringResource(R.string.invite_copy_link)
                1 -> stringResource(R.string.share_copied)
                else -> stringResource(R.string.invite_copied_nth, ui.copiedCount)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = viewModel::copyLink),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val copiedAny = ui.copiedCount > 0
                val tint = if (copiedAny) CTColor.accent else CTColor.text
                Icon(
                    imageVector = if (copiedAny) Icons.Default.Check else Icons.Default.Link,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(CTLayout.navIconSize),
                )
                Spacer(Modifier.width(Spacing.small))
                Text(text = copyLabel.uppercase(), style = ctRegular(12), color = tint)
            }
            CTSep()

            Text(
                text = "> " + stringResource(
                    R.string.invite_share_rule,
                    (InviteConfig.TTL_SECONDS / 3600).toInt(),
                ),
                style = ctRegular(12),
                color = CTColor.textDim,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CTLayout.edgePad, vertical = Spacing.medium),
            )
        }
    }
}
