package com.construct.messenger.share

import android.content.Intent
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.IncomingShareStage
import com.construct.messenger.data.repository.AppLockRepository
import com.construct.messenger.data.repository.AppLockState
import com.construct.messenger.data.repository.IncomingShareRepository
import com.construct.messenger.domain.usecase.SendOutcome
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class IncomingShareRepositoryTest {
    private val crypto = CryptoManager() // Actual native codec; never a Kotlin CFE stand-in.
    private val kept = Blobs()
    private val sources = mock<IncomingShareFiles>().also {
        whenever(it.present(any())).thenReturn(true)
        whenever(it.importBudget).thenReturn(1024)
    }
    private val messages = mock<MessageStore>()
    private val gate = MutableStateFlow(AppLockState())
    private val appLock = mock<AppLockRepository>().also { whenever(it.lock).thenReturn(gate) }
    private fun repository() = IncomingShareRepository(kept, crypto, sources, messages, appLock)
    private val firstId = "10000000-0000-4000-8000-000000000001"
    private val secondId = "20000000-0000-4000-8000-000000000002"
    private fun text() = IncomingShareIntent.read(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "share canary https://example.org/"))

    @Test
    fun selectionAndRestorationKeepAUserEditableDraftNotASend() = runBlocking {
        val repo = repository()
        repo.offer(text(), firstId)
        repo.prepare()
        repo.select(firstId, "peer")
        repo.editText(firstId, "edited")
        val restored = repository()
        restored.recreatedInput(firstId)
        restored.prepare()
        assertNull(restored.state.value.problem)
        assertEquals(IncomingShareStage.READY, restored.state.value.draft!!.stage)
        assertEquals("peer", restored.state.value.draft!!.contactId)
        assertEquals("edited", restored.state.value.draft!!.text)
        verifyNoInteractions(messages)
        repo.cancel(firstId)
        val tombstone = repository().also { it.offer(text(), firstId); it.prepare() }
        assertEquals(IncomingShareStage.FINISHED, tombstone.state.value.draft!!.stage)
        assertEquals("", tombstone.state.value.draft!!.text)
    }

    @Test
    fun consentIsBoundToTheSelectedRecipientAndCannotBeSubmittedTwice() = runBlocking {
        val repo = repository()
        repo.offer(text(), firstId)
        repo.prepare()
        repo.select(firstId, "peer")
        clearInvocations(sources)
        val done = CompletableDeferred<Unit>()
        val called = AtomicInteger()
        val send: suspend () -> List<SendOutcome> = {
            called.incrementAndGet()
            done.await()
            listOf(SendOutcome.Sent("sent-1"))
        }
        whenever(messages.get("sent-1")).thenReturn(MessageRecord("sent-1", "chat", "text", true, 1, DeliveryStatus.SENT))
        assertFalse(repo.submit(firstId, "other-peer", "text", emptyList(), send))
        assertTrue(repo.submit(firstId, "peer", "text", emptyList(), send))
        assertFalse(repo.submit(firstId, "peer", "text", emptyList(), send))
        assertEquals(IncomingShareStage.SUBMITTED, repo.state.value.draft!!.stage)
        // Sources still belong to the request while the ordinary send has no durable row.
        verify(sources, never()).prune(null)
        done.complete(Unit)
        withTimeout(10_000) { repo.state.first { !it.submitting && it.draft?.stage == IncomingShareStage.FINISHED } }
        assertEquals(1, called.get())
        verify(sources, times(1)).prune(null)
        assertTrue(repo.state.value.draft!!.items.isEmpty())
        assertNull(repo.state.value.draft!!.contactId)
    }

    @Test
    fun anInterruptedSubmitNeverBecomesPermissionToReplay() = runBlocking {
        val repo = repository()
        repo.offer(text(), firstId)
        repo.prepare()
        val draft = repo.state.value.draft!!.copy(contactId = "peer", stage = IncomingShareStage.SUBMITTED)
        kept.put("incoming-share:v1", crypto.exportIncomingShare(draft))
        val restored = repository()
        restored.prepare()
        assertEquals(IncomingShareRepository.Problem.REVIEW, restored.state.value.problem)
        var sent = false
        assertFalse(restored.submit(firstId, "peer", "text", emptyList()) { sent = true; emptyList() })
        assertFalse(sent)
        verifyNoInteractions(messages)
    }

    @Test
    fun aSecondRequestRequiresAChoiceAndIdenticalContentIsNotDeduplicated() = runBlocking {
        val repo = repository()
        repo.offer(text(), firstId)
        repo.prepare()
        repo.offer(text(), secondId)
        repo.prepare()
        assertTrue(repo.state.value.replacement)
        assertEquals(firstId, repo.state.value.draft!!.id)
        repo.keepCurrent()
        assertFalse(repo.state.value.replacement)
        assertFalse(repo.hasUnpreparedInput(secondId))
        // A lost (not explicitly discarded) input has an OS pending token but no payload.
        val afterProcessDeath = repository()
        afterProcessDeath.recreatedInput(secondId)
        afterProcessDeath.prepare()
        assertEquals(IncomingShareRepository.Problem.INTERRUPTED, afterProcessDeath.state.value.problem)
        assertEquals(firstId, afterProcessDeath.state.value.draft!!.id)
        repo.offer(text(), secondId)
        repo.prepare()
        repo.replaceCurrent()
        assertEquals(secondId, repo.state.value.draft!!.id)
        assertEquals(IncomingShareStage.READY, repo.state.value.draft!!.stage)
        verifyNoInteractions(messages)
    }

    @Test
    fun aLockedAppDoesNotImportAndCorruptStateIsNotSilentlyTreatedAsEmpty() = runBlocking {
        val repo = repository()
        gate.value = AppLockState(pinEnabled = true, unlocked = false)
        kept.put("incoming-share:v1", crypto.exportIncomingShare(com.construct.messenger.data.model.IncomingShare(firstId, "keep the existing draft", stage = IncomingShareStage.READY)))
        repo.offer(text(), firstId)
        repo.prepare()
        assertEquals(0, kept.reads)
        verifyNoInteractions(sources)
        repo.cancel()
        assertEquals("cancelling gated input must not erase an unread draft", 1, kept.values.size)
        gate.value = AppLockState()
        kept.put("incoming-share:v1", byteArrayOf(1, 2, 3))
        repo.prepare()
        assertEquals(IncomingShareRepository.Problem.RESTORE, repo.state.value.problem)
        assertNull(repo.state.value.draft)
        repo.cancel()
        assertTrue(kept.values.isEmpty())
    }

    private class Blobs : SessionBlobStore {
        val values = mutableMapOf<String, ByteArray>()
        var reads = 0
        override suspend fun put(key: String, bytes: ByteArray) { values[key] = bytes.copyOf() }
        override suspend fun get(key: String): ByteArray? { reads++; return values[key]?.copyOf() }
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun withPrefix(prefix: String) = values.filterKeys { it.startsWith(prefix) }
    }
}
