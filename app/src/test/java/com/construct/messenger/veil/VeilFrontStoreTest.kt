package com.construct.messenger.veil

import com.construct.messenger.data.local.KeystoreManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** What a learned front may be, how the set grows, and that none ships in the app (TODO 103). */
class VeilFrontStoreTest {
    private val pin = "ab".repeat(32)

    @Test
    fun `a front is normalised, and what is not one is refused`() {
        val front = VeilFrontRules.normalize(" Front.Example:443 ", "", pin.uppercase(), 1)!!
        assertEquals(LearnedFront("front.example:443", "front.example", pin, 1), front)
        assertEquals("::1", VeilFrontRules.hostOf("[::1]:443"))
        // Mutation: accept any 64 characters — a pin that cannot match is stored; these redden.
        assertNull(VeilFrontRules.normalize("f.example:443", "", pin.dropLast(1), 1))
        assertNull(VeilFrontRules.normalize("f.example:443", "", "ａ" + pin.drop(1), 1))
        assertNull(VeilFrontRules.normalize("f.example", "", pin, 1))
        assertNull(VeilFrontRules.normalize("f.example:0", "", pin, 1))
    }

    @Test
    fun `the newest front leads, an address is held once, and the set is bounded`() {
        var set = emptyList<LearnedFront>()
        for (i in 1..10) set = VeilFrontRules.merge(set, VeilFrontRules.normalize("f$i.example:443", "", pin, i.toLong())!!)
        assertEquals(VeilFrontRules.MAX_ENTRIES, set.size)
        assertEquals("f10.example:443", set.first().address)
        set = VeilFrontRules.merge(set, VeilFrontRules.normalize("f5.example:443", "", "cd".repeat(32), 20)!!)
        assertEquals(1, set.count { it.address == "f5.example:443" })
        assertEquals("cd".repeat(32), set.first().spki)
    }

    @Test
    fun `the store reads back what it pinned, newest first`() {
        var stored: String? = null
        val keystore = mock<KeystoreManager>().also { k ->
            whenever(k.veilLearnedFronts()).doAnswer { stored }
            whenever(k.saveVeilLearnedFronts(any())).doAnswer { stored = it.getArgument(0); Unit }
        }
        val store = VeilFrontStore(keystore)
        assertNull(store.preferred())
        assertTrue(store.save("a.example:443", "", pin, nowMs = 1))
        assertTrue(store.save("b.example:443", "cdn.example", pin, nowMs = 2))
        assertEquals(VeilRelay("b.example:443", "cdn.example", pin), store.preferred())
        store.remove("B.example:443")
        assertEquals("a.example:443", store.preferred()?.address)
        assertTrue(!store.save("c.example:443", "", "nope"))
        stored = "not json"
        assertNull(store.preferred())
    }

    /**
     * No front ships inside the app: a name in the APK is known to whoever unpacks it, and the one
     * Android carried was retired for that (`decisions/no-bundled-veil-fronts.md`). Fails when a
     * front is written into the sources again, so that doing it is a decision, not an edit.
     * **Canon:** iOS `VeilBundledFrontPolicyTests`.
     */
    @Test
    fun `no front is bundled`() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("sources not found from ${File(".").absolutePath}", sources.size > 50)
        val literal = Regex("""VeilRelay\(\s*(address\s*=\s*)?"""")
        val bundled = sources.filter { literal.containsMatchIn(it.readText()) || it.readText().contains("VeilSeeds") }
        assertEquals("a front written into the app: ${bundled.map { it.name }}", emptyList<File>(), bundled)
    }
}
