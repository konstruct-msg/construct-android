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
---

## Shared Construct Docs Workflow

These instructions apply to GitHub Copilot, Codex, OpenCode, and similar coding agents.

### Division of labour — read this first

| Role | Tool | Responsibility |
|------|------|----------------|
| **Coding agent** (you) | Copilot / Codex | Write code + drop raw session notes into `wiki/sessions/` and `wiki/decisions/`. That is all. |
| **Wiki pipeline** | `obsidian-llm-wiki-local` (olw) | Reads `raw/`, synthesizes concepts, creates/updates wiki articles, generates cross-links. |
| **Developer** | Human + Obsidian | Reviews wiki draft articles, approves/rejects. Curates `raw/`. |

**Your job is code.** olw handles article synthesis. Write plain-markdown session notes; let the pipeline do the rest.

### Shared knowledge base

- Vault: `/Users/maximeliseyev/Code/constrcut-docs`
- `raw/` — source corpus. Do **not** rewrite or reorganize.
- `wiki/` — canonical curated knowledge base. **Read** from here before architectural work.
- `wiki/.drafts/` — **reserved for olw**. Never write here manually.
- `wiki/sessions/` — where coding agents write session notes.
- `wiki/decisions/` — where coding agents write long-lived decision records.

### Where to save durable reasoning

After any session involving architectural changes, design decisions, API changes, or non-obvious implementation choices:

1. **Always** create or update `wiki/sessions/YYYY-MM-DD-<topic>.md`.
2. **Always** fill in `# Why` — reasoning, alternatives considered, why rejected. Most important section.
3. If the decision constrains future work, also create `wiki/decisions/<topic>.md`.
4. Session notes: plain markdown, **no YAML frontmatter, no `[[wikilinks]]`** — olw adds those.

Required note sections: `# Context`, `# What Changed`, `# Why`, `# Intended Outcome`, `# Decisions`, `# Open Questions`

### Operational logging

Append a one-line entry to `wiki/log.md` after writing a note.
Format: `[YYYY-MM-DD HH:MM] note | <topic>`

