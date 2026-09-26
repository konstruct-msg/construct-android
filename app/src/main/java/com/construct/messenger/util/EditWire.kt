package com.construct.messenger.util

import shared.proto.messaging.v1.Content.EditMessage
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.TextMessage

/**
 * 1:1 text edit — `MessageContent.edit` inside a KNST frame.
 *
 * **Canon:** iOS `ChatSendCoordinator.editMessage`. The target is the id of the
 * message row, which is the UUID from that message's KNST header. `edited_at`
 * and `edit_count` stay unset: the iOS producer does not fill them, and the
 * recipient stamps "edited" locally when it applies the text. The outer
 * envelope learns nothing about the edit.
 */
object EditWire {
    fun encode(targetMessageId: String, newText: String): ByteArray {
        val edit = EditMessage.newBuilder()
            .setTargetMessageId(targetMessageId)
            .setNewText(TextMessage.newBuilder().setText(newText))
        return MessageContent.newBuilder().setEdit(edit).build().toByteArray()
    }
}
