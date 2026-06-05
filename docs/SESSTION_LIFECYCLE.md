# Session Lifecycle Controller — Android

**Источник**: `SessionLifecycleController.swift`, `SessionCoordinator.swift`
**Принцип**: Единственный entry point для всех session lifecycle операций

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   UI Layer                              │
│  ChatScreen · Settings · UserProfile                    │
│         │                    │                          │
│         ▼                    ▼                          │
│  ViewModel               ViewModel                      │
│         │                    │                          │
│         └────────┬───────────┘                          │
│                  ▼                                      │
│  ┌───────────────────────────────────────────────────┐  │
│  │          SessionController (DI-injected)          │  │
│  │                                                   │  │
│  │  • routeIncomingMessage(message)                  │  │
│  │  • prewarmSessions(contactIds)                    │  │
│  │  • sendEndSession(userId, reason)                 │  │
│  │  • sendEndSessionToAllContacts(reason)            │  │
│  │  • handleKeySyncRequest(userId)                   │  │
│  │  • hasActiveSession(userId): Boolean              │  │
│  │  • onEphemeralSubscriptionNeeded: Callback        │  │
│  │  • onE2EDeliveryReceiptDecrypted: Callback        │  │
│  └─────────────────────┬─────────────────────────────┘  │
│                        │                                │
│                        ▼                                │
│  ┌───────────────────────────────────────────────────┐  │
│  │          SessionCoordinator (internal)            │  │
│  │                                                   │  │
│  │  • MessageRouter                                  │  │
│  │  • PublicKeyBundleHandler                         │  │
│  │  • SessionInitializationService                   │  │
│  │  • Tie-break watchdogs                            │  │
│  │  • Responder fallback                             │  │
│  │  • Cooldown management                            │  │
│  └─────────────────────┬─────────────────────────────┘  │
│                        │                                │
│                        ▼                                │
│  ┌───────────────────────────────────────────────────┐  │
│  │          CryptoManager                            │  │
│  │          SessionActionExecutor                    │  │
│  │          MessageStreamManager                     │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

---

## SessionController Interface

```kotlin
@Singleton
class SessionController @Inject constructor(
    private val sessionCoordinator: SessionCoordinator,
    private val cryptoManager: CryptoManager
) {
    // ── Setup (called once during app composition) ───────────

    fun configure(streamManager: MessageStreamManager) {
        sessionCoordinator.configure(streamManager)
    }

    fun setContext(database: RoomDatabase) {
        sessionCoordinator.setDatabase(database)
    }

    // ── Callbacks (set once, do not reassign) ────────────────

    /**
     * Set once during composition. Do not reassign — the setter
     * overwrites the previous value, which would silently break routing.
     */
    var onEphemeralSubscriptionNeeded: ((ServerUserId) -> Unit)? = null
        set(value) {
            field = value
            sessionCoordinator.onEphemeralSubscriptionNeeded = value
        }

    var onE2EDeliveryReceiptDecrypted: ((List<String>) -> Unit)? = null
        set(value) {
            field = value
            sessionCoordinator.onE2EDeliveryReceiptDecrypted = value
        }

    // ── Incoming message routing ─────────────────────────────

    /**
     * Route an incoming message through the session pipeline.
     * Called from the stream layer for every incoming message.
     */
    fun routeIncomingMessage(message: ChatMessage, database: RoomDatabase) {
        sessionCoordinator.routeIncomingMessage(message, database)
    }

    // ── Session lifecycle (user-facing) ──────────────────────

    /**
     * Proactively initialize E2E sessions as INITIATOR.
     * Used when opening a chat, creating a new contact, etc.
     */
    fun prewarmSessions(
        contactIds: List<ServerUserId>,
        skipEndSessionNotification: Boolean = false
    ) {
        sessionCoordinator.prewarmSessions(
            contactIds,
            skipEndSessionNotification
        )
    }

    /**
     * Send END_SESSION to a specific contact and archive local state.
     */
    suspend fun sendEndSession(
        userId: ServerUserId,
        reason: String = "manual_reset"
    ) {
        sessionCoordinator.sendEndSession(userId, reason)
    }

    /**
     * Broadcast END_SESSION to all active sessions (used on logout).
     */
    suspend fun sendEndSessionToAllContacts(reason: String = "logout") {
        sessionCoordinator.sendEndSessionToAllContacts(reason)
    }

    // ── Key sync ─────────────────────────────────────────────

    /**
     * Re-key the sending session when the peer's public keys changed.
     */
    fun handleKeySyncRequest(userId: ServerUserId) {
        sessionCoordinator.handleKeySyncRequest(userId)
    }

    // ── Session state query (for UI gating) ──────────────────

    /**
     * Whether an active E2E session exists for the contact.
     * Do NOT use this to make protocol decisions; it is for UI state only.
     */
    fun hasActiveSession(userId: ServerUserId): Boolean =
        cryptoManager.hasSession(userId)
}
```

