# SettingsView (#21) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace stub `SettingsScreen` with §5.5 settings hub on hardcoded fake data (no ViewModel).

**Architecture:** Pure Compose UI. Fake profile constants in `SettingsScreen.kt`. Existing CT components compose the sections. Row taps are no-op callbacks.

**Tech Stack:** Kotlin, Jetpack Compose, Material icons, existing CT design-system components.

## Global Constraints

- No ViewModel / repository / Hilt changes (#23 deferred).
- No hardcoded user-facing strings — `settings_*` keys in en + ru.
- Material icons only for interactive affordances (AGENTS.md).
- Keep file name `SettingsScreen.kt`.
- Do not commit unless the user asks.

---

## File map

| File | Role |
|---|---|
| `app/src/main/res/values/strings.xml` | EN strings |
| `app/src/main/res/values-ru/strings.xml` | RU strings |
| `app/src/main/java/.../ui/screens/settings/SettingsScreen.kt` | Full screen UI |
| `docs/superpowers/specs/2026-07-16-settings-view-design.md` | Already written |

No new files beyond expanding `SettingsScreen.kt`.

---

### Task 1: Localization keys

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`

**Interfaces:**
- Produces: string resources listed below (consumed by Task 2)

- [ ] **Step 1: Add EN keys** under `<!-- Settings -->` in `values/strings.xml`:

```xml
<string name="settings_title">Settings</string>
<string name="settings_section_profile">Profile</string>
<string name="settings_section_share">Share</string>
<string name="settings_section_settings">Settings</string>
<string name="settings_section_about">About</string>
<string name="settings_section_developer">Developer</string>
<string name="settings_row_share_invite">Share invite link</string>
<string name="settings_row_appearance">Appearance</string>
<string name="settings_row_network">Network</string>
<string name="settings_row_security">Security</string>
<string name="settings_row_version">Version</string>
<string name="settings_row_licenses">Licenses</string>
<string name="settings_row_diagnostics">Diagnostics</string>
<string name="settings_recovery_banner">Social recovery is not set up. Tap to configure.</string>
<string name="settings_discoverable_on">Discoverable</string>
<string name="settings_username_format">@%1$s</string>
```

- [ ] **Step 2: Add matching RU keys** in `values-ru/strings.xml`:

```xml
<string name="settings_title">Настройки</string>
<string name="settings_section_profile">Профиль</string>
<string name="settings_section_share">Поделиться</string>
<string name="settings_section_settings">Настройки</string>
<string name="settings_section_about">О приложении</string>
<string name="settings_section_developer">Разработка</string>
<string name="settings_row_share_invite">Ссылка-приглашение</string>
<string name="settings_row_appearance">Оформление</string>
<string name="settings_row_network">Сеть</string>
<string name="settings_row_security">Безопасность</string>
<string name="settings_row_version">Версия</string>
<string name="settings_row_licenses">Лицензии</string>
<string name="settings_row_diagnostics">Диагностика</string>
<string name="settings_recovery_banner">Социальное восстановление не настроено. Нажмите, чтобы настроить.</string>
<string name="settings_discoverable_on">Обнаруживаемый</string>
<string name="settings_username_format">@%1$s</string>
```

- [ ] **Step 3: Verify** — keys exist in both files; no duplicate `settings_title`.

---

### Task 2: Implement SettingsScreen

**Files:**
- Modify: `app/src/main/java/com/construct/messenger/ui/screens/settings/SettingsScreen.kt` (replace stub)

**Interfaces:**
- Consumes: string keys from Task 1; `CTNavBar`, `CTSettingsSectionHeader`, `CTSectionGroup`, `CTSettingsRow`, `CTSep`, `CTAvatar`, `CTColor`, `ctRegular`/`ctBold`
- Produces:

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

- [ ] **Step 1: Replace file** with full implementation:

Fake profile at file top:

```kotlin
private data class FakeSettingsProfile(
    val userId: String = "14f28d31-aaaa-bbbb-cccc-000000000099",
    val displayName: String = "Silent Fox",
    val username: String = "silent_fox",
    val discoverable: Boolean = true,
    val recoveryConfigured: Boolean = false,
    val appVersion: String = "0.1.0-dev",
)

private val FakeProfile = FakeSettingsProfile()
```

Screen body:
- `Column` + `CTNavBar` (unchanged back behavior)
- `Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp))`
- If `!FakeProfile.recoveryConfigured` → `RecoveryBanner()`
- Five sections per spec with `CTSettingsSectionHeader` + `CTSectionGroup`
- Share / Settings / About / Developer rows via `CTSettingsRow` + `CTSep` between siblings
- Version row: `value = FakeProfile.appVersion`, no click
- Other rows: `Modifier.clickable(onClick = …)`
- Icons: `Icons.Default.Share`, `Palette`, `Wifi`, `Lock`, `Description`, `BugReport` (Version: `Info` or none)

Private `SettingsProfileRow(profile, onClick)`:
- `Row(clickable, padding 12/9, CenterVertically)`
- `CTAvatar(userId, displayName, size = 44.dp)`
- Column weight 1: displayName `ctBold(13)`/`ctRegular(13)` text; username via `stringResource(R.string.settings_username_format, username)` in `textDim`; if discoverable, `settings_discoverable_on` in `accentDim` `ctRegular(11)`
- `Icon(Icons.Default.ChevronRight, tint = textDim, size 16.dp)`

Private `RecoveryBanner()`:
- `Modifier.padding(horizontal = 12.dp).padding(top = 16.dp).clip(RoundedCornerShape(CornerRadius.small)).background(CTColor.danger.copy(alpha = 0.12f)).border(HairlineBorder, CTColor.danger.copy(alpha = 0.4f), shape).padding(12.dp)`
- `Text(stringResource(R.string.settings_recovery_banner), style = ctRegular(13), color = CTColor.danger)`

`@Preview(backgroundColor = 0xFF090909, showBackground = true, heightDp = 800)` calling `SettingsScreen()`.

- [ ] **Step 2: Smoke-check** — open Preview or assemble; confirm five sections + recovery banner + profile avatar.

- [ ] **Step 3: graphify update**

Run: `graphify update .`
Expected: graph refreshes without error.

---

## Spec coverage self-review

| Spec item | Task |
|---|---|
| Expand SettingsScreen, keep name | 2 |
| Recovery banner when !recoveryConfigured | 2 |
| Five sections + CT components | 2 |
| Profile row with CTAvatar | 2 |
| Stub callbacks | 2 |
| Fake profile constants | 2 |
| en + ru strings | 1 |
| Preview | 2 |
| No VM/repo | Global |
| MainTabView unchanged | (no change needed) |
