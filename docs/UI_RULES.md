# UI rules — construct-android

The detail behind the one UI line in `AGENTS.md` (→ Design System). Read before touching UI.

## Material 3 (decided 2026-10-08)

Vault `decisions/android-is-material-3.md`. Until then iOS was the design canon and Android
mirrored it; for the look that is over. iOS goes its own way in SwiftUI, Android in Material, and
the two apps may differ. Features, texts, behaviour and the wire are still one meaning across both.

- **Source of truth: the designer's Figma file**, a local copy of the Material 3 kit (theme
  "Echo"), not iOS `ConstructTheme.swift`. New code carries no `**Canon:** iOS …` reference. Until
  the file is wired in, the CT tokens stay as they are.
- **Tokens are Material's roles.** Colour is `ColorScheme`, one scheme per theme mode of the file.
  Set **every** role: an unset one falls back to Compose's baseline purple. Type is `Typography`
  from the font roles Brand / Plain. Values come from the Figma variables by a repeatable step,
  never retyped.
- **Controls are Material 3 components** themed by `MaterialTheme`: `Button`, `Switch`,
  `NavigationBar`, `TopAppBar`, dialogs, sheets, menus. Never hand-draw a control or build a new
  `CT*` look-alike of one.
- **What stays ours:** JetBrains Mono for the chrome, and dark as the default. The rest of the
  identity (density, hairlines, identicon avatars, the `>` system-message prefix, terminal
  diagnostics) is not removed without asking the owner.
- **Terminal glyphs are decorative only** (since 2026-06-22). Never `[ok] [err] [✓] [→]` for
  state or controls: use Material icons with a semantic colour, `Switch`, `Icons.Default.Check`.
  ASCII stays only as chrome, the `>` prefix. A separator is `HorizontalDivider`.
- **Message text is the reader's.** `CTFont.message(CTFont.MESSAGE_SIZE)` for a bubble, the
  composer, and a caption under a photo, a video or a file. Everything else is chrome.

## Migration (background work, one screen per commit)

Full plan and order: `docs/MATERIAL3_MIGRATION.md`. Code moves first with no visible change,
proved by the screenshot tests (`PreviewScreenshotTest`, references in `app/src/test/screenshots/`).
The new look arrives later as one change of theme values.

1. **Theme:** the file's roles go into `ColorScheme` / `Typography` in `ui/theme/`.
2. **Aliases:** `CTColor.*` / `CTFont.*` point at the roles, so the files reading them change
   colour without changing code.
3. **Components:** remaining CT components give way to their Material counterparts screen by
   screen; an alias goes with its last caller. A screen built from now on is Material from the
   start.

## Tokens

`docs/ANDROID_ONBOARDING.md` §3 maps them:
- **colour:** `MaterialTheme.colorScheme`. The unsuffixed `CTColor.*` aliases read the roles;
  bubbles, status and media keep their own tokens;
- **type:** `Typography` inside Material components, a `CTFont` role outside them;
- **shapes:** the theme's;
- **spacing and sizes:** `CTSpace.*`, `CTIcon.*`, `CTLayout.*`, `CornerRadius.*`.

**No inline magic numbers.** `scripts/check_ui_tokens.sh` (run by `verify.sh`) fails when a count
of hand-set sizes, faces or colours rises, or when a deleted CT control comes back. A change that
lowers a count lowers its baseline in the same commit.

`./gradlew recordRoborazziDebug` rewrites the screenshot references. Look at every changed image
before committing it.
