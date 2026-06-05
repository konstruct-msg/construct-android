# Session Initialization — Android

**Источник**: `SessionInitializationService.swift`, `CryptoSessionInitializationService.swift`, `PublicKeyBundleHandler.swift`, `SessionCoordinator.swift` (tie-break, ping, ready)
**Протокол**: X3DH → Double Ratchet → PQXDH (Kyber-768)

---

## Protocol Overview

```
Alice (INITIATOR)                          Bob (RESPONDER)
     │                                          │
     │  1. Fetch Bob's pre-key bundle (gRPC)    │
     │─────────────────────────────────────────>│
     │                                          │
     │  2. X3DH key agreement (Rust)            │
     │  3. Create sending chain                 │
     │                                          │
     │  4. Send msgNum=0 (encrypted ping)       │
     │─────────────────────────────────────────>│
     │                                          │
     │                           5. msgNum=0 received
     │                           6. Fetch Alice's bundle (gRPC)
     │                           7. X3DH + initReceivingSession
     │                           8. Decrypt msgNum=0
     │                           9. PQXDH decapsulation (if Kyber)
     │                                          │
     │  10. Send session_ready (encrypted)      │
     │<─────────────────────────────────────────│
     │                                          │
     │  11. session_ready received              │
     │  12. Both sides ready → send messages    │
     │                                          │
```

---

## Key Bundle Structure

```kotlin
data class PublicKeyBundle(
    val userId: ServerUserId,
    val identityPublic: ByteArray,          // X25519 identity key
    val signedPrekeyPublic: ByteArray,      // X25519 signed prekey
    val signature: ByteArray,               // Ed25519 signature of SPK
    val verifyingKey: ByteArray,            // Ed25519 verifying key
    val suiteId: UInt,                      // 1 = X3DH, 2 = PQXDH
    val oneTimePreKeyPublic: ByteArray?,    // X25519 OTPK (optional)
    val oneTimePreKeyId: UInt?,             // OTPK ID
    val kyberPreKeyPublic: ByteArray?,      // Kyber-768 SPK (suiteId=2)
    val kyberOneTimePreKeyPublic: ByteArray?, // Kyber-768 OTPK (suiteId=2)
    val kyberOneTimePreKeyId: UInt?,        // Kyber OTPK ID
    val spkUploadedAt: ULong,               // Timestamp of SPK upload
    val spkRotationEpoch: UInt,             // SPK rotation counter
    val kyberSpkUploadedAt: ULong,          // Timestamp of Kyber SPK upload
    val kyberSpkRotationEpoch: UInt         // Kyber SPK rotation counter
)
```

---

## INITIATOR Flow

### Step 1: Fetch Pre-Key Bundle

```kotlin
suspend fun fetchPublicKeyWithRetry(
    userId: ServerUserId,
    deviceId: String? = null,
    maxAttempts: Int = 3,
    initialDelay: Duration = 1.seconds
): PublicKeyBundle {
    var lastError: Throwable? = null
    var delay = initialDelay

    for (attempt in 1..maxAttempts) {
        try {
            return keyServiceClient.getPreKeyBundle(userId, deviceId)
        } catch (e: Throwable) {
            lastError = e
            if (attempt < maxAttempts) {
                delay(delay)
                delay *= 2  // exponential backoff
            }
        }
    }
    throw lastError ?: NetworkException("Failed to fetch pre-key bundle")
}
```

### Step 2: Validate Bundle

```kotlin
fun validateBundle(bundle: PublicKeyBundle) {
    // SPK replay protection
    val knownEpoch = keyStore.loadSpkEpoch(bundle.userId)
    if (bundle.spkRotationEpoch < knownEpoch) {
        throw SessionError.StaleSpkBundle(bundle.spkRotationEpoch, knownEpoch)
    }
    keyStore.saveSpkEpoch(bundle.spkRotationEpoch, bundle.userId)

    // PQ epoch validation
    if (bundle.suiteId == 2U && bundle.kyberSpkRotationEpoch == 0U) {
        throw SessionError.KyberEpochRequired
    }
}
```

### Step 3: Initialize Session (Rust)

```kotlin
suspend fun initializeSession(
    userId: ServerUserId,
    bundle: PublicKeyBundle
) = cryptoManager.initializeSession(
    userId = userId,
    recipientBundle = bundle.toBinaryKeyBundle(),
    oneTimePreKeyPublic = bundle.oneTimePreKeyPublic,
    oneTimePreKeyId = bundle.oneTimePreKeyId,
    kyberPreKeyPublic = bundle.kyberPreKeyPublic,
    kyberOneTimePreKeyPublic = bundle.kyberOneTimePreKeyPublic,
    kyberOneTimePreKeyId = bundle.kyberOneTimePreKeyId,
    spkUploadedAt = bundle.spkUploadedAt,
    spkRotationEpoch = bundle.spkRotationEpoch,
    kyberSpkUploadedAt = bundle.kyberSpkUploadedAt,
    kyberSpkRotationEpoch = bundle.kyberSpkRotationEpoch
)
```

