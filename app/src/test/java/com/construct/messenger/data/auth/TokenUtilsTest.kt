package com.construct.messenger.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUtilsTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a base64url-encoded v4.public payload: nonce(32) || message || sig(64). */
    private fun buildPasetoPayload(message: String): String {
        val nonce = ByteArray(32) { 0xAB.toByte() }
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        val signature = ByteArray(64) { 0xCD.toByte() }
        val payload = nonce + messageBytes + signature
        return base64UrlEncode(payload)
    }

    /** Builds a full `v4.public.<payload>[.<footer>]` token. */
    private fun buildToken(message: String, footer: String? = null): String {
        val payloadB64 = buildPasetoPayload(message)
        return if (footer != null) {
            "v4.public.$payloadB64.$footer"
        } else {
            "v4.public.$payloadB64"
        }
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        return b64
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
    }

    // ── extractUserId ─────────────────────────────────────────────────────────

    @Test
    fun `extractUserId returns sub claim from valid token`() {
        val json = """{"sub":"550e8400-e29b-41d4-a716-446655440000","jti":"abc123","exp":2000000000}"""
        val token = buildToken(json)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", TokenUtils.extractUserId(token))
    }

    @Test
    fun `extractUserId returns null for non-PASETO token`() {
        assertNull(TokenUtils.extractUserId("not-a-paseto-token"))
        assertNull(TokenUtils.extractUserId("Bearer v4.public.xxx"))
        assertNull(TokenUtils.extractUserId("v3.public.xxx"))
    }

    @Test
    fun `extractUserId returns null for token with empty payload`() {
        assertNull(TokenUtils.extractUserId("v4.public."))
    }

    @Test
    fun `extractUserId returns null for malformed base64`() {
        assertNull(TokenUtils.extractUserId("v4.public.!!!invalid-base64!!!"))
    }

    @Test
    fun `extractUserId returns null for too-short payload`() {
        // Payload smaller than nonce(32) + signature(64) = 96 bytes
        val shortPayload = base64UrlEncode(ByteArray(10) { 0x00 })
        assertNull(TokenUtils.extractUserId("v4.public.$shortPayload"))
    }

    @Test
    fun `extractUserId returns null for bad JSON`() {
        val badJson = "this is not json"
        val token = buildToken(badJson)
        assertNull(TokenUtils.extractUserId(token))
    }

    @Test
    fun `extractUserId returns null when sub claim is missing`() {
        val json = """{"jti":"abc123","exp":2000000000}"""
        val token = buildToken(json)
        assertNull(TokenUtils.extractUserId(token))
    }

    @Test
    fun `extractUserId returns null when sub claim is empty`() {
        val json = """{"sub":"","jti":"abc123"}"""
        val token = buildToken(json)
        assertNull(TokenUtils.extractUserId(token))
    }

    @Test
    fun `extractUserId works with token that has footer`() {
        val json = """{"sub":"550e8400-e29b-41d4-a716-446655440000"}"""
        val token = buildToken(json, footer = "optional_footer_data")
        assertEquals("550e8400-e29b-41d4-a716-446655440000", TokenUtils.extractUserId(token))
    }

    @Test
    fun `extractUserId works with complex claims`() {
        val json = """{"sub":"7a9b1c3d-5e6f-4a8b-9c0d-1e2f3a4b5c6d","jti":"random-uuid","iss":"construct-server","iat":1800000000,"exp":1800086400,"device_id":"a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"}"""
        val token = buildToken(json)
        assertEquals("7a9b1c3d-5e6f-4a8b-9c0d-1e2f3a4b5c6d", TokenUtils.extractUserId(token))
    }

    // ── isValidFormat ─────────────────────────────────────────────────────────

    @Test
    fun `isValidFormat returns true for valid token`() {
        val json = """{"sub":"test-uuid"}"""
        val token = buildToken(json)
        assertTrue(TokenUtils.isValidFormat(token))
    }

    @Test
    fun `isValidFormat returns true for token with footer`() {
        val json = """{"sub":"test-uuid"}"""
        val token = buildToken(json, footer = "some-footer")
        assertTrue(TokenUtils.isValidFormat(token))
    }

    @Test
    fun `isValidFormat returns false for non-PASETO string`() {
        assertFalse(TokenUtils.isValidFormat(""))
        assertFalse(TokenUtils.isValidFormat("random-string"))
        assertFalse(TokenUtils.isValidFormat("v3.public.xxx"))
        assertFalse(TokenUtils.isValidFormat("Bearer v4.public.xxx"))
    }

    @Test
    fun `isValidFormat returns false for too-short payload`() {
        // 10 bytes < minimum 96
        val shortPayload = base64UrlEncode(ByteArray(10) { 0x00 })
        assertFalse(TokenUtils.isValidFormat("v4.public.$shortPayload"))
    }

    @Test
    fun `isValidFormat returns false for malformed base64`() {
        assertFalse(TokenUtils.isValidFormat("v4.public.!!!invalid-b64!!!"))
    }

    @Test
    fun `isValidFormat returns false for just prefix`() {
        assertFalse(TokenUtils.isValidFormat("v4.public."))
    }

    @Test
    fun `isValidFormat returns false for prefix with empty payload`() {
        assertFalse(TokenUtils.isValidFormat("v4.public..footer"))
    }
}
