package com.construct.messenger.util

import java.security.MessageDigest

/**
 * Deterministic anonymous display names for users without a username.
 *
 * Format: `"adjective animal"` (~80%) or `"adjective itNoun"` (~20%), all lowercase.
 *
 * **Canon:** iOS `ConstructMessenger/Utilities/DisplayNameGenerator.swift`. This MUST
 * produce byte-identical output to iOS for the same `userId` — the word lists and the
 * index derivation are copied verbatim. Two subtleties that differ from the older
 * `ANDROID_ONBOARDING.md` §6 draft and matter for cross-platform parity:
 *  - the 32-bit index value is treated as **unsigned** (`UInt`), not masked with `0x7FFFFFFF`;
 *  - the result is **not** capitalized.
 * Word lists were extracted directly from the iOS source. Verified by golden vectors
 * in `DisplayNameGeneratorTest`.
 */
object DisplayNameGenerator {

    private val adjectives = listOf(
        "silent", "happy", "swift", "brave", "gentle", "calm", "bright", "bold", "quick",
        "quiet", "wise", "noble", "free", "kind", "pure", "jolly", "witty", "fierce", "proud",
        "sly", "lucky", "cheerful", "jovial", "merry", "clever", "cunning", "valiant", "humble",
        "clear", "cool", "warm", "soft", "strong", "wild", "misty", "sunny", "cloudy", "starry",
        "frosty", "stormy", "ember", "blazing", "frozen", "mountain", "ocean", "river",
        "forest", "desert", "volcanic", "thunder", "solar", "lunar", "celestial", "crystal",
        "golden", "silver", "copper", "obsidian", "amber", "crimson", "azure", "verdant",
        "sleek", "sharp", "smooth", "tall", "deep", "light", "dark", "ancient", "agile",
        "majestic", "elegant", "graceful", "giant", "tiny", "nimble", "radiant", "gleaming",
        "shadowy", "whispering", "echoing", "vivid", "mystic", "hidden", "lonely", "weathered",
        "deprecated", "recursive", "async", "frozen", "nested", "compiled", "broken", "pending",
        "idle", "verbose", "headless", "orphaned", "forked", "stale", "cursed"
    )

    private val animals = listOf(
        "fox", "wolf", "bear", "lion", "tiger", "panda", "jaguar", "panther", "leopard",
        "cheetah", "lynx", "cougar", "hyena", "jackal", "dingo", "wolverine", "otter", "seal",
        "orca", "dolphin", "whale", "shark", "ferret", "mongoose", "badger", "deer", "moose",
        "elk", "bison", "hare", "rabbit", "squirrel", "beaver", "hedgehog", "bat", "boar", "ox",
        "ram", "stag", "marten", "meerkat", "eagle", "hawk", "owl", "raven", "falcon", "swan",
        "dove", "crane", "heron", "sparrow", "robin", "finch", "wren", "phoenix", "crow",
        "vulture", "albatross", "kingfisher", "kestrel", "harrier", "gull", "penguin",
        "peacock", "parrot", "hornbill", "nightjar", "dragon", "griffin", "unicorn", "pegasus",
        "basilisk", "chimera", "kraken", "hydra", "manticore", "gryphon", "yeti", "kitsune",
        "sphinx", "serpent", "cobra", "viper", "python", "rattler", "gecko", "iguana",
        "scorpion", "spider", "mantis", "beetle", "butterfly", "moth", "dragonfly", "mammoth",
        "saber", "raptor", "tricera", "rex", "titan", "direwolf"
    )

    private val itNouns = listOf(
        "printer", "keyboard", "monitor", "server", "router", "modem", "firewall", "switch",
        "hub", "rack", "cable", "dongle", "cursor", "terminal", "daemon", "kernel", "process",
        "thread", "socket", "buffer", "pointer", "callback", "semaphore", "mutex", "cron",
        "webhook", "pipeline", "protocol", "endpoint", "payload", "namespace", "instance",
        "container", "cluster", "registry", "proxy", "gateway", "runtime", "compiler",
        "debugger", "spreadsheet", "invoice", "deadline", "standup", "backlog", "ticket",
        "milestone", "stakeholder", "deployment", "outage", "rollback", "hotfix", "sprint",
        "retro", "roadmap", "handover", "escalation", "pivot"
    )

    /**
     * Stable anonymous name for [userId].
     * Returns `"adjective animal"` (~80%) or `"adjective itNoun"` (~20%), lowercase.
     */
    fun generate(userId: String): String {
        val hash = sha256(userId)
        val adjIndex = index(hash, 0, adjectives.size)
        val useITNoun = (hash[8].toUByte().toInt()) % 5 == 0
        val noun = if (useITNoun) itNouns[index(hash, 4, itNouns.size)]
                   else animals[index(hash, 4, animals.size)]
        return "${adjectives[adjIndex]} $noun"
    }

    /** First 6 hex chars of the SHA-256 of [userId] (e.g. `"a3f8c2"`). */
    fun generateShortId(userId: String): String =
        sha256(userId).take(3).joinToString("") { "%02x".format(it) }

    /** Big-endian 4-byte unsigned read at [start], mod [modulo] — matches iOS UInt32 math. */
    private fun index(bytes: ByteArray, start: Int, modulo: Int): Int {
        var value = 0u
        for (i in 0 until 4) {
            value = value or (bytes[start + i].toUByte().toUInt() shl (24 - i * 8))
        }
        return (value % modulo.toUInt()).toInt()
    }

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
}