---

## What NOT to do (iOS lessons)

### Don't create ad-hoc SessionCoordinator instances

**iOS problem**:
```swift
// AuthViewModel.swift — BAD
await SessionCoordinator().sendEndSessionToAllContacts(reason: "logout")

// UserProfileView.swift — BAD
try? await SessionCoordinator().sendEndSession(to: user.id, reason: "user_requested")
```

**Android fix** — DI-injected `SessionController`:
```kotlin
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val sessionController: SessionController
) : ViewModel() {
    fun logout() {
        viewModelScope.launch {
            sessionController.sendEndSessionToAllContacts("logout")
            // ... rest of logout
        }
    }
}
```

### Don't call CryptoManager.hasSession from Views

**iOS problem**:
```swift
// UserProfileView.swift — BAD
let sessionExists = CryptoManager.shared.hasSession(for: user.id)
```

**Android fix** — expose through ViewModel:
```kotlin
@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val sessionController: SessionController
) : ViewModel() {
    private val _hasSession = MutableStateFlow(false)
    val hasSession: StateFlow<Boolean> = _hasSession

    fun loadUser(userId: ServerUserId) {
        _hasSession.value = sessionController.hasActiveSession(userId)
    }
}
```

### Don't pass sessionCoordinator through DI chain

**iOS problem** (now fixed):
```
ChatsViewModel → ChatView → ChatViewModel → ChatSendCoordinator
   (sessionCoordinator passed through all 4 layers)
```

**Android fix** — inject once at the right level:
```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionController: SessionController,
    // No need to pass sessionCoordinator through layers
) : ViewModel() {
    // Use sessionController directly
}
```

---

## Internal Components (not exposed to UI)

### SessionCoordinator (internal)

Owns all session state:
- `sessionStates: Map<ServerUserId, ContactSessionState>`
- `endSessionSentAt: Map<ServerUserId, Instant>`
- `resendAttemptedAt: Map<ServerUserId, Instant>`
- `tieBreakWatchdogs: Map<ServerUserId, Job>`
- `responderFallbackTasks: Map<ServerUserId, Job>`
- `messageRouter: MessageRouter`
- `publicKeyBundleHandler: PublicKeyBundleHandler`
- `sessionInitService: SessionInitializationService`

### SessionActionExecutor

Executes `CfeAction` results from Rust:
- Stateless actions executed immediately
- State-bound actions (`.messageDecrypted`, `.sessionHealNeeded`, `.sendEndSession`, `.fetchPublicKeyBundle`) are `break`-stubbed — handled by caller

### MessageRouter

Routes incoming messages:
- ACK/dedup via `PersistentACKStore`
- Pending queue for messages before session init
- Delegate callbacks for session events

---

## Concurrency Model

| Component | Threading |
|---|---|
| SessionController | `@MainScope` (ViewModel scope) |
| SessionCoordinator | `Dispatchers.Main` (UI thread) |
| SessionActionExecutor | `Dispatchers.Main` (called from router) |
| MessageRouter | `Dispatchers.Main` (called from stream) |
| CryptoManager | `Mutex` for core access, `Dispatchers.IO` for crypto ops |

**Rule**: All session state mutations on `Dispatchers.Main`. Crypto operations on `Dispatchers.IO` with `Mutex` protection.

