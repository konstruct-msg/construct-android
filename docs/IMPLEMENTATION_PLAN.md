# Konstrukt Messenger Android — Implementation Plan

> **Last actualized:** 2026-09-21. Working slice is **1:1 text over production gRPC**
> with the current `construct-core` CFE artifact (`0.17.0+1241ec58d465`).
> `veil-front` lands via a construct-core rebuild + flag flip (no Kotlin
> routing). See `construct-docs/cryptocore/OBFUSCATION_IMPLEMENTATION_PLAN_veil-front.md`.

> ## Current reality vs this plan
> Current reality. On `develop`: UniFFI `OrchestratorCore`, two gRPC
> channels, PASETO auth, OTPK upload, `MessagingRuntime`, CFE receive,
> `SendMessageUseCase` (KNST + fail-closed stealth), v5 invites, Chat/Synaps
> on real repositories. UI does **not** import gRPC, crypto, stealth, or
> envelopes. The receive and send paths execute the same typed CFE actions as
> iOS; durable state is written through typed secure-store slots.
>
> The first multi-device seams are now closed: `CryptoManager` passes only the
> derived `CryptoDeviceId` into construct-core, `PeerDeviceRegistry` persists
> account→device mappings, `GetPreKeyBundlesResponse.active_devices` is honoured,
> and `CfeTimerBridge` feeds AppLaunched/reconnect/timer events back to CFE.
> Still open: recovery (4), VEIL (5.1), calls (6), settings subscreens,
> honeycomb Synaps, Play packaging (9.2), queued multi-carrier receive walking,
> plus live iOS↔Android interop and Android 11 hardware smoke. Recipient/replica fan-out,
> SSR1 sender-sync receive, bundle candidate walking, and planned teardown are
> now wired through the core plans.
> No FCM is planned: delivery remains the persistent stream/foreground-service
> path, per the no-GMS decision.
>
> App package is **`com.construct.messenger`**. Canon: `docs/ANDROID_ONBOARDING.md`.
> Envelope rules: `docs/WIRE_FORMAT_RULES.md`. New UI work: `GOOD_FIRST_ISSUES.md`.

## Phase 0: Project Setup (DONE)
- ✅ Gradle 9.5 + AGP 9.3.1 + Kotlin 2.2.10 + KSP 2.3.6
- ✅ Hilt 2.60.1 + Room 2.8.5 (KSP-compatible)
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

The checked-in `.so` files and generated Kotlin binding come from the official
rolling `construct-core` Android artifact, built with `android,post-quantum`.
The source build remains useful for core development, but Android consumes the
artifact from `https://github.com/konstruct-msg/construct-core/releases/download/latest/construct-core-android.tar.gz`.
Refresh both the three ABIs and `construct_core.kt` together whenever the UDL
changes; never hand-edit the generated binding.

**Files:**
- `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libconstruct_core.so`
- `app/src/main/java/.../crypto/uniffi/construct_core/construct_core.kt`
  (UniFFI bindings, package `uniffi.construct_core` — DO NOT EDIT, regenerate)

### 1.2 Crypto API Wrapper
**Status:** ✅ Done (OrchestratorCore + CFE state snapshots + PQ contribution)
**Priority:** HIGH
**Depends on:** 1.1

`CryptoManager.kt` keeps the bootstrap `ClassicCryptoCore` only for registration,
then promotes to `OrchestratorCore`, which is the messaging core:
`loadOrCreate()`, `setLocalUserId`, `exportPrivateKeys`, `generateOneTimePrekeys`,
`initSession`/`initReceivingSession`, `encryptMessage`/`decryptMessage`,
`exportSessionBytes`/`importSessionBytes` (CFE binary), `removeSession`,
`getAllSessionContactIds`, `applyPqContribution`, orchestrator/PQ snapshot
export/import, `forgetContactState`, `generateMnemonic`, `deriveRecoveryKeypair`,
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
| Session JSON (per contact) | EncryptedSharedPrefs | ❌ not here — session bytes are CFE binary via `CryptoManager`/`SessionManager`, never JSON; persisted in Room `session_state` via `SessionStateStore` (2026-07-17), incl. `session_meta.establishedAt` for the stale-END_SESSION filter |
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
`KeyService.GetPreKeyBundle` (3.2, via `SessionManager`; invite verify
passes `consume_one_time_prekey=false`), `KeyService.UploadPreKeys` (3.1),
`MessagingService.SendMessage` / `SendSealedMessage` / `MessageStream` /
`GetPendingMessages` (5.2), `InviteService.AcceptInvite` / `RevokeInvite`.

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
**Status:** ✅ Done (steps 1–4; recovery setup is Phase 4)
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
- Step 6, recovery phrase setup (`SetRecoveryKey`) — Phase 4.

