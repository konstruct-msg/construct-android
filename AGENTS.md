# AGENTS.md — construct-android

Context for AI agents working in this repository.

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
│   ├── viewmodel/      — Hilt-injected ViewModels (@HiltViewModel)
│   ├── ui/             — Compose screens and components
│   ├── data/           — Repository layer, Room DB, DataStore
│   ├── service/        — Background services (messaging, push)
│   └── di/             — Hilt modules
└── jniLibs/            — .so files from construct-core Rust build
    ├── arm64-v8a/
    ├── armeabi-v7a/
    └── x86_64/
```

### Rust core integration

```bash
# Build construct-core for Android targets (run from construct-core/)
cargo build --release --target aarch64-linux-android
cargo build --release --target armv7-linux-androideabi
cargo build --release --target x86_64-linux-android

# Generate UniFFI Kotlin bindings
uniffi-bindgen generate   --library target/aarch64-linux-android/release/libconstruct_core.so   --language kotlin   --out-dir bindings/kotlin
```

Copy resulting `.so` files to `app/src/main/jniLibs/<abi>/`.

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
- Compose UI only — no XML layouts
- Room DB for local message persistence
- gRPC channel lives in a singleton service (not recreated per-screen)

---

## Design System (read before touching any UI)

The iOS app is the **design canon**. Android mirrors it — every CT* component carries a
`**Canon:** iOS ConstructTheme.swift → …` reference. Full token tables, the SF Symbol →
Material Icon map, and the `CTStatus`/`CTStatusBadge` pattern live in
`docs/ANDROID_ONBOARDING.md` §3 (kept in sync with `~/Code/construct-docs/client/ANDROID_ONBOARDING.md`).
Read §3 before changing any UI.

### Design philosophy: CT + Material fusion

Terminal/cyberpunk CT aesthetic fused with Android/Material conventions so users intuitively
understand how to interact. **Keep**: JetBrains Mono (mono `FontFamily`), `#090909` background,
CT palette, information density, *decorative* terminal chrome (`-`/`=` separators, `>` prefix, `✷`,
hex avatars). **Never** sacrifice usability or clash with Material guidelines.

> **Terminal glyphs are decorative-only — not functional (revised 2026-06-22).** Testers and
> users did not embrace the `[…]` bracket pastiche on functional controls. **State and
> affordance must read instantly**, so `[ok] [err] [on] [off] [✓] [ ] [!] [~] [?]` and similar
> are replaced by **Material icons + semantic colour** (`CTStatus` / `CTStatusBadge`) or native
> controls (`Switch`, selection `Icons.Default.Check`). ASCII may remain only as unobtrusive
> *chrome* (separators, the `>` prefix on system messages / section headers, decorative `✷`).
> This mirrors the iOS doctrine in `construct-messenger/AGENTS.md`.

### Rules

- **Material Icons** (`androidx.compose.material.icons`, `ImageVector`) for **all interactive
  controls** — back/close, action buttons, tab bar, send, attach, mic, search. (Direct analogue
  of iOS SF Symbols.)
- **`CTSymbol.*` / ASCII** for **decorative chrome only** — `> SECTION` headers, `-`/`=`
  separators, the `>` system-message prefix. Never ASCII for state/controls.
- **Status**: `CTStatusBadge(status:)` with the `CTStatus` enum (`ok error warning on off busy
  unknown`) — never a `"[ok]"` / `"[err]"` text token. (Compose impl in `ANDROID_ONBOARDING.md`
  §3.3; not yet in code — add when the first status row appears.)
- **Selection** → `Icons.Default.Check` in `accent`; **on/off** → Material3 `Switch`.
- Tokens: `CTColor.*`, `ctRegular(size)` / `ctBold(size)` (JetBrains Mono), `CornerRadius.*`,
  `Spacing.*`, `CTLayout.*`. No inline magic numbers.

### Current state & migration

`CTTabBar` and `CTSettingsRow` **already** use Material icons (ahead of iOS). But `CTSymbol.kt`
still lists dead action/status glyphs — prune them to decorative-only when next touched
(only `CTNavBar`/`CTSep`/`MainScreen`/`OnboardingScreen` consume `CTSymbol`). Bottom nav: iOS
moved to native `TabView`; prefer Material3 `NavigationBar` (icon-only) over the hand-rolled
`CTTabBar` when refactoring. Pending glyph phases match iOS: `[→]` → `ChevronRight`,
`[ BUTTON ]` → `CTButton`, ASCII row icons → Material icons, contact-request action glyphs.

---

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
The Android design doc lives at
`~/Code/construct-docs/client/ANDROID_ONBOARDING.md` (kept in sync with this repo's
`docs/ANDROID_ONBOARDING.md`).

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

