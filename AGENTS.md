# AGENTS.md — construct-android

Context for AI agents working in this repository — and the invariants a person needs too.

**Start here, in this order:** this file → `docs/IMPLEMENTATION_PLAN.md` (the one plan: what is
done, what is next, priorities and "done when") → `README.md` (build; the `.so` is not in git) →
the document for your area, listed in the plan's §1. First tasks for a new person:
`GOOD_FIRST_ISSUES.md`.

---

## What is construct-android?

Android client for Construct Messenger. Built with Kotlin + Jetpack Compose + Hilt.
Uses `construct-core` via UniFFI/JNI bindings (same direct path as iOS — NOT via `construct-engine`).

---

## Architecture

```
app/src/main/
├── java/.../
│   ├── crypto/         — CryptoManager (UniFFI wrapper around construct-core)
│   ├── invite/         — device-minted v5 invites (CIv1 + konstruct://add)
│   ├── stealth/        — sealed-sender policy / wallet / cert (fail-closed on send)
│   ├── viewmodel/      — Hilt-injected ViewModels (@HiltViewModel)
│   ├── ui/             — Compose screens and components
│   ├── data/           — repositories, Room, gRPC (UI must not import data/api)
│   ├── domain/         — Register / Login / UploadPreKeys / SendMessage
│   ├── service/        — MessagingRuntime, SessionManager, router / processor
│   └── di/             — Hilt modules
└── jniLibs/            — .so from construct-core; NOT in git, see below
    ├── arm64-v8a/
    ├── armeabi-v7a/
    └── x86_64/
```

**The `.so` files are not in git** — `app/src/main/jniLibs/` is ignored, and a fresh clone has to
build them before Gradle can assemble anything. Same rule as the iOS `*.xcframework` binaries, and
for the same reason: they are build output of another repository, reproducible from it by one
command, and committing them put 149.5 MB of binaries into a repository whose every other tracked
file adds up to 2.6 MB. They were purged from history on 2026-09-22.

They must be rebuilt whenever construct-core changes. UniFFI checks its interface checksums when
the library loads, so a `.so` older than the bindings beside it does not fail at the call — the
app does not start, and the reason is not on the screen.

**`construct-core.lock` records which core, and `checkCoreLibrary` enforces it.** Taking the
binaries out of git took the record with them, so the lock file puts it back as one line of text:
the `CONSTRUCT_CORE_VERSION` stamp the library carries in its own `.rodata`. A Gradle task reads
that stamp out of each ABI's `.so` before `preBuild` and fails with both values when they differ,
so the mismatch is caught on the machine that can fix it rather than at start-up on a device.
`./build_crypto_lib.sh` rewrites the lock from what it just built, which makes moving to a new
core a diff someone approves instead of something that happens quietly.

Take the `.so` and `construct_core.kt` from the **same** build — the published archive contains
both, and the lock only pins the library. `scripts/fetch_core.sh` installs the build the lock names.

**The unit tests call the real core.** Since 2026-10-02 (core 0.31, the KNST frame codec) the JVM
tests load the same build for the machine they run on, from `app/src/test/host/<os>-<cpu>/` —
JNA's resource prefix, the layout construct-core's archive ships under `host/`. Not in git either;
`checkHostCoreLibrary` holds its stamp to the lock before any test runs, so tests never check one
core while the app ships another. Do not replace a core call with a Kotlin stand-in to make a test
easier: that is how the two drift.

### Rust core integration

```bash
# Build construct-core for Android targets (run from construct-core/)
cargo build --release --target aarch64-linux-android
cargo build --release --target armv7-linux-androideabi
cargo build --release --target x86_64-linux-android

# Generate UniFFI Kotlin bindings
uniffi-bindgen generate   --library target/aarch64-linux-android/release/libconstruct_core.so   --language kotlin   --out-dir bindings/kotlin
```

Copy resulting `.so` files to `app/src/main/jniLibs/<abi>/` — or just run `./build_crypto_lib.sh`,
which does all of the above. Either way the files stay untracked.

---

## Build

```bash
./gradlew build                    # build
./gradlew test                     # unit tests
./gradlew connectedAndroidTest     # instrumented tests (device/emulator)
./gradlew assembleDebug            # debug APK
./gradlew assembleRelease          # release APK
```

---

## Key conventions

