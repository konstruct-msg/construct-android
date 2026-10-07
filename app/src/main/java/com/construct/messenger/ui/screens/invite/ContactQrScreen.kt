package com.construct.messenger.ui.screens.invite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.invite.InviteConfig
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.InviteQrImage
import com.construct.messenger.ui.components.inviteQrSize
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.CornerRadius
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
        Rule()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = ui.displayName, style = CTFont.ui(15, FontWeight.Bold), color = CTColor.text)
                Text(
                    text = stringResource(R.string.qr_caption_trust),
                    style = CTFont.caption,
                    color = CTColor.accent.copy(alpha = 0.5f),
                )
            }
            Rule()

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                val size = inviteQrSize(maxWidth)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    val payload = ui.payload
                    if (payload != null) {
                        InviteQrImage(
                            payload = payload,
                            size = size,
                            contentDescription = stringResource(R.string.show_my_qr),
                        )
                    } else {
                        QrPlaceholder(size = size + QR_CARD_PADDING * 2, failed = ui.failed)
                    }
                    if (payload != null || ui.failed) NewCodeButton(onClick = viewModel::newCode)
                }
            }
            Rule()

            val copyLabel = when (ui.copiedCount) {
                0 -> stringResource(R.string.invite_copy_link)
                1 -> stringResource(R.string.share_copied)
                else -> stringResource(R.string.invite_copied_nth, ui.copiedCount)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { viewModel.copyLink() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val copiedAny = ui.copiedCount > 0
                val tint = if (copiedAny) CTColor.accent else CTColor.text
                Icon(
                    imageVector = if (copiedAny) Icons.Default.Check else Icons.Default.Link,
                    contentDescription = null,
                    tint = tint,
                    // SF `link` leans; Material's lies flat.
                    modifier = Modifier
                        .size(CTIcon.row)
                        .rotate(if (copiedAny) 0f else -45f),
                )
                Spacer(Modifier.width(CTSpace.s))
                Text(text = copyLabel.uppercase(), style = CTFont.caption, color = tint)
            }
            Rule()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "> " + stringResource(
                        R.string.invite_share_rule,
                        (InviteConfig.TTL_SECONDS / 3600).toInt(),
                    ),
                    style = CTFont.caption,
                    color = CTColor.textDim,
                )
                if (ui.copyFailed) {
                    Text(
                        text = "> " + stringResource(R.string.invite_create_failed),
                        style = CTFont.caption,
                        color = CTColor.danger,
                    )
                }
            }
        }
    }
}

/** iOS `Rectangle().fill(Color.CT.noise).frame(height: 1)`: edge to edge. */
@Composable
private fun Rule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CTColor.noise),
    )
}

/** Where the code will be, or why it is not: the card's size, so nothing moves when it lands. */
@Composable
private fun QrPlaceholder(size: Dp, failed: Boolean) {
    val card = RoundedCornerShape(CornerRadius.small)
    Box(
        modifier = Modifier
            .size(size)
            .clip(card)
            .background(CTColor.bgMsg)
            .border(1.dp, CTColor.noise, card),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CTSpace.s),
                modifier = Modifier.padding(horizontal = CTSpace.xl),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = CTColor.danger,
                    modifier = Modifier.size(CTIcon.nav),
                )
                Text(
                    text = stringResource(R.string.qr_failed),
                    style = CTFont.caption,
                    color = CTColor.textDim,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            CircularProgressIndicator(
                color = CTColor.textDim,
                strokeWidth = 2.dp,
                modifier = Modifier.size(CTIcon.nav),
            )
        }
    }
}

/** iOS `refreshRow`: an outlined card button, accent at 40 % for the edge. */
@Composable
private fun NewCodeButton(onClick: () -> Unit) {
    val card = RoundedCornerShape(CornerRadius.small)
    Text(
        text = stringResource(R.string.qr_new_code).lowercase(),
        style = CTFont.body,
        color = CTColor.accent,
        modifier = Modifier
            .clip(card)
            .background(CTColor.bgMsg)
            .border(1.dp, CTColor.accent.copy(alpha = 0.4f), card)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = CTSpace.s),
    )
}

private val QR_CARD_PADDING = 20.dp
