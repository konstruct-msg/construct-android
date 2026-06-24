# Konstrukt Messenger Android — Implementation Plan

> **Last actualized:** 2026-06-24. Brought into sync with current iOS
> architecture (`construct-veil` happy-eyeballs, VEIL rename, CFE binary
> session persistence, OTPK threshold = 20). The `veil-front` obfuscation
> protocol (see `construct-docs/raw/02_Core_Crypto/protocols/OBFUSCATION_IMPLEMENTATION_PLAN_veil-front.md`)
> lands on Android automatically via construct-core rebuild + flag flip
> once it's ready upstream — no Android-specific work needed beyond M7
> of that plan.

> ## ⚠️ Current reality vs this plan
> This document describes the **target** architecture. Phases 1.1–3.2 and
> 2.2/2.3 now have a working skeleton (native libs, UniFFI bindings, gRPC
> stubs generated at build time, `CryptoManager`, `GrpcClient`,
> `SessionManager`, `KeystoreManager`, `RegisterUseCase`/`LoginUseCase`) —
> see the per-phase status below. Session healing (3.3), recovery (4), VEIL
> transport (5.1), message stream (5.2), calls (6), push (7), and most UI
> screens (8) are **not started**. App package is **`com.construct.messenger`**
> (namespace + applicationId), matching the paths in
> `construct-docs/raw/ANDROID_ONBOARDING.md`.
> New devs: start from `GOOD_FIRST_ISSUES.md`.

## Phase 0: Project Setup (DONE)
- ✅ Gradle 9.3.1 + Kotlin 2.0
- ✅ Compose with Kotlin Compiler plugin
- ✅ Hilt for DI (`KonstructApp` `@HiltAndroidApp` + `MainActivity` `@AndroidEntryPoint`)
- ✅ Directory structure (skeleton packages with README stubs)
- ✅ Design tokens aligned to iOS canon (Color, Dimens/CTLayout, Shadows)
- ✅ i18n scaffold (`values/`, `values-ru/`); UI uses `stringResource`

## Phase 1: Crypto Core Integration

### 1.1 Rust Core Build & Integration
**Status:** ✅ Done
**Priority:** HIGH
**Depends on:** construct-core repo

Built via `build_crypto_lib.sh --all` (cargo + NDK cross-compile for
`aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`,
`--features android,post-quantum`), then `uniffi-bindgen generate --language
kotlin`. Re-run the script and regenerate bindings whenever `construct-core`
changes (e.g. the ML-KEM/ML-DSA-65 switch picked up 2026-06-24).

**Files:**
- `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libconstruct_core.so`
- `app/src/main/java/.../crypto/uniffi/construct_core/construct_core.kt`
  (UniFFI bindings, package `uniffi.construct_core` — DO NOT EDIT, regenerate)

### 1.2 Crypto API Wrapper
**Status:** ✅ Done (core wiring; PQ contribution mixing not yet exposed)
**Priority:** HIGH
**Depends on:** 1.1

`CryptoManager.kt` wraps `uniffi.construct_core.ClassicCryptoCore`:
`loadOrCreate()`, `setLocalUserId`, `exportPrivateKeys`, `generateOneTimePrekeys`,
`initSession`/`initReceivingSession`, `encryptMessage`/`decryptMessage`,
`exportSessionBytes`/`importSessionBytes` (CFE binary), `removeSession`,
`getAllSessionContactIds`, `generateMnemonic`, `deriveRecoveryKeypair`,
`computePow`, `signWithDeviceKey` (Ed25519 sign, repurposes the
`signRecoveryChallenge` FFI export — there is no dedicated bare-sign function
yet; swap this if/when `construct-core` adds one).

> **Binary pipeline (mandatory):** session persistence uses **CFE** (16-byte
> header + MessagePack payload via `rmp_serde`), not JSON. The Rust FFI is
> `export_session` / `import_session` (`ClassicCryptoCoreInterface`). No
> `base64EncodedString`-style stringification in application code; bytes cross
> the UniFFI boundary as `ByteArray`/`List<UByte>`. Same rule iOS follows —
> see `construct-messenger/AGENTS.md` §"Binary Data Pipeline". `SessionManager`
> follows this too: `exportSessions()`/`importSessions()` are
> `Map<String, ByteArray>`, not `Map<String, String>` as an earlier draft of
> this doc showed.

---

## Phase 2: Core Infrastructure

### 2.1 Hilt App Module
**Status:** Not needed as drafted — superseded
**Priority:** HIGH

