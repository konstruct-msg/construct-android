package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace

/**
 * The session with this contact was opened from a signed prekey past the core's age limit, and
 * they have not written since. Nothing to press: it goes when they do. **Canon:** iOS
 * `ChatAtRiskBannerView`, `decisions/stale-peer-reachability.md`.
 */
@Composable
fun AtRiskSessionBanner(visible: Boolean) {
    if (!visible) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CTColor.bgMsg)
            .padding(horizontal = CTLayout.edgePad, vertical = CTLayout.chromeGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CTLayout.chromeGap),
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            tint = CTColor.warning,
            modifier = Modifier.size(CTLayout.navIconSize),
        )
        Column(verticalArrangement = Arrangement.spacedBy(CTSpace.xs)) {
            Text(stringResource(R.string.session_at_risk_title), style = CTFont.bodyEmphasis, color = CTColor.text)
            Text(stringResource(R.string.session_at_risk_subtitle), style = CTFont.caption, color = CTColor.textDim)
        }
    }
}
