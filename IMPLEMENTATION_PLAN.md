# Konstrukt Messenger Android — Implementation Plan

## Phase 0: Project Setup (DONE)
- ✅ Gradle 9.3.1 + Kotlin 2.0
- ✅ Compose with Kotlin Compiler plugin
- ✅ Hilt for DI
- ✅ Directory structure

## Phase 1: Crypto Core Integration

### 1.1 Rust Core Build & Integration
**Status:** Pending
**Priority:** HIGH
**Depends on:** construct-core repo

```
Steps:
1. Build Rust library for Android targets
   cargo build --release --target aarch64-linux-android
   cargo build --release --target armv7-linux-androideabi
   cargo build --release --target x86_64-linux-android

2. Generate UniFFI bindings
   uniffi-bindgen generate \
     --library target/aarch64-linux-android/release/libconstruct_core.so \
     --language kotlin \
     --out-dir bindings/kotlin

3. Copy .so files to app/src/main/jniLibs/
   arm64-v8a/, armeabi-v7a/, x86_64/

4. Add to build.gradle:
   sourceSets {
       main { jniLibs.srcDirs = ['src/main/jniLibs'] }
   }
```

**Files to create:**
- `app/src/main/jniLibs/` (native libs)
- `app/src/main/java/.../crypto/ClassicCryptoCore.kt` (UniFFI bindings)

### 1.2 Crypto API Wrapper
**Status:** Pending
**Priority:** HIGH
**Depends on:** 1.1

```kotlin
// CryptoManager.kt - singleton wrapper
class CryptoManager(private val core: ClassicCryptoCore) {
    // generateKeyBundle() -> PublicKeyBundle
    // initSession(contactBundle)
    // encryptMessage(contactId, plaintext)
    // decryptMessage(contactId, ciphertext)
    // exportSessionJson(contactId)
    // importSessionJson(contactId, json)
    // generateMnemonic(wordCount)
    // deriveRecoveryKeypair(mnemonic)
    // solvePoW(challenge, difficulty)
}
```

---

## Phase 2: Core Infrastructure

### 2.1 Hilt App Module
**Status:** Pending
**Priority:** HIGH

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideCryptoManager(...): CryptoManager

    @Provides
    @Singleton
    fun provideGrpcClient(...): GrpcClient

    @Provides
    @Singleton
    fun provideSessionManager(...): SessionManager

    @Provides
    @Singleton
    fun provideKeystoreManager(...): KeystoreManager
}
```

### 2.2 Android Keystore
**Status:** Pending
**Priority:** HIGH

| Key | Storage | Accessibility |
|-----|---------|---------------|
| Auth token | EncryptedSharedPrefs | AFTER_FIRST_UNLOCK |
| Device identity key | Android Keystore | USER_AUTHENTICATED |
| Session JSON (per contact) | EncryptedSharedPrefs | AFTER_FIRST_UNLOCK |
| Recovery public key | EncryptedSharedPrefs | AFTER_FIRST_UNLOCK |

```kotlin
// KeystoreManager.kt
class KeystoreManager(
    private val keyStore: KeyStore,
    private val encryptedPrefs: DataStore<Preferences>
) {
    // saveAuthToken(token)
    // getAuthToken(): String?
    // saveSessionJson(contactId, json)
    // getSessionJson(contactId): String?
    // generateIdentityKey(): KeyPair  // hardware-backed
    // hasIdentityKey(): Boolean
}
```

### 2.3 gRPC Client
**Status:** Pending
**Priority:** HIGH
**Depends on:** protobuf definitions

```
Services to implement:
- AuthService: RegisterDevice, Login, RefreshToken, Logout
- DeviceService: GetDevices, RevokeDevice
- KeyService: UploadKeyBundle, FetchKeyBundle, UploadOneTimePrekeys
- MessagingService: SendMessage, MessageStream, GetPendingMessages
- UserService: GetProfile, UpdateProfile, SearchUsers
- NotificationService: RegisterPushToken, UnregisterPushToken
- SentinelService: GetCallCredentials
```

**Files to create:**
- `data/api/proto/` (generated protobuf)
- `data/api/GrpcClient.kt`
- `data/api/AuthService.kt`
- `data/api/KeyService.kt`
- `data/api/MessagingService.kt`

---

## Phase 3: Authentication & Session

### 3.1 Registration Flow
**Status:** Pending
**Priority:** HIGH
**Depends on:** 1.2, 2.3

```
1. core.generateKeyBundle() -> PublicKeyBundle
2. Solve PoW challenge -> RPC: GetPoWChallenge -> solve -> RegisterDevice
3. RegisterDevice(bundle, pow_solution) -> device_id, auth_token
4. Upload initial OTPK batch -> RPC: UploadOneTimePrekeys (min 10)
5. Store auth_token in Keystore
6. Optionally: setup recovery phrase -> SetRecoveryKey
```

**Files to create:**
- `domain/usecase/RegisterUseCase.kt`
- `domain/usecase/LoginUseCase.kt`

### 3.2 Session Lifecycle
**Status:** Pending
**Priority:** HIGH

**States:** NONE -> INITIALIZING -> ACTIVE -> HEALING -> NONE

```kotlin
// SessionManager.kt
class SessionManager(
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val keystoreManager: KeystoreManager
) {
    // initSession(contactId) - INITIATOR path
    // initReceivingSession(senderBundle, firstMessage) - RESPONDER
    // encryptMessage(contactId, plaintext)
    // decryptMessage(contactId, ciphertext)
    // exportSessions(): Map<String, String>
    // importSessions(sessions)
}
```

### 3.3 Session Healing
**Status:** Pending
**Priority:** MEDIUM
**Depends on:** 3.2

| Condition | Action |
|-----------|--------|
| messageNumber == 0, AEAD fails | HEAL - re-init silently |
| messageNumber > 0, AEAD fails | END_SESSION - full re-init |

```
1. Receive msgNum=0 -> decrypt fails
2. Check heal attempt count (< 3, 24h TTL)
3. If attempts remaining: delete session -> re-fetch bundle -> initReceivingSession
4. If max attempts: send END_SESSION
```

**Files to create:**
- `domain/usecase/HealSessionUseCase.kt`

---

## Phase 4: Key Management & Recovery

### 4.1 Account Recovery (BIP39)
**Status:** Pending
**Priority:** MEDIUM
**Depends on:** 1.2

```
Setup (first time):
1. mnemonic = core.generateMnemonic(12)
2. keypair = core.deriveRecoveryKeypair(mnemonic)
3. RPC: SetRecoveryKey(keypair.publicKey)
4. Show mnemonic to user ONCE