**Files:**
- `domain/usecase/RegisterUseCase.kt`
- `domain/usecase/LoginUseCase.kt`
- `domain/usecase/UploadPreKeysUseCase.kt` — OTPK batch 100 after register;
  PQ capability re-advertise.

### 3.2 Session Lifecycle
**Status:** ✅ Device-addressed core boundary and durable account→device registry
are implemented for the current multi-device send/receive slice (INITIATOR + RESPONDER
via CFE receive). The network remains account-addressed; `CryptoDeviceId` is used for
core sessions and `active_devices` is the only server-authoritative pruning input.
Send fan-out covers recipient devices and own replicas; receive init walks every
non-destructive bundle candidate for the current carrier. A queued multi-carrier
reconciliation pass and live iOS↔Android interop remain open.
**Priority:** HIGH

**States:** NONE -> INITIALIZING -> ACTIVE -> HEALING -> NONE

`SessionManager.kt` and `PeerDeviceRegistry.kt`:
```kotlin
account id ──PeerDeviceRegistry──> CryptoDeviceId
    │                                  │
    └─ gRPC / Room / sealed recipient  └─ CFE session / AD / timer actions
```

`exportSessions()` / `importSessions()` use `ByteArray`, not `String`, per the
CFE-binary rule (§1.2). `SessionStateStore` maps the core's typed
`CfeSecureStoreSlot` values to Room keys: hot session, archive, deferred PQ,
Kyber snapshot, signed-prekey slot, and orchestrator snapshot. Cold start
restores orchestrator/PQ snapshots first and imports only `Session` slots before
opening the stream. Send/receive persist the returned state *before* the unary
RPC (sender-state-durability-before-send).

**Files:**
- `service/SessionManager.kt`
- `service/MessagingRuntime.kt`
- `data/local/SessionStateStore.kt`

### 3.3 Session Healing
**Status:** ✅ Core decision path done; Android bridge is partial. Rust CFE owns
the heal/END_SESSION decision and returns typed actions. Android executes
`SessionHealNeeded`, `EndSessionSuppressed`, `SessionTerminated`, typed storage,
and the sender-state durability rule. `CfeTimerBridge` now executes
`ScheduleTimer`/`CancelTimer`, `TimerFired`, `AppLaunched`, and
`NetworkReconnected`; per-device teardown planning, recipient/replica fan-out and
SSR1 sender-sync routing are wired; queued multi-carrier reconciliation remains open.
`HealSuppressed`/`EndSessionSuppressed` hold the stream cursor (do **not** ACK).
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

**Android bridge:**
- `service/MessageProcessor.kt`
- `service/ProcessorEffectsImpl.kt`
- `domain/usecase/HealSessionUseCase.kt` (transport side effect only)

### 3.4 Session-Control Message Format
**Status:** Partial — END_SESSION (21) and RESET_INIT (24) have live inbound
handlers. PING/READY (25/26) have typed producers and are non-renderable after
decrypt, but their inbound session-confirmation/watchdog transitions are not
wired yet. Android is greenfield: do **not** dual-send legacy magic strings.
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
`decisions/binary-control-message-format.md`). Android uses two real wire
forms: unencrypted outer-envelope types for reset/reset-init, and encrypted
KNST type byte 5 for PING/READY. The latter stay inside Double-Ratchet
ciphertext; `IncomingPlaintext` filters them before Room persistence.

| Signal | Wire discriminator | Direction | Payload |
|--------|--------------------|-----------|---------|
| Session ping | KNST byte 5 = `25` `CONTENT_TYPE_SESSION_PING` | INITIATOR → peer | encrypted `SessionControl{op=PING}` |
| Session ready | KNST byte 5 = `26` `CONTENT_TYPE_SESSION_READY` | RESPONDER → INITIATOR | encrypted `SessionControl{op=READY}` |
| Session reset-init | outer `content_type=24` `CONTENT_TYPE_SESSION_RESET_INIT` | tie-break winner | real X3DH first-ratchet carrier (msgNum=0), not a pure signal |
| End session | outer `content_type=21` `CONTENT_TYPE_SESSION_RESET` | either | unencrypted random 1024-byte pad |