### Step 4: Send Session Ping (msgNum=0)

```kotlin
suspend fun sendSessionPing(userId: ServerUserId) {
    val pingContent = "__session_ping_${UUID.randomUUID()}__"
    val pingId = UUID.randomUUID().toString()

    val payload = outboundSessionService.encryptSessionControl(
        plaintext = pingContent,
        messageId = pingId,
        recipientId = userId
    )

    messagingServiceClient.sendMessage(
        messageId = pingId,
        recipientId = userId,
        senderId = currentUserId,
        conversationId = ConversationId.direct(currentUserId, userId),
        encryptedPayload = payload,
        timestamp = currentTimeMillis(),
        contentType = ContentType.SESSION_PING
    )
}
```

---

## RESPONDER Flow

### Step 5-6: Receive msgNum=0, Fetch Bundle

Triggered by `MessageRouter.routeIncomingMessage` when `messageNumber == 0` and no session exists.

```kotlin
// In MessageRouter:
if (!cryptoManager.hasSession(otherUserId)) {
    handleFirstMessage(message, otherUserId)
    return
}

// In SessionController.handleFirstMessage:
pendingQueue.enqueue(message, otherUserId)
fetchPublicKeyBundleAndInit(otherUserId, message)
```

### Step 7: Initialize Receiving Session (Rust)

```kotlin
suspend fun initReceivingSession(
    userId: ServerUserId,
    bundle: PublicKeyBundle,
    firstMessage: ChatMessage
): ByteArray = cryptoManager.initReceivingSession(
    userId = userId,
    recipientBundle = bundle.toBinaryKeyBundle(),
    firstMessage = firstMessage,
    spkUploadedAt = bundle.spkUploadedAt,
    spkRotationEpoch = bundle.spkRotationEpoch,
    kyberSpkUploadedAt = bundle.kyberSpkUploadedAt,
    kyberSpkRotationEpoch = bundle.kyberSpkRotationEpoch
)
```

### Step 8: Decrypt First Message

Rust returns decrypted plaintext from `initReceivingSession`. The message is then saved to the database.

### Step 9: PQXDH Decapsulation

If `firstMessage.kemCiphertext` is non-empty:

```kotlin
if (firstMessage.kemCiphertext.isNotEmpty()) {
    val kyberOtpkId = firstMessage.kyberOtpkId
    if (kyberOtpkId > 0) {
        val otpkSecret = pqcKeyManager.getKyberOtpkSecret(kyberOtpkId)
            ?: throw SessionError.PqOtpkMissing(kyberOtpkId)
        pqcKeyManager.decapsulateAndStrengthen(
            kemCiphertext = firstMessage.kemCiphertext,
            contactId = userId,
            secretKeyOverride = otpkSecret
        )
        pqcKeyManager.deleteKyberOtpk(kyberOtpkId)
    } else {
        pqcKeyManager.decapsulateAndStrengthen(
            kemCiphertext = firstMessage.kemCiphertext,
            contactId = userId
        )
    }
}
```

### Step 10: Send Session Ready

```kotlin
suspend fun sendSessionReady(userId: ServerUserId) {
    val readyContent = "__session_ready_${UUID.randomUUID()}__"
    val readyMessageId = UUID.randomUUID().toString()

    val payload = outboundSessionService.encryptSessionControl(
        plaintext = readyContent,
        messageId = readyMessageId,
        recipientId = userId
    )

    messagingServiceClient.sendMessage(
        messageId = readyMessageId,
        recipientId = userId,
        senderId = currentUserId,
        conversationId = ConversationId.direct(currentUserId, userId),
        encryptedPayload = payload,
        timestamp = currentTimeMillis()
    )
}
```

### Step 11-12: Both Sides Ready

When INITIATOR receives `session_ready`, it:
1. Cancels tie-break watchdog
2. Marks session as active
3. Confirms session in `SessionConfirmationTracker`
4. Drains pending queue (sends queued messages)

---

## Tie-Break (Simultaneous Init)

When both sides try to init at the same time:

