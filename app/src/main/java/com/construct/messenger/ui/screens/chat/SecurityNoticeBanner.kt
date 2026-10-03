package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.construct.messenger.R
import com.construct.messenger.data.model.ContactTrustAlert
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular

/**
 * A contact's security event or failed KT proof, above the transcript until the user
 * acknowledges it. **Canon:** iOS `ChatKeyChangeBannerView` — Verify opens the safety numbers.
 */
@Composable
fun SecurityNoticeBanner(
    alert: ContactTrustAlert?,
    contactName: String,
    onVerify: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    val (title, body) = trustAlertText(alert ?: return, contactName)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CTColor.bgMsg)
            .padding(horizontal = CTLayout.edgePad, vertical = Spacing.standard),
        verticalArrangement = Arrangement.spacedBy(Spacing.compact),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = CTColor.danger,
                modifier = Modifier.size(CTLayout.navIconSize),
            )
            Text(title, style = ctBold(14), color = CTColor.text)
        }
        Text(body, style = ctRegular(13), color = CTColor.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            TextButton(onClick = onVerify) {
                Text(stringResource(R.string.key_change_verify), style = ctBold(13), color = CTColor.accent)
            }
            TextButton(onClick = onAcknowledge) {
                Text(stringResource(R.string.security_notice_acknowledge), style = ctRegular(13), color = CTColor.textDim)
            }
        }
    }
}

/** Title and body for [alert]. **Canon:** iOS `ContactTrustAlert.titleKey` / `subtitle`. */
@Composable
fun trustAlertText(alert: ContactTrustAlert, contactName: String): Pair<String, String> = when (alert) {
    ContactTrustAlert.ADDRESS_CHANGED ->
        stringResource(R.string.address_change_banner_title) to
            stringResource(R.string.address_change_banner_subtitle_fmt, contactName)
    ContactTrustAlert.VERIFICATION_FAILED ->
        stringResource(R.string.key_change_banner_title_failed) to
            stringResource(R.string.key_change_banner_subtitle_failed)
}