- Use `@HiltViewModel` for all ViewModels — no manual ViewModel factories
- All crypto operations go through `CryptoManager` — do not call UniFFI bindings directly from UI
- UI / ViewModels talk to **repositories only** — no `data/api`, `crypto`, `stealth`, or proto envelopes
- Compose UI only — no XML layouts
- Room DB for local message persistence
- gRPC lives in the `GrpcClient` singleton (two channels: auth + sealed); `MessagingRuntime` owns cold start
- **Before pushing: `scripts/verify.sh`** (add `--device` when the change touches sessions,
  delivery or start-up). It exists because this repo's breakages were mostly not test failures —
  commits that never compiled behind `checkCoreLibrary`, a `.so` from another core build, a core
  action with no executor. Its first run caught a commit whose tests no longer compiled.
- **CI** (`.github/workflows/ci.yml`) runs `scripts/verify.sh` on every push and PR with the core
  the lock names. **A release APK** is built by pushing a tag `v<versionName>` (or running
  `release.yml` by hand): signed with the key from the repository secrets and refused unless the
  signer is the pinned certificate; a tag leaves a draft release, which a person publishes.
- Status lives only in `docs/IMPLEMENTATION_PLAN.md`. Do not add a status line anywhere else —
  that is how this file, the README and the plan came to disagree.
- Closing a task from the plan means updating the plan in the same commit.
- **Version:** `versionName` in `app/build.gradle` is bumped by hand — minor for a build handed
  to testers, patch for a fix build; `versionCode` is the commit count and needs nothing.

---

## Commits and branches

[Conventional Commits](https://www.conventionalcommits.org/): `feat(scope): …`, `fix(scope): …`,
`refactor(scope): …`, `chore(scope): …`, `docs(scope): …`.

**Never commit on `develop` or `main`.** `develop` is what testers get — the release APK is built
from a `v<versionName>` tag on it; `main` is what goes public. Every change goes on a topic branch
cut from an up-to-date `develop` (`feat|fix|docs|chore|refactor|test/<topic>`) and lands in
`develop` through a GitHub pull request. `develop → main` is the release step, and the owner takes
it. Agents push and open the PR only when asked, and `scripts/verify.sh` passes first.

From 2026-09-11 to 2026-10-01 changes went straight to the default branch across the
construct-* repos — two people on the project made a branch per change look like ceremony. That
was reversed on purpose: the habit has to be in place before there is a public release for it to
break. A commit that landed on `develop` by mistake and is not pushed moves off it with
`git branch <topic> && git reset --keep origin/develop && git switch <topic>`. Pushed history is
never rewritten.

---

## Design System (read before touching any UI)

**Android is Material 3** (decided 2026-10-08, `~/Code/construct-docs/decisions/android-is-material-3.md`).
Until then the iOS app was the design canon and Android mirrored it; **that is over for the
look.** iOS goes its own way in SwiftUI, Android in Material, and the two apps are allowed to
differ. Features, texts, behaviour and the wire are still one meaning across both.

- **Source of truth: the designer's Figma file** — a local copy of the Material 3 kit (theme
  "Echo"), not iOS `ConstructTheme.swift`. New code carries no `**Canon:** iOS …` reference.
  The file is not wired in yet; until it is, the CT tokens below stay as they are.
- **Tokens are Material's roles**: colour = `ColorScheme` (Primary, On Primary, Primary
  Container, Surface, Surface Container …; one scheme per theme mode of the file — set **every**
  role, an unset one falls back to Compose's baseline purple), type = `Typography` from the font
  roles Brand / Plain. Values come from the Figma variables by a repeatable step, never retyped.
- **Controls are Material 3 components** themed by `MaterialTheme` — `Button`, `Switch`,
  `NavigationBar`, `TopAppBar`, dialogs, sheets, menus. Do not hand-draw a control, and do not
  build a new `CT*` look-alike of one.
- **What stays ours:** JetBrains Mono for the chrome and dark as the default. The rest of the
  identity (density, hairlines, identicon avatars, the `>` system-message prefix, terminal
  diagnostics) is not removed without asking the owner.
- **Terminal glyphs are decorative only** (since 2026-06-22): never `[ok] [err] [✓] [→]` for state
  or controls — Material icons + semantic colour, `Switch`, `Icons.Default.Check`. ASCII stays only
  as chrome: separators, the `>` prefix, `✷`.

### Migration (background work, one screen per commit)

Full plan and order: **`docs/MATERIAL3_MIGRATION.md`** — code moves first with no visible change
(proved by screenshot tests), the new look arrives later as one change of theme values.

