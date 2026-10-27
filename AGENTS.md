# AGENTS.md — construct-android

The rules this project paid for in its first year, and the invariants of this repository. Each
line is here because breaking it already cost something; the incident behind it lives in the
vault. If a rule here and your plan disagree, the rule wins until a `decisions/` note says
otherwise.

| Area | Read first |
|---|---|
| Why these rules exist, and the checklist before any new protocol feature | `~/Code/construct-docs/decisions/rules-from-the-first-year.md` |
| What is done, what is next, "done when" | `docs/IMPLEMENTATION_PLAN.md` (the one plan; its §1 lists the doc per area) |
| Build, first clone, the `.so` | `README.md` |
| Sessions | `docs/SESSIONS.md` · `~/Code/construct-docs/decisions/sessions-renew-by-sending.md` |
| The send path, stealth, multi-device | `docs/WIRE_FORMAT_RULES.md` |
| Crypto API, binary format, gRPC | `docs/API_CRYPTO_GUIDE.md` · `docs/FFI_BINARY_FORMAT.md` · `docs/GRPC_LAYER.md` |
| UI | `docs/UI_RULES.md` · `docs/ANDROID_ONBOARDING.md` · `docs/MATERIAL3_MIGRATION.md` |
| Testing against iOS | `docs/STAND.md` |
| No Play Services | `~/Code/construct-docs/decisions/android-without-play-services.md` |
| First tasks for a new person | `GOOD_FIRST_ISSUES.md` |
| Everything else | `~/Code/construct-docs` (vault; its own `AGENTS.md` is authoritative) |

