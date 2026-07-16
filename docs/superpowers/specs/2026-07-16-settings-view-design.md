# SettingsView (#21) — Design

**Issue:** [konstruct-msg/construct-android#21](https://github.com/konstruct-msg/construct-android/issues/21)  
**Canon:** `docs/ANDROID_ONBOARDING.md` §5.5  
**Date:** 2026-07-16  
**Branch:** `feature/settings-view-21`

## Goal

Replace the stub `SettingsScreen` with a scrollable settings hub that matches iOS SettingsView structure on fake data. Sub-screen navigation is stubbed (no-op). No ViewModel / repository (deferred to #23).

## Non-goals

- `AccountSettings` / `AppearanceSettings` / `NetworkSettings` / `Security` screens
- Hilt `SettingsViewModel` or `UserRepository` (#23)
- Real recovery / discoverability / share actions
- Theme switching (#25)

## Approach

**Hardcoded fake profile in the screen file** (chosen over VM or full fake repo).

Keep the existing file name `SettingsScreen.kt` (Android naming). Expand it in place; do not add `SettingsView.kt`.

## UI structure

```
Column(bg)
  CTNavBar(title = settings_title)
  verticalScroll
    RecoveryBanner          // if !recoveryConfigured
    > PROFILE
      CTSectionGroup { ProfileRow }
    > SHARE
      CTSectionGroup { rows + CTSep between }
    > SETTINGS
      CTSectionGroup { rows + CTSep between }
    > ABOUT
      CTSectionGroup { rows + CTSep between }
    > DEVELOPER
      CTSectionGroup { rows + CTSep between }
```

Section headers use existing `CTSettingsSectionHeader`. Cards use `CTSectionGroup`. Rows use `CTSettingsRow` + `CTSep(THIN)` between siblings inside a group.

### Profile row

Private composable in `SettingsScreen.kt` (not a new shared component):

- Leading `CTAvatar(userId, displayName, size ≈ 44.dp)`
- Column: display name (`ctRegular`/`ctBold` ~13–14), username (`@…`, `textDim`), optional discoverable hint
- Trailing Material `Icons.Default.ChevronRight` in `textDim`
- Whole row `clickable` → no-op (optional `onProfileClick: () -> Unit = {}`)

### Recovery banner

Private stub composable: danger-tinted card (reuse `CTSectionGroup` styling or a thin `background(danger.copy(alpha))` + `CTColor.danger` text) with a short warning that recovery is not set up. Shown only when `recoveryConfigured == false`. Tap → no-op.

### Sections and rows (labels via stringResource)

| Section | Rows |
|---|---|
| Profile | Profile row only |
| Share | Share invite link |
| Settings | Appearance, Network, Security |
| About | Version (shows fake `appVersion` as value), Licenses |
| Developer | Diagnostics |

Row icons: Material `ImageVector` only (per AGENTS.md). No ASCII action glyphs. Suggested icons: `Share`, `Palette`, `Wifi`, `Lock`, `Info`, `Description`, `BugReport` — swap only if a closer Material match already used elsewhere in the app.

Each row: `CTSettingsRow(..., modifier = Modifier.clickable { … })` with default empty lambdas.

## Fake data

Private constants (or a private `data class FakeSettingsProfile`) at the top of `SettingsScreen.kt`:

| Field | Example |
|---|---|
| `userId` | `"14f28d31-aaaa-bbbb-cccc-000000000099"` |
| `displayName` | `"Silent Fox"` |
| `username` | `"silent_fox"` |
| `discoverable` | `true` |
| `recoveryConfigured` | `false` |
| `appVersion` | `"0.1.0-dev"` |

No DI. Preview uses the same constants.

## Localization

Add keys to `values/strings.xml` and `values-ru/strings.xml` for every visible string on this screen (section titles, row labels, recovery banner, discoverable hint). No hardcoded user-facing literals in Compose.

Use `settings_*` prefix for all new keys (e.g. `settings_section_profile`, `settings_row_appearance`, `settings_recovery_banner`). Do not block on iOS key parity; #24 can rename later if needed.

## API surface

```kotlin
@Composable
fun SettingsScreen(
    onNavigateBack: (() -> Unit)? = null,
    onProfileClick: () -> Unit = {},
    onShareInviteClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onNetworkClick: () -> Unit = {},
    onSecurityClick: () -> Unit = {},
    onLicensesClick: () -> Unit = {},
    onDiagnosticsClick: () -> Unit = {},
)
```

Version row is display-only (no click). Recovery banner click is a no-op (no callback param).

`MainTabView` keeps calling `SettingsScreen()` with no callbacks — behavior unchanged for the tab host.

## Preview

`@Preview` on dark `#090909` background showing recovery banner + filled profile (default fake data).

## Verification

- Manual / Preview: all five sections render; recovery banner visible; profile shows avatar + name.
- No new unit test unless non-trivial logic appears (none expected under approach A).
- After code changes: `graphify update .`

## Acceptance (from #21)

- [ ] Sections render (Profile / Share / Settings / About / Developer)
- [ ] Sub-screen navigation stubbed
- [ ] Fake profile drives Profile row + recovery banner