1. **Theme**: the file's roles into `ColorScheme` / `Typography` in `ui/theme/`.
2. **Aliases**: `CTColor.*` / `CTFont.*` point at the roles, so the ~70 files reading them change
   colour without changing code.
3. **Components**: `CTNavBar`, `CTTabBar`, `CTButton`, `CTTextField`, `CTSearchBar`,
   `CTModeSelector` … give way to their Material counterparts screen by screen; an alias goes
   with its last caller. A screen built from now on is built in Material from the start.

### Today's tokens (until the migration replaces them)

`docs/ANDROID_ONBOARDING.md` §3 describes what the code has now — CT palette, `CTFont` roles,
the `CTStatus`/`CTStatusBadge` pattern, the icon map. Use it to read existing code, not as the
target. Tokens: `CTColor.*`, `CTFont.*` (`CTFont.message` for message text), `CTIcon.*`,
`CTSpace.*`, `CornerRadius.*`, `CTLayout.*`. No inline magic numbers: `scripts/check_ui_tokens.sh`
(run by `verify.sh`) fails when a count of hand-set sizes, faces or colours rises; a migration
lowers its baseline in the same commit.

---

## Sessions — the core decides, Android executes

**Read `docs/SESSIONS.md` before touching `SessionManager`, `MessageProcessor`,
`ProcessorEffectsImpl`, `CfeTimerBridge` or anything that sends a control message.**

- **A session renews by sending** (since 2026-09-27). Any message carrying the handshake header
  opens a new state beside the one held; the core keeps previous states and promotes the one that
  decrypts. There is no SESSION_RESET_INIT, ping/ready, confirm window, tie-break or heal.
- **There is no END_SESSION** (since 2026-09-28). A message nothing reads is answered by the core
  with a DECRYPTION_ERROR (content type 28) naming the state it was written on; the writer's core
  retires that state only if it is current and resends the named message once. Manual reset, chat
  and contact deletion and logout are **local** — nothing is sent.
- Do not add a teardown message, a cooldown, a retry budget, a stale-by-timestamp check or an
  "announce the reset" path. A message that names no state is the thing this replaced.
- **Everything passed to the core is a `CryptoDeviceId`**, never an account id. Mixing them does
  not throw; the message simply never opens.
- Before writing a session or crypto decision in Kotlin, open `construct-core/src/construct_core.udl`:
  the core probably already does it. Both `when`s over `CfeAction` are exhaustive — never add
  `else ->`; a new core action must fail to compile until it is handled.
- Older docs, iOS code at older revisions and your own memory all describe the removed mechanisms.
  Do not port them.

## Changes on the delivery or crypto path

A commit that touches sending, receiving, sessions, sealed sender or the envelope answers three
questions in its message — not "this is safe", but the answers:

1. What does the server (or any relay) learn that it did not learn before?
2. What can a party — server, sender, a sibling device — withhold or substitute that it could not
   before?
3. Which trust boundary moves, and in which direction?

An honest "something" to 1 or 2 is a design decision and goes through `construct-docs/decisions/`
before it is merged.

## No Google Play Services — decided 2026-08-23, before any delivery code existed

**Nothing in this app may require GMS on the device.** No FCM, no Play Integrity, no Play
Location. Google *build-time* dependencies are fine and already present (Hilt, KSP, protobuf) —
they ask nothing of the device.