No `AppModule.kt` with `@Provides` factories was needed: `CryptoManager`,
`GrpcClient`, `SessionManager`, and `KeystoreManager` all use
`@Singleton @Inject constructor()` directly, which Hilt resolves into
`SingletonComponent` without an explicit module. An `AppModule` would only
become necessary for binding an interface to an implementation (`@Binds`) or
providing a type Hilt can't construct itself (e.g. a `KeyStore` instance) —
revisit if that need shows up.

### 2.2 Android Keystore
**Status:** ✅ Done (tokens only — see deviations)
**Priority:** HIGH

`KeystoreManager.kt` persists auth tokens in Android Keystore-backed
`EncryptedSharedPreferences` (`androidx.security:security-crypto`,
`AES256_SIV` key / `AES256_GCM` value scheme):
`saveTokens(AuthTokensResponse, deviceId)`, `getAccessToken()`,
`getRefreshToken()`, `getUserId()`, `getDeviceId()`, `clearTokens()`. Wired
into `RegisterUseCase`/`LoginUseCase`, which call `saveTokens()` right after
a successful gRPC response.

**Deviations from the original table below** (kept for history):

| Key | Original plan | Actual |
|-----|---------|---------------|
| Auth token | EncryptedSharedPrefs | ✅ as planned |
| Device identity key | Android Keystore-generated | ❌ not applicable — identity/signing keys live inside `construct-core` (Rust), persisted via `CryptoManager.exportPrivateKeys()`/CFE bytes, not Android-Keystore-generated keypairs |
| Session JSON (per contact) | EncryptedSharedPrefs | ❌ not here — session bytes are CFE binary via `CryptoManager`/`SessionManager`, never JSON, and not yet wired to persistent storage (in-memory only today) |
| Recovery public key | EncryptedSharedPrefs | ❌ not implemented yet (Phase 4) |

`expiresAt` is intentionally not persisted, matching iOS `KeychainManager` —
token expiry is runtime session state, not Keystore data.

### 2.3 gRPC Client
**Status:** ✅ Done (codegen + client; not every RPC has a Kotlin caller yet)
**Priority:** HIGH
**Depends on:** protobuf definitions

Stubs are generated at **build time** by the `com.google.protobuf` Gradle
plugin (not a checked-in `data/api/proto/` dir, and not the
`generate_grpc_kotlin.sh` script from an earlier draft — that script was
deleted, it never actually worked end-to-end). See
`data/api/README.md` for the full pipeline and a documented gotcha
(grpc-kotlin nests `XxxCoroutineStub` inside `object XxxGrpcKt`, not
top-level — easy to get an "Unresolved reference").

`GrpcClient.kt` owns one `ManagedChannel` (OkHttp, direct TLS to
`ams.konstruct.cc:443` — the production gRPC backend, see §5.1; hardcoded
constant for now, not build-config/DataStore-driven, and there is no
VEIL-routed fallback for censored networks yet) and exposes lazy coroutine
stubs: `auth`, `key`, `messaging`, `user`, `notification`, `sentinel`.
`DeviceService`, `ChannelService`, `MLSService`, `VeilService`,
`SignalingService`, etc. are generated too but have no `GrpcClient` accessor
yet — add one when a use case needs it.

Calls actually wired up so far: `AuthService.GetPowChallenge`,
`AuthService.RegisterDevice`, `AuthService.AuthenticateDevice` (3.1),
`KeyService.GetPreKeyBundle` (3.2, via `SessionManager`).

**Files:**
- `app/src/main/proto/` (vendored `.proto` sources from `construct-protos`)
- `data/api/GrpcClient.kt`
- ~~`data/api/AuthService.kt`, `KeyService.kt`, `MessagingService.kt`~~ — not
  created as separate files; callers use `grpcClient.auth`/`.key`/etc.
  directly today. Revisit if a service grows enough RPCs to warrant its own
  wrapper.

---

## Phase 3: Authentication & Session

### 3.1 Registration Flow
**Status:** ✅ Done (steps 1–3; OTPK upload and recovery setup are separate, still pending)
**Priority:** HIGH
**Depends on:** 1.2, 2.3

`RegisterUseCase`:
1. `CryptoManager.loadOrCreate()` -> fresh identity/SPK bundle
   (`RegistrationBundleFields`).