`SessionControl` is vendored in `app/src/main/proto/messaging/e2ee.proto`; its
producer is `SessionControlUseCase.sendEncryptedControl`:

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

**Consumer rule:** dispatch outer 21/24 before the normal chat pipeline. After
decrypt, inspect the KNST type before decoding a `MessageContent`; types 25/26
must return before Room persistence. `RESET_INIT` (24) is special because its
payload is the real X3DH carrier and must enter responder init.

**Producer rule:** Android is greenfield — produce typed `SessionControl` only.
Do **not** dual-send the iOS legacy magic string. PING/READY travel as ordinary
encrypted messages at the outer envelope so the relay does not need to preserve
their semantic type; the KNST header is visible only after decrypt.

**Where it lives today:** `service/MessageRouter.kt` handles outer control types;
`IncomingPlaintext.kt` filters decrypted KNST controls; `SessionControlUseCase.kt`
produces typed PING/READY and END_SESSION.
**Current handler state:** inbound 21 archives the local session without a bounce;
24 goes through the responder-init/CFE path; 25/26 producers exist and all four
types are guaranteed non-renderable. Inbound PING/READY still need their explicit
session-confirmation/watchdog transitions; today they are ACKed as control frames.

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
**Status:** ✅ Done (transport + runtime + 1:1 send, typed CFE persistence,
2026-09-17)
**Priority:** HIGH
**Depends on:** 3.2

```
Cold start: import CFE sessions → hydrate ACK → drain GetPendingMessages
Client -> Server: Subscribe(direct:<sorted ids>)
Server -> Client: MessageEnvelope (stream)
KeepAlive: every 25s
On disconnect: reconnect with persisted cursor
Send: MessageContent → KNST → CFE OutgoingMessage → persist session → unary
```

Stealth on send is **fail-closed** (no identity key → do not identified-downgrade).
Identified envelope carries sender+recipient only — no `conversation_id`.
`GetPreKeyBundle` for invite verify uses `consume_one_time_prekey=false`.

**Files:**
- `data/api/MessageStreamService.kt`
- `data/api/MessagingService.kt`
- `service/MessagingRuntime.kt`
- `service/MessageRouter.kt` / `MessageProcessor.kt` / `ProcessorEffectsImpl.kt`
- `domain/usecase/SendMessageUseCase.kt`

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

## Phase 7: Background Delivery (No GMS)

### 7.1 Persistent foreground stream
**Status:** ✅ Done for the current 1:1 slice (2026-09-18); real-device lifecycle
and Doze smoke still required
**Priority:** HIGH
**Depends on:** 5.2

`MessagingForegroundService` owns the process-lifetime host for the existing
`MessageStream`; it is not a second delivery channel. It starts only for an
authenticated identity, restores the runtime after sticky process recreation,
and stops when no identity can be restored or the user logs out.

- Android 8–13 use the ordinary two-argument `startForeground` path.
- Android 13+ requests `POST_NOTIFICATIONS`.
- Android 14+ declares and supplies the `remoteMessaging` foreground-service type.
- Android 11 (API 30) is supported by the normal notification-channel path.
- No FCM SDK, Play Integrity, Play Location, GMS/FOSS flavors, receiver, or
  provider token registration is part of the base APK.
- UnifiedPush may be added later only as an optional accelerator; the persistent
  stream must remain fully functional without a distributor.

**Files:**
- `service/MessagingForegroundService.kt`
- `service/MessagingRuntime.kt`
- `MainActivity.kt` (Android 13+ notification permission)
- `AndroidManifest.xml`

**Still to verify on hardware:** process kill/recreation, reboot, network
loss/reconnect, pending-message drain, Doze/battery behavior, and delivery
against a live iOS peer.

---

## Phase 8: UI Components

### 8.1 Navigation & Screens
**Status:** In Progress (1:1 text path wired to real repositories; settings/calls still skeleton)
**Priority:** HIGH
**Depends on:** Phase 1–5.2 and 7 for the current 1:1 text/delivery slice (done);
6 for calls

