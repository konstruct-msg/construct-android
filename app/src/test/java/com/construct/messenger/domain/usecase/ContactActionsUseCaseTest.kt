package com.construct.messenger.domain.usecase

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.PeerDeviceRegistry
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.local.ChatStore
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.local.db.PeerDeviceEntity
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.util.ConversationId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uniffi.construct_core.CfeSecureStoreSlot

class ContactActionsUseCaseTest {

    private val me = "00000000-0000-0000-0000-000000000001"
    private val peer = "00000000-0000-0000-0000-000000000002"
    private val devices = listOf("a".repeat(32), "b".repeat(32))

    private val keystore = mock<KeystoreManager>().also { whenever(it.getUserId()).thenReturn(me) }
    private val users = mock<ContactStore>()
    private val chats = mock<ChatStore>()
    private val messages = mock<MessageStore>()
    private val registry = mock<PeerDeviceRegistry>()
    private val crypto = mock<CryptoManager>()
    private val sessions = mock<SessionStateStore>()

    private val actions = ContactActionsUseCase(keystore, users, chats, messages, registry, crypto, sessions, mock())

    /**
     * Every device of theirs is forgotten in the core and its stored blob removed — the blob alone
     * would restore the session on the next launch. Devices are read before the row goes.
     * Mutation: skip the blob delete — this reddens.
     */
    @Test
    fun `removing a contact forgets every device session and deletes its rows`() = runTest {
        whenever(registry.knownDevices(peer)).thenReturn(
            devices.map { PeerDeviceEntity(peer, it, ByteArray(32), firstSeenAtMs = 0, lastSeenAtMs = 0) },
        )

        actions.delete(peer)

        val chatId = ConversationId.direct(me, peer)
        verify(messages).deleteChat(chatId)
        verify(chats).delete(chatId)
        inOrder(registry, users) {
            verify(registry).knownDevices(peer)
            verify(users).delete(peer)
        }
        devices.forEach { device ->
            verify(crypto).forgetContactState(device)
            verify(sessions).saveSecureStore(eq(CfeSecureStoreSlot.Session(device)), argThat { isEmpty() })
        }
    }

    /**
     * Deleting a chat takes the chat, its messages and every device session, and keeps the
     * contact (iOS: the contact lives on in Synaps). Mutation: delete the user row — this reddens.
     */
    @Test
    fun `deleting a chat keeps the contact and forgets its sessions`() = runTest {
        whenever(registry.knownDevices(peer)).thenReturn(
            devices.map { PeerDeviceEntity(peer, it, ByteArray(32), firstSeenAtMs = 0, lastSeenAtMs = 0) },
        )

        actions.deleteChat(peer)

        val chatId = ConversationId.direct(me, peer)
        verify(messages).deleteChat(chatId)
        verify(chats).delete(chatId)
        verify(users, never()).delete(any())
        devices.forEach { device ->
            verify(crypto).forgetContactState(device)
            verify(sessions).saveSecureStore(eq(CfeSecureStoreSlot.Session(device)), argThat { isEmpty() })
        }
    }

    /** Local only, trimmed; blank is "no name", not an empty one. Mutation: store it as typed — this reddens. */
    @Test
    fun `a local name is stored trimmed and a blank one clears it`() = runTest {
        val store = FakeContactStore().also { it.rows[peer] = ContactRecord(id = peer, isContact = true) }
        val actions = ContactActionsUseCase(keystore, store, chats, messages, registry, crypto, sessions, mock())
        actions.setLocalName(peer, "  Kostya ")
        assertEquals("Kostya", store.rows[peer]!!.localAlias)
        actions.setLocalName(peer, "   ")
        assertNull(store.rows[peer]!!.localAlias)
    }
}
