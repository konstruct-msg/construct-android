package com.construct.messenger.util

import java.security.MessageDigest
import uniffi.construct_core.generatedName
import uniffi.construct_core.isGeneratedName

/**
 * Deterministic anonymous display names for users without a username: `"adjective animal"` (~80%)
 * or `"adjective itNoun"` (~20%), lowercase.
 *
 * The rule is the core's (construct-rules `names`), one for every client: the word lists and the
 * index were copied here from iOS, and into construct-client, until 2026-11-10
 * (`decisions/client-rules-shared-in-rust.md`). The golden vectors live in the core's tests.
 */
object DisplayNameGenerator {
    /** Stable anonymous name for [userId], lowercase. */
    fun generate(userId: String): String = generatedName(userId)

    /**
     * Whether [name] is only the name generated from [userId] — something any client computes for
     * itself, so it says nothing about what the person chose to be called. Any case, trimmed.
     */
    fun isGenerated(name: String, userId: String): Boolean = isGeneratedName(name, userId)

    /** First 6 hex chars of the SHA-256 of [userId] (e.g. `"a3f8c2"`). */
    fun generateShortId(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .take(3).joinToString("") { "%02x".format(it) }
}