```kotlin
// Navigation
Splash -> Onboarding -> Orientation -> Main (Chats / Synaps / Calls / Settings)
                    -> Chat
                    -> Recovery (if recovering)   // not built
```

**Screens implemented (real data unless noted):**
- `SplashScreen` — restore session / `MessagingRuntime`.
- `OnboardingScreen` — username + `AuthRepository.initializeIdentity`.
- `OrientationScreen` — first-run walkthrough (replay from Settings still missing).
- `MainTabView` — Chats / Synaps / Calls / Settings on Material3 `NavigationBar`.
- `ChatsListScreen` — Room `ChatsRepository`; empty CTA opens Synaps (not a fake contact).
- `ChatScreen` — `LazyColumn` bubbles + `MessageInputView`; `ChatViewModel` observes/sends via `MessagesRepository`.
- `SynapsScreen` — mint v5 invite (share/copy), paste-accept, contact list. Not honeycomb.
- `CallsScreen` — placeholder.
- `SettingsScreen` — settings root skeleton.

**Still to implement:**
- Synaps honeycomb + FindUser / contact-request UI + invite QR.
- Settings subscreens: Account, Appearance, Network, Security.
- Chat search overlay, pagination, call button.
- Call screen (WebRTC).
- Recovery flow screens.

### 8.2 Compose Components
**Status:** In Progress
**Priority:** HIGH

**Implemented components (aligned with iOS canon):**
- `CTNavBar`, `CTTabBar` (legacy; prefer Material3 `NavigationBar`), `CTButton`,
  `CTTextField`, `CTSearchBar`, `CTSectionGroup` / `CTSettingsSectionHeader` /
  `CTSettingsRow`, `CTSep`, `CTSystemMessage`, `CTStatusBadge`, `CTAvatar`,
  `CTLogoView`, `ConstructNavRow`, `ConstructActionRow`, `ConstructButtonRow`,
  `CTModeSelector`, `ConnectionStatusIndicator`, `CTNoise`, `ChatRow`,
  `MessageBubble`, `MessageInputView`.

**Still to implement / enhance:**
- Chat search overlay, scroll-to-bottom control, attach/mic (icons exist; no media pipeline).
- `CTLoader` / progress indicators.
- Wire `ConnectionStatusIndicator` to stream state.

---

## Phase 9: Polish

