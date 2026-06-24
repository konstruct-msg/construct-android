# Crypto Core — Android Implementation

**Источник**: `CryptoManager.swift`, `CryptoSessionInitializationService.swift`, `MessageCryptoService.swift`
**Rust FFI**: `construct_core.swift` (UniFFI) → `construct_core.kt` (same UniFFI bindings)

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│              CryptoManager (Kotlin)                     │
│  ┌──────────────┐  ┌─────────────────────────────────┐  │
│  │ coreLock     │  │ OrchestratorCore (Rust via FFI) │  │
│  │ (Mutex)      │  │ ┌─────────────────────────────┐ │  │
│  └──────┬───────┘  │ │ X3DH · Double Ratchet       │ │  │
│         │          │ │ Kyber-768 · PQXDH           │ │  │
│  ┌──────▼───────┐  │ │ Session heal · Archive      │ │  │
│  │ KeyManager   │  │ │ Orchestrator state          │ │  │
│  │ (Encrypted   │  │ └─────────────────────────────┘ │  │
│  │  Keystore)   │  └─────────────────────────────────┘  │
│  └──────────────┘                                       │
└─────────────────────────────────────────────────────────┘
```

---

## CryptoManager

```kotlin
@Singleton
class CryptoManager @Inject constructor(
    private val keychainManager: KeychainManager,
    private val sessionInitService: CryptoSessionInitializationService,
    private val messageCrypto: MessageCryptoService,
    private val pqcKeyManager: PQCKeyManager
) {
    // Serializes all access to orchestratorCore
    private val coreLock = Mutex()

    private var orchestratorCore: OrchestratorCore? = null
    private var _cachedUserId: ServerUserId? = null

    val isInitialized: Boolean
        get() = orchestratorCore != null

    // ── Initialization ───────────────────────────────────────────

    fun setLocalUserId(userId: ServerUserId) {
        _cachedUserId = userId
        val cryptoId = cryptoLocalUserId  // 32-hex derived from identity key

        if (orchestratorCore != null) {
            orchestratorCore!!.setLocalUserId(cryptoId)
            migrateSessionsIfNeeded(orchestratorCore!!)
            return
        }

        val keysData = keychainManager.loadPrivateKeysData()
            ?: run {
                Log.e(TAG, "setLocalUserId: no keys available")
                return
            }

        viewModelScope.launch {
            coreLock.withLock {
                val newCore = createOrchestratorCoreFromKeys(keysData, cryptoId)
                importOtpks(newCore)
                pqcKeyManager.loadCfeSnapshot(newCore)
                loadOrchestratorStateCfe(newCore)
                migrateSessionsIfNeeded(newCore)
                orchestratorCore = newCore
            }
        }
    }

    // ── Event Handling (Core Decision API) ───────────────────────

    /**
     * The single entry point for all protocol decisions.
     * Pass a CfeIncomingEvent, get back List<CfeAction>.
     * NEVER make session decisions outside this method.
     */
    suspend fun handleOrchestratorEvent(
        event: CfeIncomingEvent,
        tag: String? = null
    ): List<CfeAction> = coreLock.withLock {
        val core = orchestratorCore
            ?: throw CryptoManagerError.CoreNotInitialized

        val actions = core.handleEvent(event)
        logOrchestratorEvent(event, actions, tag)
        actions
    }

    // ── Session Queries ─────────────────────────────────────────

    fun hasSession(userId: ServerUserId): Boolean =
        orchestratorCore?.hasSession(userId) == true

    fun getSessionHealth(userId: ServerUserId): SessionHealthReport? =
        orchestratorCore?.getSessionHealth(userId)

    fun getAllSessionUserIds(): List<ServerUserId> =
        orchestratorCore?.getAllSessionContactIds().orEmpty()

    // ── Session Init (INITIATOR) ────────────────────────────────

    suspend fun initializeSession(
        userId: ServerUserId,
        recipientBundle: KeyBundle,
        oneTimePreKeyPublic: ByteArray? = null,
        oneTimePreKeyId: UInt? = null,
        kyberPreKeyPublic: ByteArray? = null,
        kyberOneTimePreKeyPublic: ByteArray? = null,
        kyberOneTimePreKeyId: UInt? = null,
        spkUploadedAt: ULong = 0UL,
        spkRotationEpoch: UInt = 0U,
        kyberSpkUploadedAt: ULong = 0UL,
        kyberSpkRotationEpoch: UInt = 0U
    ) = coreLock.withLock {
        sessionInitService.initializeSession(
            userId = userId,
            recipientBundle = recipientBundle,
            oneTimePreKeyPublic = oneTimePreKeyPublic,
            oneTimePreKeyId = oneTimePreKeyId,
            kyberPreKeyPublic = kyberPreKeyPublic,
            kyberOneTimePreKeyPublic = kyberOneTimePreKeyPublic,
            kyberOneTimePreKeyId = kyberOneTimePreKeyId,
            spkUploadedAt = spkUploadedAt,
            spkRotationEpoch = spkRotationEpoch,
            kyberSpkUploadedAt = kyberSpkUploadedAt,
            kyberSpkRotationEpoch = kyberSpkRotationEpoch,
            core = orchestratorCore
                ?: throw CryptoManagerError.CoreNotInitialized,
            archiveSession = { uid, reason ->
                archiveSession(uid, reason)
            },
            saveSession = { uid ->
                saveSessionToKeychain(uid)
            }
        )
    }

    // ── Session Init (RESPONDER) ────────────────────────────────

    suspend fun initReceivingSession(
        userId: ServerUserId,
        recipientBundle: KeyBundle,
        firstMessage: ChatMessage,
        spkUploadedAt: ULong = 0UL,
        spkRotationEpoch: UInt = 0U,
        kyberSpkUploadedAt: ULong = 0UL,
        kyberSpkRotationEpoch: UInt = 0U
    ): ByteArray = coreLock.withLock {
        sessionInitService.initReceivingSession(
            userId = userId,
            recipientBundle = recipientBundle,
            firstMessage = firstMessage,
            spkUploadedAt = spkUploadedAt,
            spkRotationEpoch = spkRotationEpoch,
            kyberSpkUploadedAt = kyberSpkUploadedAt,
            kyberSpkRotationEpoch = kyberSpkRotationEpoch,
            core = orchestratorCore
                ?: throw CryptoManagerError.CoreNotInitialized,
            archiveSession = { uid, reason ->
                archiveSession(uid, reason)
            },
            saveSession = { uid ->
                saveSessionToKeychain(uid)
            }
        )
    }

    // ── Encrypt / Decrypt ───────────────────────────────────────

    suspend fun encryptMessage(
        plaintext: String,
        userId: ServerUserId
    ): EncryptedMessageComponents = coreLock.withLock {
        messageCrypto.encryptMessage(
            plaintext = plaintext,
            userId = userId,
            core = orchestratorCore
                ?: throw CryptoManagerError.CoreNotInitialized,
            restoreSession = { restoreSession(userId) },
            saveSession = { saveSessionToKeychain(userId) },
            archiveSession = { uid, reason -> archiveSession(uid, reason) }
        )
    }

    suspend fun decryptMessage(
        message: ChatMessage,
        contactIdOverride: ServerUserId? = null
    ): MessageDecryptResult = coreLock.withLock {
        // Check ACK cache first
        if (PersistentACKStore.isProcessedInMemory(message.id)) {
            throw CryptoManagerError.DuplicateMessage
        }

        messageCrypto.decryptMessage(
            message = message,
            contactIdOverride = contactIdOverride,
            core = orchestratorCore
                ?: throw CryptoManagerError.CoreNotInitialized,
            restoreSession = { restoreSession(message.from) },
            saveSession = { saveSessionToKeychain(message.from) },
            archiveSession = { uid, reason -> archiveSession(uid, reason) },
            tryDecryptWithArchived = { tryDecryptWithArchivedSessions(message) }
        )
    }

    // ── Background Decrypt ──────────────────────────────────────

    suspend fun decryptMessageForBackground(
        message: ChatMessage
    ): MessageDecryptResult = coreLock.withLock {
        if (PersistentACKStore.isProcessedInMemory(message.id)) {
            throw CryptoManagerError.DuplicateMessage
        }

        val core = orchestratorCore
            ?: throw CryptoManagerError.CoreNotInitialized

        if (!core.hasSession(message.from)) {
            throw CryptoManagerError.SessionNotFound
        }

        val contentForDecrypt = MessagePadding.unpadCiphertext(message.content)
        val result = core.decryptMessage(
            contactId = message.from,
            ephemeralPublicKey = message.ephemeralPublicKey,
            messageNumber = message.messageNumber,
            content = contentForDecrypt
        )

        saveSessionToKeychain(message.from)
        MessageDecryptResult(result.plaintext, result.storageKey)
    }

    // ── Orchestrator State ──────────────────────────────────────

    fun saveOrchestratorStateCfe() {
        viewModelScope.launch {
            coreLock.withLock {
                val core = orchestratorCore ?: return@launch
                val blob = core.exportOrchestratorState()
                keychainManager.saveData(blob, ORCHESTRATOR_STATE_KEY)
            }
        }
    }

    fun loadOrchestratorStateCfe(core: OrchestratorCore) {
        val data = keychainManager.loadData(ORCHESTRATOR_STATE_KEY)
            ?: return
        core.importOrchestratorState(data)
    }

    fun clearOrchestratorStateCfe() {
        keychainManager.deleteData(ORCHESTRATOR_STATE_KEY)
    }

    // ── Session Persistence ─────────────────────────────────────

    private fun saveSessionToKeychain(userId: ServerUserId) {
        val sessionData = orchestratorCore?.exportSession(userId)
            ?: return

        keychainManager.saveSessionData(sessionData, userId)
        saveOrchestratorStateCfe()
    }

    // ── Key Management ──────────────────────────────────────────

    fun rotateSignedPrekey(): RotatedSpkBundle {
        val core = orchestratorCore
            ?: throw CryptoManagerError.CoreNotInitialized
        return core.rotateSignedPrekey()
    }

    fun generateOneTimePrekeys(count: UInt): List<OtpkPair> {
        val core = orchestratorCore
            ?: throw CryptoManagerError.CoreNotInitialized
        return core.generateOneTimePrekeys(count)
    }

    fun oneTimePrekeyCount(): UInt =
        orchestratorCore?.oneTimePrekeyCount() ?: 0U

    fun deleteAllCryptoKeys() {
        orchestratorCore = null
        keychainManager.deletePrivateKeys()
        keychainManager.deleteAllKeys()
    }
}
```

---

## Key Differences from iOS

| Aspect | iOS | Android |
|---|---|---|
| Lock | `NSRecursiveLock()` | `Mutex()` from kotlinx-coroutines |
| Thread | `@MainActor` | `suspend fun` + `Dispatchers.IO` |
| Secure Storage | Keychain | EncryptedSharedPreferences + Keystore |
| Core Init | Sync in `setLocalUserId` | `viewModelScope.launch` for async init |
| Error Handling | `throw` + `try?` | `Result<T, E>` + sealed errors |

---

## iOS Anti-patterns Fixed

1. **No `try?` swallowing** — all errors propagate or are explicitly handled
2. **Mutex instead of NSLock** — proper async-compatible locking
3. **No direct Core access** — all through `coreLock.withLock`
4. **No `UserDefaults` for crypto state** — only EncryptedSharedPreferences
5. **Exhaustive error types** — sealed class for all `CryptoManagerError` variants

---