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
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular

/**
 * A contact's security event, above the transcript until the user acknowledges it.
 * **Canon:** iOS `ChatKeyChangeBannerView` — Verify opens the safety numbers.
 */
@Composable
fun SecurityNoticeBanner(
    notice: SecurityNotice,
    contactName: String,
    onVerify: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    val (title, body) = when (notice) {
        SecurityNotice.NONE -> return
        SecurityNotice.ADDRESS_CHANGED ->
            stringResource(R.string.address_change_banner_title) to
                stringResource(R.string.address_change_banner_subtitle_fmt, contactName)
        SecurityNotice.NEW_DEVICE ->
            stringResource(R.string.new_device_banner_title) to
                stringResource(R.string.new_device_banner_subtitle_fmt, contactName)
    }
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
