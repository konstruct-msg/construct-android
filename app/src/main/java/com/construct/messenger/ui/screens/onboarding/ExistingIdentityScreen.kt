package com.construct.messenger.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.HairlineBorder

/**
 * "Already have an identity?" — how to bring it to this device.
 *
 * **Canon:** iOS `ExistingIdentityChooserView` — an intro line, then one card per way in, equal
 * weight and none preselected. iOS offers "Link this device" only in debug builds
 * (`DeviceLinkOfferPolicy`) and Android has no linking, so the release list is the same one
 * card: restore from the recovery phrase. iOS's "Can't connect?" is left out, as on the first
 * screen — Android cannot import a VEIL access code yet.
 */
@Composable
fun ExistingIdentityScreen(
    onBack: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(
            title = stringResource(R.string.onboarding_existing_title),
            showBack = true,
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CTLayout.edgePad)
                .padding(bottom = CTLayout.sectionGap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CTLayout.sectionGap),
        ) {
            Text(
                text = stringResource(R.string.onboarding_existing_intro),
                style = CTFont.body,
                color = CTColor.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = CTLayout.sectionGap)
                    .padding(top = CTLayout.sectionGap),
            )
            ChoiceCard(
                icon = Icons.Default.Key,
                title = stringResource(R.string.onboarding_restore_title),
                subtitle = stringResource(R.string.onboarding_restore_subtitle),
                onClick = onRestore,
            )
        }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(CornerRadius.small)
    Row(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(CTColor.bgMsg)
            .border(HairlineBorder, CTColor.noise, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = CTLayout.edgePad, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CTLayout.chromeGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CTColor.accent,
            // SF `key.fill` stands upright; Material's key lies on its side.
            modifier = Modifier.width(32.dp).size(CTIcon.control).rotate(90f),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(text = title.uppercase(), style = CTFont.bodyEmphasis, color = CTColor.text, letterSpacing = 1.sp)
            Text(text = subtitle, style = CTFont.caption, color = CTColor.textDim)
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = CTColor.textDim,
            modifier = Modifier.size(CTIcon.row),
        )
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 390, heightDp = 600)
@Composable
private fun ExistingIdentityScreenPreview() {
    ExistingIdentityScreen(onBack = {}, onRestore = {})
}