```kotlin
val myId = currentUserId
val peerId = otherUserId

if (DeviceIdOrdering.isNaturalInitiator(myId, peerId)) {
    // I WIN → INITIATOR role
    // Send SESSION_RESET_INIT (atomic single message)
    sendSessionResetInit(peerId)
    startTieBreakWatchdog(peerId)
} else {
    // I LOSE → RESPONDER role
    // Wait for peer's SESSION_RESET_INIT or ping
    startResponderFallback(peerId)
}
```

### SESSION_RESET_INIT (atomic)

```kotlin
suspend fun sendSessionResetInit(userId: ServerUserId) {
    val sriContent = "__session_reset_init_${UUID.randomUUID()}__"
    val sriId = UUID.randomUUID().toString()

    val payload = outboundSessionService.encryptSessionControl(
        plaintext = sriContent,
        messageId = sriId,
        recipientId = userId
    )

    messagingServiceClient.sendMessage(
        messageId = sriId,
        recipientId = userId,
        senderId = currentUserId,
        conversationId = ConversationId.direct(currentUserId, userId),
        encryptedPayload = payload,
        timestamp = currentTimeMillis(),
        contentType = ContentType.SESSION_RESET_INIT  // 24
    )
}
```

### Tie-Break Watchdog (30s timeout)

If INITIATOR doesn't receive confirmation within 30 seconds:
1. Re-prewarm session
2. Re-send SESSION_RESET_INIT

### Responder Fallback (60s timeout)

If RESPONDER doesn't receive init from peer within 60 seconds:
1. Take INITIATOR role instead
2. Start proactive session init

---

## Session Confirmation

```kotlin
class SessionConfirmationTracker {
    private val pendingSessions = mutableSetOf<ServerUserId>()

    fun markPending(userId: ServerUserId) {
        pendingSessions.add(userId)
    }

    fun markConfirmed(userId: ServerUserId) {
        pendingSessions.remove(userId)
    }

    fun isPending(userId: ServerUserId): Boolean {
        return pendingSessions.contains(userId)
    }
}
```

**Purpose**: While session is pending confirmation, outgoing messages are buffered (not sent) to prevent ratchet desync.

---

## Stale SPK Handling

```kotlin
suspend fun initializeSessionProactively(
    userId: ServerUserId,
    onSuccess: () -> Unit,
    onFailure: (Throwable) -> Unit
) {
    val staleSPKMaxRetries = 2
    val staleSPKRetryDelay = 60.seconds
    val staleSPKFastFailDays = 10.25

    for (attempt in 0..staleSPKMaxRetries) {
        if (attempt > 0) {
            delay(staleSPKRetryDelay)
        }

        try {
            val bundle = fetchPublicKeyWithRetry(userId)
            initializeSession(userId, bundle, deleteExisting = true)
            onSuccess()
            return
        } catch (e: SessionError.PeerSpkStale) {
            if (attempt < staleSPKMaxRetries && e.ageDays < staleSPKFastFailDays) {
                continue  // retry after delay
            }
            break  // fast fail
        } catch (e: Throwable) {
            break
        }
    }
    onFailure(lastError ?: NetworkException("Connection failed"))
}
```

---

## Error Types

```kotlin
sealed class SessionError : Exception() {
    data class StaleSpkBundle(
        val receivedEpoch: UInt,
        val knownEpoch: UInt
    ) : SessionError()

    data class PeerSpkStale(
        val ageDays: Double
    ) : SessionError()

    object KyberEpochRequired : SessionError()

    data class PqOtpkMissing(
        val keyId: UInt
    ) : SessionError()

    object CoreNotInitialized : SessionError()
    object SessionNotFound : SessionError()
}
```

---

## iOS Anti-patterns Fixed

| iOS Problem | Android Fix |
|---|---|
| `initializeSessionProactively` uses callbacks | `suspend fun` with proper error propagation |
| SPK stale retry logic mixed with network retry | Separate `SessionError.PeerSpkStale` handling |
| Tie-break watchdog uses `Task` without proper cancellation | `CoroutineScope` with `Job` cancellation |
| Session ping/ready sent as raw strings | Structured `ContentType` enum |
| `SessionConfirmationTracker` is singleton | DI-injected, scoped to SessionController |

---

## Key Differences from iOS

| Aspect | iOS | Android |
|---|---|---|
| Async model | `async/await` + `Task` | Coroutines (`suspend fun`, `viewModelScope`) |
| Callbacks | `onSuccess: @escaping () -> Void` | `suspend fun` returns result directly |
| Error handling | `throw` + `try?` swallowing | Sealed class errors, no swallowing |
| Timer/timeout | `Task.sleep` + manual cancel | `withTimeoutOrNull` |
| State management | `@MainActor` dictionaries | `Mutex` + `StateFlow` |
| DI | Singletons everywhere | Hilt injection |

