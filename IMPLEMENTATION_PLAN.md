# Konstrukt Messenger Android — Implementation Plan

> **Last actualized:** 2026-06-02. Brought into sync with current iOS
> architecture (`construct-veil` happy-eyeballs, VEIL rename, CFE binary
> session persistence, OTPK threshold = 20). The `veil-front` obfuscation
> protocol (see `construct-docs/raw/02_Core_Crypto/protocols/OBFUSCATION_IMPLEMENTATION_PLAN_veil-front.md`)
> lands on Android automatically via construct-core rebuild + flag flip
> once it's ready upstream — no Android-specific work needed beyond M7
> of that plan.

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
    // exportSessionBytes(contactId): ByteArray   // CFE binary — never JSON
    // importSessionBytes(contactId, bytes)       // CFE binary — never JSON
    // generateMnemonic(wordCount)
    // deriveRecoveryKeypair(mnemonic)
    // solvePoW(challenge, difficulty)
}
```

> **Binary pipeline (mandatory):** session persistence uses **CFE** (16-byte
> header + MessagePack payload via `rmp_serde`), not JSON. The Rust FFI is
> `export_session_bytes_for` / `import_session_bytes_for`. No
> `base64EncodedString`-style stringification in application code; bytes cross
> the UniFFI boundary as `ByteArray`. Same rule iOS follows — see
> `construct-messenger/AGENTS.md` §"Binary Data Pipeline".

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
4. Upload initial OTPK batch -> RPC: UploadOneTimePrekeys (min 20, matches iOS)
5. Store auth_token in Keystore
6. Optionally: setup recovery phrase -> SetRecoveryKey
```

> OTPK threshold was bumped from 10 → 20 to match iOS production setting.
> Below 20 the server flags the device for replenishment.

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

### 5.1 VEIL Transport
**Status:** Pending
**Priority:** MEDIUM
**Depends on:** 2.3

> **Renamed 2026-05-29:** what used to be called "ICE" in earlier drafts is
> now **VEIL** (`construct-veil`) — the obfuscation proxy layer (obfs4,
> WebTunnel, future veil-front). The WebRTC industry term "ICE" is a
> different concept and is kept only for the WebRTC NAT-traversal flow in
> §6. Do NOT mix the two — see `construct-messenger/AGENTS.md` §"VEIL vs
> WebRTC ICE".

**Primary gRPC backend:** `ams.konstruct.cc:443` (direct TLS, used when the
network is uncensored).

**VEIL relay bridges:** addresses are not hard-coded. They come from a
signed `.well-known/veil-bridges` manifest fetched at app start with a
hardcoded SPKI pin as last-resort fallback. The current obfs4/WebTunnel
endpoints (e.g. `api.divany-kresla.uk:443`) are deliberately rotated and
must not be baked into client code.

**Connection strategy — do NOT implement a Kotlin-side fallback loop.**

iOS routes everything through `construct-veil`'s **happy-eyeballs
coordinator** (Rust side, behind `veil_start` FFI). Android does the same:

```kotlin
// VeilProxy.kt — thin Kotlin wrapper around the Rust FFI
class VeilProxy(private val core: ClassicCryptoCore) {
    // 1. fetchManifest() -> bridge descriptors + ticket bundles
    // 2. core.veilStart(bundles)  ← Rust races methods in parallel
    //                                (obfs4 / WebTunnel / future veil-front)
    // 3. Returns a local TCP port; gRPC client connects to it as h2c
}
```

Everything below is upstream of the Kotlin surface and lives in
`construct-veil`:
- Parallel racing across MethodIds (`obfs4`, `WebTunnel`, future `VeilFront`)
- Per-network `PersistentScores` for method preference
- Silent fallback on failure
- Backoff and latency-aware switching

**Don't reinvent this in Kotlin** — the iOS direct-fallback pattern was
deleted (`[[project-construct-veil-ios-adoption]]`) for exactly that reason:
two parallel routing implementations diverged. Android consumes the same
Rust coordinator via UniFFI.

**Files to create:**
- `data/api/VeilProxy.kt` (thin Kotlin wrapper, not a routing implementation)
- `data/api/TransportRouter.kt` (FSM mirror of Rust router state for UI/observability)