Recovery (new device):
1. keypair = core.deriveRecoveryKeypair(mnemonic)
2. RPC: InitiateRecovery() -> challenge
3. signature = core.signRecoveryChallenge(keypair.privateKey, challenge)
4. RPC: CompleteRecovery(signature) -> new auth_token
5. Re-register keys, upload OTPKs
```

**Files to create:**
- `domain/usecase/SetupRecoveryUseCase.kt`
- `domain/usecase/RecoverAccountUseCase.kt`

---

## Phase 5: Networking

### 5.1 ICE Relay
**Status:** Pending
**Priority:** MEDIUM
**Depends on:** 2.3

**Endpoints:**
```
Primary: ice.ams.konstruct.cc:443 (TLS)
Moscow: ice.msk.konstruct.cc:9443 (no TLS)
```

```kotlin
object ICEEndpoints {
    val primary = ICEEndpoint(host = "ice.ams.konstruct.cc", port = 443, tls = true)
    val mskRelay = ICEEndpoint(host = "ice.msk.konstruct.cc", port = 9443, tls = false)
}
```

**Connection strategy:**
```
1. Try primary (timeout: 5s)
2. If fail -> try mskRelay (timeout: 5s)
3. If both fail -> exponential backoff (2s, 4s, 8s, max 60s)
4. On reconnect: prefer last successful
5. Probe latency every 5 min, switch if diff > 100ms
```

**Files to create:**
- `data/api/ICEConnectionManager.kt`

### 5.2 Message Stream
**Status:** Pending
**Priority:** HIGH
**Depends on:** 3.2

```
Client -> Server: Subscribe(user_id)
Server -> Client: MessageEnvelope (stream)
Client -> Server: Ack(message_id)