This is checkable right now and must stay that way: the manifest declares network permissions
plus the Android foreground-service/notification permissions, `CAMERA` (asked for only when the
invite scanner opens, a video note is first recorded, or a video call is started, answered or has its
camera turned on; the QR is read by CameraX + ZXing, not ML Kit; a note is recorded by CameraX into the
app's cache; a call's camera is WebRTC's Camera2 capturer, frames go nowhere but the call, and it stops
whenever nobody sees the app), `RECORD_AUDIO` (asked for only when
the user taps the microphone for a voice note, records a video note, or makes or answers a call), `MODIFY_AUDIO_SETTINGS`
(install-time; a call's voice mode is refused without it), and for calls `MANAGE_OWN_CALLS`
(a self-managed `ConnectionService` — Telecom knows a call is on; nothing goes to the call log or
the dialer), `FOREGROUND_SERVICE_PHONE_CALL` / `_MICROPHONE` and `USE_FULL_SCREEN_INTENT` (a
ringing call over the lock screen). Services: the non-exported `MessagingForegroundService`, the
non-exported `CallService` (`phoneCall|microphone`, only while a call is on), and
`CallConnectionService` — exported because Telecom must bind it, guarded by
`BIND_TELECOM_CONNECTION_SERVICE`, which only the system holds. No `<receiver>` (a call
notification's buttons are service and activity intents), and no push-provider client code: a
call arrives as its E2EE offer on our own `MessageStream`, not by a VoIP push. On Android 13+
the app requests `POST_NOTIFICATIONS`; on Android 14+ it starts with the `remoteMessaging`
foreground-service type. Older supported devices, including Android 11, use the ordinary
two-argument `startForeground` path.

- **Delivery is our own connection**: a persistent `MessageStream` in a foreground service. Not a
  second notification channel.
- **UnifiedPush is an option, never the base.** The default path must work with no distributor.
- **One APK.** No GMS/FOSS flavours — two flavours are two delivery behaviours and a permanent
  question about which one is real.
- The battery cost and the persistent foreground notification are stated to the user, not hidden.

Why it is an invariant and not a preference: FCM would hand a third party the fact and timing of
every message you receive plus a stable device id — exactly the metadata sealed sender exists to
keep from our *own* server. And a client that needs no GMS **is** the GrapheneOS client, so this
is also why there will not be a separate one.

Full reasoning and rejected alternatives: `~/Code/construct-docs/decisions/android-without-play-services.md`.
Where Android sits among the platforms: `~/Code/construct-docs/decisions/client-platform-sequencing.md`.

## Wire format — read before touching the send path

**`docs/WIRE_FORMAT_RULES.md`** — what Android may and may not put on the envelope, and why.
Not optional reading before `SendMessageUseCase`, stealth, or anything multi-device.

iOS reached those rules through five defects that each lived for months and were found by reading
device logs rather than code: SENDER_SYNC routed on a field the server blanks by design (so no copy
ever arrived, since the feature shipped), the same unsealed envelope carrying `direct:me:partner`
in the clear, two device-id fields nobody reads, a heartbeat announcing itself on the outer
envelope, and a magic string with no reader that spent four months rendering as a visible bubble.
Every one of them was a field written by the client, read by no one, and paid for in metadata.

The one-line version: **an unsealed envelope must not carry anything beyond the sender/recipient
pair already on it**, and before adding any field, answer in writing who reads it and what it tells
the server about who talks to whom.

## Documentation

All project documentation: `~/Code/construct-docs` (Obsidian vault).
**Authoritative map + writing rules: `~/Code/construct-docs/AGENTS.md`** — read it before
contributing docs. The vault is a flat domain-folder structure (`architecture/`, `backend/`,
`client/`, `cryptocore/`, `security/`, `deployment/`, `sessions/`, `decisions/`, `_archive/`, …).
The Android design doc is this repo's `docs/ANDROID_ONBOARDING.md`; the vault keeps a pointer to
it, not a copy (two copies "kept in sync" had already diverged).

## Shared Construct Docs Workflow

The vault's own `~/Code/construct-docs/AGENTS.md` is **authoritative**. Summary below is the
operational subset for coding agents.

### Where durable reasoning goes

Any reasoning that informed a code change must survive beyond the chat session. After any session
involving architectural changes, design decisions, API/data-format changes, bug root-cause
analysis, or non-obvious implementation choices:

1. **Always** write a session note at `~/Code/construct-docs/sessions/YYYY-MM-DD-<topic>.md`.
2. **Always** fill in `## Why` — reasoning, alternatives considered, why rejected. Most important section.
3. If the decision will constrain future work, also create/update `~/Code/construct-docs/decisions/<slug>.md`.
4. Patch the affected spec in its domain folder in the **same** session — keep specs current.
5. Before creating a new note, search for an existing one and extend it rather than duplicating.

### Session note format

Plain markdown, no YAML frontmatter. `[[wikilinks]]` to other sessions/decisions/specs are welcome
(Obsidian graph). Sections: `## Context`, `## What Changed`, `## Why`, `## Decisions`,
`## Open Questions`. Decision records (`decisions/<slug>.md`) use `## Context`, `## Decision`,
`## Rationale`, `## Consequences`, plus **Status** (accepted | superseded | deferred) and **Date**.

### Operational logging

Append a one-line entry to `~/Code/construct-docs/log.md` after creating/updating a note.
Format: `[YYYY-MM-DD HH:MM] note | <topic>`