### 9.1 Localization
**Status:** In Progress (`en` + `ru` for onboarding / chats / Synaps / chat composer; `ja` not started)
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
│   │   ├── MessageStreamService.kt                     ✅
│   │   ├── MessagingService.kt                         ✅
│   │   └── VeilProxy.kt   (thin wrapper over Rust VEIL coordinator; see §5.1) ⬜
│   ├── local/
│   │   ├── KeystoreManager.kt                          ✅ (tokens only — see §2.2)
│   │   ├── AckStore.kt / SessionStateStore.kt          ✅
│   │   ├── PendingInviteStore.kt                       ✅ (konstruct://add)
│   │   └── db/                                         ✅ chats/messages/users/acks/sessions
│   └── repository/                                     ✅ Auth / Chats / Messages / Contacts
├── di/                                                  ✅ Crypto / Database / Repository / Store
├── invite/                                              ✅ v5 mint / verify / AcceptInvite
├── stealth/                                             ✅ policy / wallet / cert / seal
├── domain/
│   └── usecase/
│       ├── RegisterUseCase.kt                          ✅
│       ├── LoginUseCase.kt                             ✅
│       ├── UploadPreKeysUseCase.kt                     ✅
│       ├── SendMessageUseCase.kt                       ✅
│       ├── SessionControlUseCase.kt                    ✅
│       ├── HealSessionUseCase.kt                       ✅
│       ├── SendReceiptUseCase.kt                       ✅
│       ├── ResponderInitUseCase.kt                     ✅
│       ├── SetupRecoveryUseCase.kt                     ⬜
│       └── CallUseCase.kt                              ⬜
├── service/
│   ├── SessionManager.kt                               ✅
│   ├── MessagingRuntime.kt                             ✅
│   ├── MessageRouter.kt / MessageProcessor.kt          ✅
│   ├── ProcessorEffectsImpl.kt                         ✅ (heal/END_SESSION transport effects)
│   ├── CfeTimerBridge.kt                               ✅
│   └── MessagingForegroundService.kt                   ✅
├── ui/
│   ├── navigation/
│   │   ├── Screen.kt
│   │   └── NavHost.kt
│   ├── screens/
│   │   ├── splash/
│   │   ├── onboarding/
│   │   ├── main/
│   │   ├── chats/         ✅ ChatsListScreen.kt, ChatRow.kt
│   │   ├── chat/          ✅ ChatScreen.kt (bubbles + composer)
│   │   ├── synaps/        ✅ SynapsScreen.kt (mint / paste / list)
│   │   ├── orientation/   ✅ OrientationScreen.kt
│   │   ├── calls/         ✅ CallsScreen.kt (placeholder)
│   │   └── settings/      ✅ SettingsScreen.kt (root only)
│   ├── viewmodel/         ✅ Splash / Onboarding / Orientation / Main / Chat / Synaps
│   ├── components/        ✅ (see §8.2)
│   │   ├── CTNavBar.kt
│   │   ├── CTButton.kt
│   │   ├── CTTextField.kt
│   │   ├── CTSearchBar.kt
│   │   ├── CTSectionGroup.kt
│   │   ├── CTSettingsSectionHeader.kt
│   │   ├── CTSettingsRow.kt
│   │   ├── CTSep.kt
│   │   ├── CTSystemMessage.kt
│   │   ├── CTStatusBadge.kt
│   │   ├── CTAvatar.kt    (identicon avatars, ported from iOS 2026-06)
│   │   ├── CTLogoView.kt
│   │   ├── ConstructNavRow.kt
│   │   ├── ChatRow.kt
│   │   └── CTTabBar.kt    (legacy; prefer Material3 NavigationBar)
│   └── theme/
│       ├── Color.kt
│       ├── Type.kt
│       ├── Theme.kt
│       └── Symbol.kt
```

---

## Phase 5.3 — Remaining 1:1 API (no device required)

**Status:** implemented 2026-08-19 (JVM tests; no live iOS↔Android)  
**Goal:** finish 1:1 production-gRPC surface that iOS already calls, plus JVM
wire-contract tests. Not VEIL / calls / MLS / media / recovery.
Unauth sealed transport and END_SESSION identity-box stay deferred
(lockstep with iOS / missing FFI).

| # | Item | Why | Done when |
|---|------|-----|-----------|
| 1 | Wire contract tests | Interop stand-in | KNST / receipt / invite v5 / no `conversation_id` / END_SESSION 1024 / SealedInner type 0 |
| 2 | `CheckUsernameAvailability` + `SetDiscoverable` | FindUser is dead without opt-in | alias checked on onboarding; discoverable=true after register-with-alias |
| 3 | `Logout` + END_SESSION to every live session | iOS sign-out | tokens cleared, runtime stopped, peers notified |
| 4 | `GetUserProfile` / `UpdateUserProfile` | names after FindUser / accept | repository + Settings profile row |
| 5 | `RotateSignedPreKey` | SPK ages out (~7d) | bootstrap after runtime start; classic SPK (Kyber later) |
| 6 | SESSION_PING / SESSION_READY producers | RESPONDER handshake phase 2 | READY after `initReceivingSession`; type in KNST byte 5 |
| 7 | Invite `jti` journal | `RevokeInvite` needs a local list | Room row per mint; revoke burns journal + RPC |
| 8 | Bounded send retry | iOS send coordinator | 3 attempts, same `messageId` / idempotency, only if `retryable` |

---

## Implementation Order

1. **Phase 1:** Crypto Core integration (UniFFI wrapper) — ✅ done
2. **Phase 2:** DI, Keystore, gRPC base — ✅ done (2.1 turned out unnecessary as drafted; 2.2 scoped to tokens)
3. **Phase 3:** Registration, Login, Session management — ✅ 3.1–3.3 done; 3.4 classify + inbound END_SESSION / RESET_INIT
4. **Phase 4:** Recovery — pending
5. **Phase 5:** Message stream + 1:1 send — ✅ done; VEIL transport — pending
6. **Phase 6:** WebRTC calls — pending
7. **Phase 7:** persistent foreground delivery without GMS — ✅ implemented;
   Android 11/device lifecycle smoke pending
8. **Phase 8:** UI — 1:1 Chat/Synaps wired; FindUser + logout + discoverable; honeycomb/settings subscreens pending
9. **Phase 9:** Localization `en`/`ru` in progress; Play packaging pending