2. `grpcClient.auth.getPowChallenge` -> `CryptoManager.computePow`.
3. Build `DevicePublicKeys` + `PowSolution` from the bundle, call
   `registerDevice`, then `setLocalUserId` + `KeystoreManager.saveTokens`.

`LoginUseCase` (device re-auth, no registration): signs
`"{deviceId}{timestamp}"` with `CryptoManager.signWithDeviceKey`, calls
`authenticateDevice`, persists tokens the same way.

`DevicePublicKeys.crypto_suite` is the literal string `"Curve25519+Ed25519"`
— a free-form display string independent of the `CryptoSuite` proto enum
used elsewhere, matching iOS `AuthServiceClient.registerDevice` exactly.

**Not yet done:**
- Step 4, upload initial OTPK batch (`KeyService.UploadPreKeys`, min 20 to
  match iOS) — needs `CryptoManager.generateOneTimePrekeys()` wired to a new
  use case.
- Step 6, recovery phrase setup (`SetRecoveryKey`) — Phase 4.

**Files:**
- `domain/usecase/RegisterUseCase.kt`
- `domain/usecase/LoginUseCase.kt`

### 3.2 Session Lifecycle
**Status:** ✅ Done (INITIATOR path + crypto delegation; RESPONDER path is a thin pass-through, not exercised by a real inbound-message flow yet since 5.2 isn't built)
**Priority:** HIGH

**States:** NONE -> INITIALIZING -> ACTIVE -> HEALING -> NONE

`SessionManager.kt`:
```kotlin
class SessionManager @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
) {
    suspend fun initSession(contactId: String): String          // INITIATOR — fetches PreKeyBundle via KeyService, maps to BinaryKeyBundle
    fun initReceivingSession(contactId, senderBundle, firstMessage)  // RESPONDER
    fun encryptMessage(contactId, plaintext)
    fun decryptMessage(sessionId, ephemeralPublicKey, messageNumber, content)
    fun exportSessions(): Map<String, ByteArray>                 // CFE binary, not String/JSON
    fun importSessions(sessions: Map<String, ByteArray>)
}
```

Deviates from the original sketch in two ways: no `KeystoreManager`
dependency (session bytes aren't persisted yet — `exportSessions()` exists
but nothing calls it on a lifecycle event), and `exportSessions()`/
`importSessions()` use `ByteArray`, not `String`, per the CFE-binary rule
(§1.2).

**Files:**
- `service/SessionManager.kt`

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

### 3.4 Session-Control Message Format
**Status:** Pending
**Priority:** HIGH
**Depends on:** 3.2, 3.3, 5.2 (message stream)

> Full spec: `ANDROID_ONBOARDING.md` §"Session-Control Message Format (typed
> binary — DO THIS, not magic strings)". Summarized here because every
> handler that touches incoming/outgoing messages (3.3 healing, 5.2 stream,
> chat UI) must dispatch on this **before** the chunk reassembler / text
> pipeline — getting it wrong means handshake noise renders as chat bubbles.

The session handshake signals (`PING`, `READY`, `RESET_INIT`) are protocol
control, not chat content. iOS historically encoded them as plaintext magic
strings (`"__session_ready_<UUID>__"`) baked into the message body, which
leaked into the transcript and broke on format skew (see
`decisions/binary-control-message-format.md`). The fix moves the
discriminator into the Envelope **`content_type`** field — outside the
renderable text pipeline, so it can never become a chat bubble — and is not
part of the Double-Ratchet AEAD associated data, so setting it never affects
decryption.

| Signal | `content_type` | Direction | Payload |
|--------|---------------:|-----------|---------|
| Session ping | `25` `CONTENT_TYPE_SESSION_PING` | INITIATOR → peer (tie-break nudge) | `SessionControl{op=PING}` |
| Session ready | `26` `CONTENT_TYPE_SESSION_READY` | RESPONDER → INITIATOR (phase 2) | `SessionControl{op=READY}` |
| Session reset-init | `24` `CONTENT_TYPE_SESSION_RESET_INIT` | tie-break winner (atomic re-init) | real X3DH first-ratchet carrier (msgNum=0) — **not** a pure signal |
| End session | `21` `CONTENT_TYPE_SESSION_RESET` | either | 16-byte sentinel (unencrypted) |

`SessionControl` (already vendored in `app/src/main/proto/messaging/e2ee.proto`,
`ContentType` enum in `app/src/main/proto/core/envelope.proto` — proto plumbing
for this is done, the Kotlin consumer/producer side is not):

