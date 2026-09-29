package com.construct.messenger.data.model

/**
 * One device of this account, as Settings → Linked devices shows it.
 *
 * **Canon:** iOS `AuthServiceClient.LinkedDevice`. [name] and [platform] come from the device's own
 * sealed description (`DeviceInfo.sealed_metadata`) — the server holds neither — and are null when
 * there is no copy this device can open: one that has not published yet, or published before this
 * device was linked. The row then identifies it by [id] alone.
 */
data class LinkedDevice(
    val id: String,
    val name: String?,
    val platform: DevicePlatform?,
    /** Epoch seconds. */
    val createdAt: Long,
    val isCurrent: Boolean,
    /** The account's primary device, which `RevokeDevice` refuses to remove. */
    val isPrimary: Boolean,
) {
    /** The same eight hex characters every log line prints (iOS `DeviceRow`). */
    val shortId: String get() = id.take(8)
}

enum class DevicePlatform { IOS, ANDROID, DESKTOP, OTHER }
