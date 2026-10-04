package com.construct.messenger.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.invite.InviteConfig
import com.construct.messenger.veil.VeilConfigLink

/**
 * The links in a message's text. **Canon:** iOS `LinkDetectingText` — `konstruct://` and
 * `https://…` (konstruct.cc included); nothing else is a link. Unlike iOS, closing punctuation
 * is left out of the link: "see https://konstruct.cc/add?invite=…." must not end in a dot.
 */
object MessageLinks {
    data class Found(val range: IntRange, val url: String)

    private val LINK = Regex("""(?:konstruct://|https?://)[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""")

    /** Characters a sentence puts after a link and a URL rarely ends with. */
    private const val TRAILING = ".,;:!?'"

    fun find(text: String): List<Found> = LINK.findAll(text).mapNotNull { m ->
        var end = m.range.last
        while (end > m.range.first && (text[end] in TRAILING || unbalancedClose(text, m.range.first, end))) end--
        val url = text.substring(m.range.first, end + 1)
        // A scheme with nothing after it is not a link.
        if (url.substringAfter("://").isEmpty()) null else Found(m.range.first..end, url)
    }.toList()

    /** A `)` closing a bracket opened before the link, as in "(https://…)". */
    private fun unbalancedClose(text: String, start: Int, end: Int): Boolean {
        if (text[end] != ')') return false
        val inside = text.substring(start, end + 1)
        return inside.count { it == ')' } > inside.count { it == '(' }
    }

    /** Ours to open — an invite or a VEIL front link — rather than the browser's. */
    fun isOwn(url: String): Boolean = InviteConfig.isInviteLink(url) || VeilConfigLink.isLink(url)
}

private const val LINK_TAG = "url"

/**
 * Message text with its links tappable. Text without a link is a plain [Text], so a bubble's own
 * long press and double tap reach it as before. With links, the text takes the gestures itself and
 * passes [onLongPress] and [onDoubleTap] on; a tap outside a link does nothing, as on the bubble.
 */
@Composable
fun LinkedText(
    text: String,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val links = remember(text) { MessageLinks.find(text) }
    if (links.isEmpty()) {
        Text(text = text, style = style, color = color, modifier = modifier)
        return
    }
    val annotated = remember(text, linkColor) { annotate(text, links, linkColor) }
    val context = LocalContext.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val longPress by rememberUpdatedState(onLongPress)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    Text(
        text = annotated,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures(
                onTap = { pos ->
                    val offset = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                    annotated.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()
                        ?.let { open(context, it.item) }
                },
                onLongPress = { longPress() },
                onDoubleTap = { doubleTap() },
            )
        },
    )
}

private fun annotate(text: String, links: List<MessageLinks.Found>, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
        for (link in links) {
            addStyle(linkStyle, link.range.first, link.range.last + 1)
            addStringAnnotation(LINK_TAG, link.url, link.range.first, link.range.last + 1)
        }
    }

/** Ours into this app — `MainActivity` takes it as it takes a link tapped anywhere else; the rest
 * to whatever the system opens it with. */
private fun open(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    if (MessageLinks.isOwn(url)) intent.setPackage(context.packageName)
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("LinkedText", "nothing opens ${url.substringBefore("://")}:// links")
    }
}
