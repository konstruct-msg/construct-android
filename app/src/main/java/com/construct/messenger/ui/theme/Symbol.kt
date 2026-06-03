package com.construct.messenger.ui.theme

object CTSymbol {
    const val back = "[←]"
    const val forward = "[→]"
    const val close = "[×]"
    const val add = "[+]"
    const val ok = "[✓]"
    const val callOut = "[↗]"
    const val callEnd = "[end]"
    const val callAnswer = "[ans]"
    const val key = "[key]"
    const val lock = "[lock]"
    const val biometric = "[bio]"
    const val deviceIOS = "[iOS]"
    const val deviceMac = "[mac]"
    const val deviceAndroid = "[drd]"
    const val deviceGeneric = "[dev]"
    const val refresh = "[↺]"
    const val upload = "[↑]"
    const val download = "[↓]"
    const val loading = "[···]"
    const val ttl = "[ttl]"
    const val log = "[log]"
    const val disk = "[disk]"
    const val image = "[img]"
    const val send = "[→]"
    const val mic = "[mic]"
    const val settings = "[cfg]"
    const val profile = "[usr]"
    const val search = "[?]"

    // Separators (CTSep) — ASCII dashed/double lines.
    fun thin(count: Int = 25) = "- ".repeat(count)
    fun thick(count: Int = 25) = "= ".repeat(count)
}