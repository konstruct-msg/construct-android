package com.construct.messenger.devicelink

/** A scanned text meant as a device-link code: read by the core, refused by name if it does not read. */
fun isDeviceLinkText(text: String): Boolean = text.startsWith("konstruct://link")
