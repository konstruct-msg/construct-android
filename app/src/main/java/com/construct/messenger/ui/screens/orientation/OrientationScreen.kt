package com.construct.messenger.ui.screens.orientation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTButton
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.HairlineBorder
import com.construct.messenger.ui.theme.KonstructMessengerTheme
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.OrientationViewModel
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Post-registration product orientation — the three-page "How Konstruct works" guide.
 *
 * **Canon:** iOS `ConstructMessenger/Views/Onboarding/OrientationView.swift`. Three pages
 * (Identity / People / Three places), swipeable pager, always-available Skip, page dots, and
 * a Continue → Enter Konstruct button. Not identity registration.
 *
 * [onFinished] is invoked on Skip or Enter; the caller decides where to go (first-run → Main,
 * Settings replay → back). Completion is persisted by [OrientationViewModel].
 */
@Composable
fun OrientationScreen(
    onFinished: () -> Unit,
    viewModel: OrientationViewModel = hiltViewModel(),
) {
    OrientationContent(
        onFinish = {
            viewModel.markCompleted()
            onFinished()
        },
    )
}

private const val PAGE_COUNT = 3

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrientationContent(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == PAGE_COUNT - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .systemBarsPadding(),
    ) {
        TopBar(onSkip = onFinish)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            OrientationPage(page)
        }

        BottomChrome(
            currentPage = pagerState.currentPage,
            isLastPage = isLastPage,
            onPrimary = {
                if (isLastPage) {
                    onFinish()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
        )
    }
}

// MARK: - Chrome

@Composable
private fun TopBar(onSkip: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.orientation_section_label).uppercase(),
            style = ctRegular(11),
            color = CTColor.accent,
            letterSpacing = 2.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.orientation_skip).uppercase(),
            style = ctRegular(13),
            color = CTColor.textDim,
            modifier = Modifier.clickable(onClick = onSkip),
        )
    }
}

@Composable
private fun BottomChrome(currentPage: Int, isLastPage: Boolean, onPrimary: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(PAGE_COUNT) { index ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            color = if (index == currentPage) CTColor.accent else CTColor.noise,
                            shape = CircleShape,
                        ),
                )
            }
        }
        CTButton(
            label = stringResource(
                if (isLastPage) R.string.orientation_enter else R.string.orientation_next
            ).uppercase(),
            onClick = onPrimary,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = 24.dp),
        )
    }
}

// MARK: - Pages

@Composable
private fun OrientationPage(page: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .height(160.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when (page) {
                0 -> IdentityIllustration()
                1 -> PeopleIllustration()
                else -> MapIllustration()
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        val titleRes: Int
        val bodyRes: Int
        val captionRes: Int
        when (page) {
            0 -> {
                titleRes = R.string.orientation_page1_title
                bodyRes = R.string.orientation_page1_body
                captionRes = R.string.orientation_page1_caption
            }
            1 -> {
                titleRes = R.string.orientation_page2_title
                bodyRes = R.string.orientation_page2_body
                captionRes = R.string.orientation_page2_caption
            }
            else -> {
                titleRes = R.string.orientation_page3_title
                bodyRes = R.string.orientation_page3_body
                captionRes = R.string.orientation_page3_caption
            }
        }

        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(titleRes).uppercase(),
                style = ctBold(18),
                color = CTColor.text,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(bodyRes),
                style = ctRegular(13),
                color = CTColor.text,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )
            Text(
                text = stringResource(captionRes),
                style = ctRegular(11),
                color = CTColor.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// MARK: - Illustrations

/** Pointy-top hexagon matching iOS `HexagonShape` / the CT hex-avatar language. */
private val HexagonShape = GenericShape { size, _ ->
    val r = minOf(size.width, size.height) / 2f
    val cx = size.width / 2f
    val cy = size.height / 2f
    for (i in 0 until 6) {
        val angle = (Math.PI / 3.0 * i - Math.PI / 2.0).toFloat()
        val x = cx + r * cos(angle)
        val y = cy + r * sin(angle)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
private fun IdentityIllustration() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .border(1.5.dp, CTColor.accent.copy(alpha = 0.45f), HexagonShape),
            )
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(CTColor.bgMsg, HexagonShape),
            )
            Icon(
                imageVector = Icons.Filled.Key,
                contentDescription = null,
                tint = CTColor.accent,
                modifier = Modifier.size(28.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Smartphone,
                contentDescription = null,
                tint = CTColor.textDim,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = stringResource(R.string.orientation_page1_visual_label).uppercase(),
                style = ctRegular(10),
                color = CTColor.textDim,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun PeopleIllustration() {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PathCard(
            icon = Icons.Filled.QrCode,
            titleRes = R.string.orientation_page2_path_qr_title,
            subtitleRes = R.string.orientation_page2_path_qr_sub,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.orientation_or).uppercase(),
            style = ctRegular(10),
            color = CTColor.textDim,
        )
        PathCard(
            icon = Icons.Filled.Search,
            titleRes = R.string.orientation_page2_path_search_title,
            subtitleRes = R.string.orientation_page2_path_search_sub,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PathCard(
    icon: ImageVector,
    titleRes: Int,
    subtitleRes: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(CTColor.bgMsg)
            .border(HairlineBorder, CTColor.noise)
            .padding(vertical = 16.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CTColor.accent,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = stringResource(titleRes).uppercase(),
            style = ctBold(11),
            color = CTColor.text,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(subtitleRes),
            style = ctRegular(10),
            color = CTColor.textDim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MapIllustration() {
    Column(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .padding(horizontal = 28.dp)
            .background(CTColor.bgMsg)
            .border(HairlineBorder, CTColor.noise),
    ) {
        MapRow(
            icon = Icons.Filled.Chat,
            titleRes = R.string.orientation_map_streams,
            subRes = R.string.orientation_map_streams_sub,
        )
        Divider()
        MapRow(
            icon = Icons.Filled.Groups,
            titleRes = R.string.orientation_map_synaps,
            subRes = R.string.orientation_map_synaps_sub,
        )
        Divider()
        MapRow(
            icon = Icons.Filled.Settings,
            titleRes = R.string.orientation_map_settings,
            subRes = R.string.orientation_map_settings_sub,
        )
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CTColor.noise),
    )
}

@Composable
private fun MapRow(icon: ImageVector, titleRes: Int, subRes: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CTColor.accent,
            modifier = Modifier.size(16.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(titleRes).uppercase(),
                style = ctBold(12),
                color = CTColor.text,
                letterSpacing = 1.sp,
            )
            Text(
                text = stringResource(subRes),
                style = ctRegular(11),
                color = CTColor.textDim,
            )
        }
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun OrientationScreenPreview() {
    KonstructMessengerTheme(darkTheme = true) {
        OrientationContent(onFinish = {})
    }
}
