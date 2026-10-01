@file:OptIn(ExperimentalMaterial3Api::class)

package com.construct.messenger.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.construct.messenger.R
import com.construct.messenger.data.model.MessageReaction
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.ctMedium
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.util.ReactionRules

/**
 * The quick set and a plus. **Canon:** iOS `MessageReactionCapsule` — six emoji, a dot under the
 * one already set (tapping it again takes it off), the plus opens the full picker.
 *
 * On iOS the capsule opens from the menu's React item; here it heads the long-press menu itself —
 * one tap fewer for the same choice, and a row of emoji at the top of a menu is how Android
 * messengers offer it.
 */
@Composable
fun ReactionQuickRow(current: String?, onPick: (String) -> Unit, onPickMore: () -> Unit) {
    val remove = stringResource(R.string.reaction_remove)
    val more = stringResource(R.string.reaction_pick_more)
    Row(
        modifier = Modifier.padding(horizontal = CTLayout.inlinePad, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReactionRules.QUICK_SET.forEach { emoji ->
            Column(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onPick(emoji) }
                    .semantics { contentDescription = if (current == emoji) remove else emoji },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(emoji, fontSize = 22.sp)
                Box(
                    Modifier
                        .size(4.dp)
                        .background(if (current == emoji) CTColor.accent else androidx.compose.ui.graphics.Color.Transparent, CircleShape),
                )
            }
        }
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onPickMore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AddCircleOutline, contentDescription = more, tint = CTColor.accent, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * The reactions on a message, hung off the bubble's corner. **Canon:** iOS `reactionBadgeRow` — one
 * chip, one emoji per reactor in the order they came; tapping an emoji reacts with it (or takes
 * ours off when it is ours).
 */
@Composable
fun ReactionBadgeRow(reactions: List<MessageReaction>, onTap: (String) -> Unit, modifier: Modifier = Modifier) {
    if (reactions.isEmpty()) return
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .background(CTColor.bgMsg, shape)
            .border(0.5.dp, CTColor.noise, shape),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        reactions.forEach { reaction ->
            val label = stringResource(R.string.reaction_a11y, reaction.emoji)
            Text(
                text = reaction.emoji,
                style = ctRegular(14),
                modifier = Modifier
                    .clickable { onTap(reaction.emoji) }
                    .semantics { contentDescription = label }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The full picker behind the plus. **Canon:** iOS `ReactionEmojiPickerSheet` — a grid of every emoji
 * this phone can draw, grouped. A grid and not a text field: iOS's text field showed the letter
 * keyboard, and letters went out as reactions.
 */
@Composable
fun ReactionPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val groups = remember { EmojiCatalogue.groups() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CTColor.bg) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CTLayout.hitTarget),
            modifier = Modifier.fillMaxWidth().padding(horizontal = CTLayout.edgePad),
        ) {
            groups.forEach { group ->
                item(key = group.title, span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "> " + stringResource(group.title).uppercase(),
                        style = ctMedium(11),
                        color = CTColor.textDim,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                items(group.emoji, key = { it }) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(CTLayout.hitTarget)
                            .clickable {
                                onPick(emoji)
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 28.sp)
                    }
                }
            }
        }
    }
}

/**
 * Every emoji the picker offers. **Canon:** iOS `EmojiCatalogue` — the same block ranges, filtered
 * through the same validator the wire uses, so the picker never offers something a peer refuses;
 * and through the system font, so it never offers a box.
 */
object EmojiCatalogue {
    data class Group(@StringRes val title: Int, val emoji: List<String>)

    private val RANGES: List<Pair<Int, List<IntRange>>> = listOf(
        R.string.emoji_group_smileys to listOf(0x1F600..0x1F64F, 0x1F910..0x1F92F, 0x1F970..0x1F97A),
        R.string.emoji_group_people to listOf(0x1F440..0x1F4AA, 0x1F930..0x1F93E, 0x1F9D0..0x1F9DF),
        R.string.emoji_group_nature to listOf(0x1F400..0x1F43F, 0x1F980..0x1F9AE, 0x1F330..0x1F343),
        R.string.emoji_group_food to listOf(0x1F345..0x1F37F, 0x1F950..0x1F96F),
        R.string.emoji_group_activity to listOf(0x1F380..0x1F3CA, 0x1F93F..0x1F94F),
        R.string.emoji_group_travel to listOf(0x1F680..0x1F6C5, 0x1F3E0..0x1F3F0),
        R.string.emoji_group_objects to listOf(0x1F4BB..0x1F4FF, 0x1F526..0x1F53D),
        R.string.emoji_group_symbols to listOf(0x1F500..0x1F525, 0x2764..0x2764, 0x1F4AF..0x1F4AF),
    )

    fun groups(
        props: ReactionRules.EmojiProperties = ReactionRules.EmojiProperties.Icu,
        drawable: (String) -> Boolean = { android.graphics.Paint().hasGlyph(it) },
    ): List<Group> = RANGES.mapNotNull { (title, ranges) ->
        val emoji = ranges.flatMap { it.toList() }.mapNotNull { codePoint ->
            val bare = String(Character.toChars(codePoint))
            // Older emoji are text by default and need U+FE0F to draw as emoji — ❤ above all,
            // the first of the quick set. Offer the form the validator accepts.
            val candidate = if (props.isEmojiPresentation(codePoint)) bare else bare + "️"
            candidate.takeIf { ReactionRules.isValidEmoji(it, props) && drawable(it) }
        }
        if (emoji.isEmpty()) null else Group(title, emoji)
    }
}