**Future addition — `veil-front`.** A new MethodId (honest HTTPS front +
session-bound auth) is being implemented in `construct-veil` per
`OBFUSCATION_IMPLEMENTATION_PLAN_veil-front.md`. When it lands upstream,
Android picks it up automatically: rebuild `libconstruct_core.so`,
regenerate Kotlin UniFFI bindings, flip a manifest flag. No new Kotlin
routing code.

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

> **Note on terminology:** this section uses **WebRTC ICE** (Interactive
> Connectivity Establishment — the industry-standard P2P NAT-traversal
> mechanism). It is **unrelated** to VEIL (§5.1) despite the name overlap
> in some legacy docs. TURN servers below are obtained via Construct's
> Signaling service `getTurnCredentials` RPC, not hard-coded.

**TURN servers (current production):**
```
turn:turn.ams.konstruct.cc:3478?transport=udp
turn:turn.ams.konstruct.cc:3478?transport=tcp
turns:turn.ams.konstruct.cc:5349?transport=tcp
turn:turn.msk.konstruct.cc:3478?transport=udp     (currently blocked by RKN, kept for failover)
turn:turn.msk.konstruct.cc:3478?transport=tcp
turns:turn.msk.konstruct.cc:5349?transport=tcp
```

Credentials are short-lived (HMAC-SHA1 over a username derived from
`<timestamp>:<userId>`, secret shared with coturn via TURN-REST). The
server returns them via `SignalingService.GetTurnCredentials(callId)`;
clients do not store the static secret.

**Signaling flow:**
```
1. Send CALL_OFFER via MessageStream (encrypted via construct-core E2EE)
2. Start PeerConnection with WebRTC ICE candidates (host / srflx / relay)
3. Remote sends CALL_ANSWER
4. WebRTC ICE exchange -> P2P or TURN-relayed
5. MediaChannel open (Opus audio; video planned, see CallsFeature.isVideoEnabled on iOS)
```

**System-call UI: `ConnectionService` + `TelecomManager`** — the Android
equivalent of CallKit. Use it for:
- Lock-screen incoming call UI (system-owned)
- "Recent calls" entry in the system Phone app
- Audio focus + AVAudioSession-equivalent route management
- Bluetooth headset / car audio handover

**iOS lessons that apply here directly:**
- Set up the audio session **before** the system activates it (on iOS we
  put `useManualAudio = true` at app launch — Android has analogous
  ordering via `ConnectionService.onShowIncomingCallUi` /
  `onCreateIncomingConnection`).
- Stop any local ringback tone the moment `peerConnectionState ==
  .connected` fires — otherwise it shares the audio output with WebRTC
  and produces silence. See `[[project-calls-audio-fixed]]` for the iOS
  postmortem.
- Use the system route picker (Android `MediaRouter` /
  `AudioDeviceCallback`), not a binary speaker toggle.

**Files to create:**
- `domain/usecase/CallUseCase.kt`
- `data/api/WebRtcManager.kt`
- `data/api/ConstructConnectionService.kt` (extends `android.telecom.ConnectionService`)
- `data/api/AudioRoutePicker.kt` (Android equivalent of iOS `AudioRoutePickerButton`)

---

## Phase 7: Push Notifications

### 7.1 FCM Integration
**Status:** Pending
**Priority:** MEDIUM
**Depends on:** 2.3

```
1. Get FCM token (must be a high-priority data message — NOT a notification message,
   notification messages don't wake the app reliably)
2. RPC: RegisterPushToken(token, platform=ANDROID)
3. On new message: FCM data message -> wake up -> stream -> decrypt
4. Use WorkManager for reliability
5. For incoming calls: trigger ConnectionService.onShowIncomingCallUi
   (NOT a notification — the system call UI is owned by Telecom framework, §6.1)
```

> **Cross-platform note:** Android does not have a true VoIP-push primitive
> equivalent to iOS PushKit + CallKit. The closest is FCM high-priority data
> message + `ConnectionService.onShowIncomingCallUi`. Battery / Doze
> constraints make this less reliable than iOS PushKit; expect to add
> WorkManager-driven catch-up paths and `setForegroundService` during active
> calls. Treat this as a known platform parity gap, not a regression.

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