KeepAlive: every 30s
On disconnect: reconnect -> drain pending
```

**Files to create:**
- `data/api/MessageStreamService.kt`

---

## Phase 6: Calls (WebRTC)

### 6.1 WebRTC Integration
**Status:** Pending
**Priority:** MEDIUM
**Depends on:** 5.1

**TURN servers:**
```
turns:ice.ams.konstruct.cc:5349
turns:ice.msk.konstruct.cc:5349
```

**Signaling flow:**
```
1. Send CALL_OFFER via MessageStream (encrypted)
2. Start PeerConnection with ICE candidates
3. Remote sends CALL_ANSWER
4. ICE exchange -> P2P/relay
5. MediaChannel open
```

**Files to create:**
- `domain/usecase/CallUseCase.kt`
- `data/api/WebRtcManager.kt`

---

## Phase 7: Push Notifications

### 7.1 FCM Integration
**Status:** Pending
**Priority:** MEDIUM
**Depends on:** 2.3

```
1. Get FCM token
2. RPC: RegisterPushToken(token, platform=ANDROID)
3. On new message: FCM data message -> wake up -> stream -> decrypt
4. Use WorkManager for reliability
```

**Files to create:**
- `data/local/FcmService.kt`
- `data/worker/MessageSyncWorker.kt`

---

## Phase 8: UI Components

### 8.1 Navigation & Screens
**Status:** Pending
**Priority:** HIGH
**Depends on:** Phase 1-7

```kotlin
// Navigation
Splash -> Onboarding -> Main (conversation list) -> Chat -> Settings
                    -> Recovery (if recovering)

// Screens to implement:
- SplashScreen (check keystore)
- OnboardingScreen (registration)
- MainScreen (conversation list)
- ChatScreen (messages + input)
- SettingsScreen (profile, devices, recovery)
- SearchScreen (find users)
- CallScreen (WebRTC)
```

### 8.2 Compose Components
**Status:** Completed (basic)
**Priority:** HIGH

Components to enhance:
- CTTextField
- CTButton
- CTListItem
- CTConversationRow
- CTMessageBubble
- CTNavBar
- CTLoader

---

## Phase 9: Polish

### 9.1 Localization
**Status:** Pending
**Priority:** LOW

```xml
res/values/strings.xml (English)
res/values-ru/strings.xml (Russian)
```

### 9.2 Final Build
**Status:** Pending
**Priority:** HIGH

```
- Lint checks
- ProGuard (release)
- Bundle for Play Store
```

---

## File Structure Summary

```
app/src/main/java/com/maxeliseyev/konstructmessenger/
├── MainActivity.kt
├── KonstructApp.kt (Application class)
├── crypto/
│   ├── CryptoManager.kt
│   └── ClassicCryptoCore.kt (UniFFI, DO NOT EDIT)
├── data/
│   ├── api/
│   │   ├── GrpcClient.kt
│   │   ├── AuthService.kt
│   │   ├── KeyService.kt
│   │   ├── MessagingService.kt
│   │   ├── MessageStreamService.kt
│   │   ├── ICEConnectionManager.kt
│   │   └── proto/ (generated)
│   ├── local/
│   │   ├── KeystoreManager.kt
│   │   └── FcmService.kt
│   └── repository/
│       ├── AuthRepository.kt
│       ├── SessionRepository.kt
│       └── UserRepository.kt
├── di/
│   └── AppModule.kt
├── domain/
│   ├── model/
│   │   ├── User.kt
│   │   ├── Conversation.kt
│   │   ├── Message.kt
│   │   └── Keys.kt
│   └── usecase/
│       ├── RegisterUseCase.kt
│       ├── LoginUseCase.kt
│       ├── SendMessageUseCase.kt
│       ├── HealSessionUseCase.kt
│       ├── SetupRecoveryUseCase.kt
│       └── CallUseCase.kt
├── ui/
│   ├── navigation/
│   │   ├── Screen.kt
│   │   └── NavHost.kt
│   ├── screens/
│   │   ├── splash/
│   │   ├── onboarding/
│   │   ├── main/
│   │   ├── chat/
│   │   ├── settings/
│   │   └── call/
│   ├── components/
│   │   ├── CTTextField.kt
│   │   ├── CTButton.kt
│   │   ├── CTListItem.kt
│   │   └── ...
│   └── theme/
│       ├── Color.kt
│       ├── Type.kt
│       ├── Theme.kt
│       └── Symbol.kt
└── workers/
    └── MessageSyncWorker.kt
```

---

## Implementation Order

1. **Phase 1:** Crypto Core integration (UniFFI wrapper)
2. **Phase 2:** DI, Keystore, gRPC base
3. **Phase 3:** Registration, Login, Session management
4. **Phase 4:** Recovery
5. **Phase 5:** Message stream, ICE relay
6. **Phase 6:** WebRTC calls
7. **Phase 7:** FCM push
8. **Phase 8:** UI screens
9. **Phase 9:** Localization, final polish