package com.construct.messenger.service

/**
 * Opaque per-device copy suffix used to reject another device's ciphertext before CFE.
 * The tag is a core-derived MAC; the id contains no device identifier in clear text.
 */
internal data class DeviceCopyRoute(
    val baseMessageId: String,
    val audience: Audience,
    val tag: String,
) {
    enum class Audience { RECIPIENT, OWN_REPLICA }

    companion object {
        private val pattern = Regex("^(.+)-(fd|ss)-([0-9a-fA-F]{8}|[0-9a-fA-F]{16})(?:-c[0-9]+)?$")

        fun parse(messageId: String): DeviceCopyRoute? {
            val match = pattern.matchEntire(messageId) ?: return null
            return DeviceCopyRoute(
                baseMessageId = match.groupValues[1],
                audience = if (match.groupValues[2] == "ss") Audience.OWN_REPLICA else Audience.RECIPIENT,
                tag = match.groupValues[3].lowercase(),
            )
        }
    }
}