```protobuf
message SessionControl {
  uint32 version = 1;   // unknown versions are ignored (forward-compat)
  SessionOp op = 2;     // PING / READY / RESET_INIT / END — mirrors content_type
  bytes nonce = 3;       // random per-signal; dedup + tie-break watchdog correlation
}
enum SessionOp { SESSION_OP_UNSPECIFIED=0; PING=1; READY=2; RESET_INIT=3; END=4; }
```

No checksum needed — integrity is already guaranteed by the Double Ratchet
AEAD tag.

**Consumer rule (byte-sniff, accept both):** dispatch on `content_type`
first; fall back to the legacy plaintext prefix only to interop with old iOS
peers still in the field. A non-null result means "handle as control, return
before persisting — never create a `Message` row." `RESET_INIT` (24) is
special: the X3DH init already consumed the payload, so the inner content is
just a sentinel.

**Producer rule (dual-send during transition):** set the typed
`content_type` **and** keep the legacy magic-string payload so old iOS peers
that only understand the string still interop. Once the legacy fallback is
retired fleet-wide on both platforms, switch the payload to a serialized
`SessionControl` (carrying `nonce`) and stop sending the string.

> **Server dependency:** the server must recognize `content_type` 25/26 or it
> re-emits them as `E2EE_SIGNAL` (1) and the typed path goes inert — fail-open,
> not a dropped message, so dual-send still works via the string either way.
> Server proto landed 2026-06-23 (`construct-server/shared/proto/core/envelope.proto`).

**Files to create:**
- Dispatch logic in whatever owns the decrypt → render pipeline once 5.2
  (Message Stream) exists — there is no message-receive path on Android yet
  to attach this to.

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

Legend: ✅ exists today · ⬜ planned, not created yet

```
app/src/main/java/com/construct/messenger/
├── MainActivity.kt                                     ✅
├── KonstructApp.kt (Application class)                 ✅
├── crypto/
│   ├── CryptoManager.kt                                ✅
│   └── uniffi/construct_core/construct_core.kt          ✅ (UniFFI, DO NOT EDIT)
├── data/
│   ├── api/
│   │   ├── GrpcClient.kt                               ✅
│   │   ├── README.md                                   ✅ (codegen pipeline + grpc-kotlin gotcha)
│   │   ├── AuthService.kt / KeyService.kt / ...         ⬜ not created — see §2.3
│   │   ├── MessageStreamService.kt                     ⬜
│   │   └── VeilProxy.kt   (thin wrapper over Rust VEIL coordinator; see §5.1) ⬜
│   ├── local/
│   │   ├── KeystoreManager.kt                          ✅ (tokens only — see §2.2)
│   │   └── FcmService.kt                               ⬜
│   └── repository/                                     ⬜ (no repository layer yet; use cases call CryptoManager/GrpcClient/SessionManager directly)
├── di/                                                  ⬜ (no AppModule needed so far — see §2.1)
├── domain/
│   ├── model/                                           ⬜
│   └── usecase/
│       ├── RegisterUseCase.kt                          ✅
│       ├── LoginUseCase.kt                             ✅
│       ├── SendMessageUseCase.kt                       ⬜
│       ├── HealSessionUseCase.kt                       ⬜
│       ├── SetupRecoveryUseCase.kt                     ⬜
│       └── CallUseCase.kt                              ⬜
├── service/
│   └── SessionManager.kt                               ✅
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
│   │   ├── CTAvatar.kt    (identicon avatars, ported from iOS 2026-06)
│   │   └── ...
│   └── theme/
│       ├── Color.kt
│       ├── Type.kt
│       ├── Theme.kt
│       └── Symbol.kt
└── workers/
    └── MessageSyncWorker.kt                            ⬜
```

---

## Implementation Order

1. **Phase 1:** Crypto Core integration (UniFFI wrapper) — ✅ done
2. **Phase 2:** DI, Keystore, gRPC base — ✅ done (2.1 turned out unnecessary as drafted; 2.2 scoped to tokens)
3. **Phase 3:** Registration, Login, Session management — ✅ 3.1/3.2 done; 3.3 (healing) and 3.4 (session-control message format) pending
4. **Phase 4:** Recovery — pending
5. **Phase 5:** Message stream, VEIL transport — pending
6. **Phase 6:** WebRTC calls — pending
7. **Phase 7:** FCM push — pending
8. **Phase 8:** UI screens — pending (8.2 components in progress, e.g. `CTAvatar` identicons)
9. **Phase 9:** Localization, final polish — pending