Android client of Konstruct: Kotlin, Jetpack Compose, Hilt. It uses `construct-core` through
UniFFI/JNI directly, the same path as iOS (not `construct-engine`). Layout under
`app/src/main/java/.../`: `crypto/` (`CryptoManager`), `invite/`, `stealth/`, `viewmodel/`, `ui/`,
`data/` (repositories, the core's store, gRPC), `domain/` (use cases), `service/`
(`MessagingRuntime`, `SessionManager`, router / processor), `di/`.

---

## The Zen of Konstruct

```
Someone has solved it before. Read them first.
The core decides; the app executes.
A message names the state it means. Time is not an address.
Reproduce, then fix.
One meaning, one carrier.
Bytes stay bytes.
Measure, then build.
The effect is the proof, not the exit code.
A test that cannot fail is worse than no test.
Boring before new.
Temporary is permanent, unless something checks it.
What nothing reads is not sent.
Silence is the worst failure: a message that never appears raises no error.
```

The canonical source is the vault note in the table above. Change it there first.

## The twelve rules

**Protocol**: almost all of the year's cost went here.

1. **Prior art first.** Before any protocol mechanism, write down how Signal / MLS (RFC 9420) /
   Matrix solve it and why we deviate. Group key management is MLS, not ours.
2. **The core decides, Android executes.** If two clients must compute it identically for a
   message to be readable, it is a `construct-core` API, written first. Kotlin makes no session or
   protocol decision. Open `construct-core/src/construct_core.udl` first: the core usually does it already.
3. **A control message names the state it refers to**: an epoch, a ratchet key or a number. A fix
   that adds a timer, cooldown, window, tie-break or retry budget means the message has no address.
   Stop and give it one.
4. **Reproduce before fixing.** A flaky-session symptom is fixed only on a stand that reproduces
   it (`docs/STAND.md`; `scripts/verify.sh --device`). The fix counts when the stand that failed
   now passes.
5. **One meaning, one carrier; different meanings, different types.** Read
   `~/Code/construct-docs/decisions/one-meaning-two-carriers.md` before adding a field, counter or
   timeout. A comment saying two things "must match" means one should call the other.

**Data and evidence**

6. **Binary stays binary**, source to destination. The FFI boundary takes `ByteArray` and CFE
   (a 16-byte header plus MessagePack), never JSON or base64. Base64 only at true text boundaries
   (QR, deep links).
7. **Measure, then build.** For network, censorship, performance and battery, measure on the real
   path first.
8. **Verify the effect, not the exit code.** Check a delete by querying after it, a release by the
   APK the draft holds, and a fix by the device log.

**Tests and process**

9. **A test must be able to fail.** Put the decision in a pure function and test that function.
   Mutation-check every new test, one mutation at a time.
10. **Depth before breadth.** A new surface starts when the last one is boring on the stand and
    with testers. Testers who stop testing is a P0.
11. **A temporary exception has an end date and a check** that notices the debt growing
    (`check_ui_tokens.sh` baselines are one).
12. **A branch and a PR, always.** See *Commits and branches*.

**A change on the delivery or crypto path answers three questions** in its commit message. Any
commit touching `service`, `crypto`, `stealth`, `invite`, `data/api`, `domain/usecase` or `proto`
needs them, even when it changes only comments; `verify.sh` checks them.
1. What does the server, or any relay, learn that it did not learn before?
2. What can a party (server, sender, sibling device) withhold or substitute that it could not before?
3. Which trust boundary moves, and in which direction?

An honest "something" to 1 or 2 is a `decisions/` note, not a merge.

**A producer with no consumer is a defect.** A send, an action or a field lands with its reader in
the same change, or it is removed.

---

## Invariants of this repo

### Core and build

- **The `.so` files are not in git** (`app/src/main/jniLibs/` is ignored; purged from history on
  2026-09-22). Get them with `scripts/fetch_core.sh`, or build them with `./build_crypto_lib.sh`
  (`--arm64`, `--x86`), which also regenerates the bindings.
- **`construct-core.lock` pins the core.** `checkCoreLibrary` runs before `preBuild` and fails when
  a `.so`'s `CONSTRUCT_CORE_VERSION` stamp differs from the lock. A mismatched `.so` does not fail
  at the call: the app does not start. `build_crypto_lib.sh` rewrites the lock, so moving cores is
  a reviewed diff.
- **Take the `.so` and `construct_core.kt` from the same build.** The lock pins only the library.
  Never hand-edit the generated binding.
- **Unit tests call the real core** from `app/src/test/host/<os>-<cpu>/` (not in git;
  `checkHostCoreLibrary` holds it to the lock). Never replace a core call with a Kotlin stand-in.
- **NDK newer than `construct-core/.cargo/config.toml`:** set `CARGO_TARGET_<TRIPLE>_LINKER` /
  `_AR` rather than editing the core's config.
- **Apple Silicon needs Rosetta** (`softwareupdate --install-rosetta`): the grpc-java plugin
  labelled `osx-aarch_64` is x86_64. Never run Gradle with `--rerun-tasks`.
- **Protos are vendored** in `app/src/main/proto/` from `construct-protos`; sync with
  `scripts/sync-protos.sh`. The Gradle protobuf plugin generates the stubs.
- Commands: `./gradlew build | test | connectedAndroidTest | assembleDebug | assembleRelease`.

### Verify, CI, release

- **Before pushing: `scripts/verify.sh`**, after committing (its checks read commits). Add
  `--device` when the change touches sessions, delivery or start-up. CI runs the same script on
  every push and PR.
- **Release:** a tag `v<versionName>` (or `release.yml` by hand) builds a draft release, signed
  with the repository-secret key and refused unless the signer is the pinned certificate. A
  person publishes it. A tag not on `main` is a tester build (`INTERNAL_TOOLS`: diagnostic log in
  Settings → Diagnostics); a build from `main` writes no log.
- **Version:** `versionName` in `app/build.gradle` is bumped by hand: the minor version for a
  build handed to testers, the patch version for a fix build. `versionCode` is the commit count.
- **Status lives only in `docs/IMPLEMENTATION_PLAN.md`.** Closing a task updates the plan in the
  same commit.

### Code

- `@HiltViewModel` for every ViewModel; no manual factories. Compose only, no XML layouts.
- **All crypto goes through `CryptoManager`.** UI and ViewModels talk to repositories only: no
  `data/api`, `crypto`, `stealth` or proto envelopes.
- Local data lives in the core's encrypted store (`LocalStores`, `Core*Store`). Room was removed
  on 2026-10-19.
- gRPC lives in the `GrpcClient` singleton (two channels: auth and sealed). `MessagingRuntime`
  owns cold start.

### Sessions — the core decides, Android executes

Read `docs/SESSIONS.md` before touching `SessionManager`, `MessageProcessor`,
`ProcessorEffectsImpl`, `CfeTimerBridge` or anything that sends a control message.
- **A session renews by sending.** A message with the handshake header opens a new state beside
  the current one; the core promotes the one that decrypts.
- **There is no END_SESSION.** An unreadable message is answered by the core with
  DECRYPTION_ERROR (content type 28) naming its state. The writer retires that state only if it is
  current and resends once. Reset, chat and contact deletion, and logout are local: nothing is sent.
- **Never rebuild** a teardown message, SESSION_RESET_INIT, ping/ready, confirm windows,
  tie-breaks, heal, cooldowns, retry budgets, stale-by-timestamp checks or "announce the reset".
  Older docs, old iOS code and memory describe them; do not port them.
- **Everything passed to the core is a `CryptoDeviceId`**, never an account id. Mixing them does
  not throw; the message simply never opens.
- Both `when`s over `CfeAction` are exhaustive. Never add `else ->`: a new core action must fail
  to compile until it is handled.

### Wire format

Read `docs/WIRE_FORMAT_RULES.md` before `SendMessageUseCase`, stealth or anything multi-device.
**An unsealed envelope carries nothing beyond the sender/recipient pair already on it.** Before
adding a field, write who reads it and what it tells the server about who talks to whom. iOS found
five such defects only from device logs.

### No Google Play Services

Decided 2026-08-23. **Nothing may require GMS on the device**: no FCM, no Play Integrity, no Play
Location, no ML Kit. FCM would give a third party the timing of every message and a stable device
id. Build-time Google dependencies (Hilt, KSP, protobuf) are fine. A GMS-free client is also the
GrapheneOS client.
- **Delivery is our own connection**: a persistent `MessageStream` in a foreground service.
  UnifiedPush is an option, never the base. A call arrives as its E2EE offer on that stream, not
  by VoIP push.
- `scripts/check_no_play_services.sh` (run by `verify.sh`) holds the manifest to this.
- **One APK**: no GMS/FOSS flavours. The battery cost and the persistent notification are stated
  to the user.
- **The permission list is fixed, and adding one is the owner's decision.** Declared: network,
  foreground-service and notification permissions; `POST_NOTIFICATIONS` (13+); the
  `remoteMessaging` FGS type (14+; 11–13 use two-argument `startForeground`); others below.
  - `CAMERA`, asked for only when the invite scanner opens, a video note is first recorded, or a
    video call turns its camera on. Scanning uses CameraX + ZXing; frames go nowhere but the call.
  - `RECORD_AUDIO`, asked for only on the first voice note, video note or call.
  - `MODIFY_AUDIO_SETTINGS`, granted at install.
  - For calls: `MANAGE_OWN_CALLS` (self-managed; nothing goes to the call log),
    `FOREGROUND_SERVICE_PHONE_CALL` / `_MICROPHONE` and `USE_FULL_SCREEN_INTENT`.
  - `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: music from a chat, shown under its file name only, and
    never for voice notes.
- **Services:** `MessagingForegroundService`, `CallService` (only during a call) and
  `MusicPlaybackService` (only while music is loaded) are not exported. `CallConnectionService` is
  exported for Telecom and guarded by `BIND_TELECOM_CONNECTION_SERVICE`. There is no `<receiver>`
  and no push-provider code.

### Design System

**Material 3, themed from the designer's Figma file. Controls are Material's; JetBrains Mono
chrome and dark by default are ours; message text is the reader's.** Read `docs/UI_RULES.md`
before touching UI.

---

## Commits and branches

- Conventional Commits: `feat|fix|refactor|chore|docs|test(scope): …`.
- **Never commit on `develop` (testers) or `main` (public).** Cut a topic branch from an up-to-date
  `develop` and merge it through a PR. `develop → main` is the owner's step. Agents push and open
  PRs only when asked, after `verify.sh` passes.
- A commit made on `develop` by mistake and not pushed moves with
  `git branch <topic> && git reset --keep origin/develop && git switch <topic>`. Never rewrite
  pushed history.
- Check the branch and `git status` before every commit, because other sessions share the tree.
  Never `checkout -- .`, `restore .`, `reset --hard` or `stash`: name the files.

## Docs

The vault (`~/Code/construct-docs`) holds everything that is not a file in this repo; its
`AGENTS.md` is authoritative. Search it before an architectural decision.
- After the work, progress goes into the task note (`todo/NNN-*.md`).
- A decision that constrains future work goes into `decisions/`, with Context, Decision,
  Rationale, Consequences, Status and Date.
- A root cause found the hard way, or a choice with rejected alternatives, gets a session note
  `sessions/YYYY-MM-DD-<topic>.md` whose `## Why` is filled in.
- Patch the affected spec in the same session, and add one line to `log.md`:
  `[YYYY-MM-DD HH:MM] note | <topic>`.
- The Android design doc is `docs/ANDROID_ONBOARDING.md`; the vault keeps a pointer, not a copy.
