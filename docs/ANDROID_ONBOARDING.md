# Construct Messenger — Android Implementation Guide

> **Цель**: предоставить Android-разработчику полное понимание архитектуры, дизайн-системы, UI-компонентов, бизнес-логики и крипто-протокола для реализации на Kotlin / Jetpack Compose.
>
> **Статус реализации (2026-09-21, `construct-android` `develop`).**
> Это канон дизайна (iOS → Android), не трекер фаз. Фазы протокола —
> `docs/IMPLEMENTATION_PLAN.md` в репозитории Android.
>
> **Уже в коде:** онбординг + Orientation; табы; список чатов (Room); чат
> (пузыри + инпут + send/observe); Synaps — mint v5 / paste / список контактов /
> FindUser / contact requests (не honeycomb); `konstruct://add`; session runtime /
> receive / send; CFE
> action executor; typed secure-store persistence; orchestrator/PQ snapshots;
> current `construct-core` Android artifact.
> **Ещё нет:** honeycomb Synaps, экраны Account / Appearance / Network / Security,
> VEIL / звонки / recovery, queued multi-carrier receive walk
> и живой iOS↔Android прогон. Account→device registry, device-only core boundary,
> per-device fan-out, SSR1 sender-sync, bundle candidate walk и CFE timer bridge
> уже подключены.
> **FCM не будет:** delivery — собственный persistent stream в foreground service,
> без требования Google Play Services.

---

## Содержание

1. [Обзор архитектуры](#1-обзор-архитектуры)
2. [Глоссарий терминов](#2-глоссарий-терминов)
3. [Дизайн-система (Design Tokens)](#3-дизайн-система-design-tokens)
   - 3.1 [Цветовая палитра](#31-цветовая-палитра)
   - 3.2 [Типографика](#32-типографика)
   - 3.3 [Символы / иконки](#33-символы--иконки)
   - 3.4 [Геометрия / скругления](#34-геометрия--скругления)
   - 3.5 [Сетка / Spacing](#35-сетка--spacing)
   - 3.6 [Тени](#36-тени)
   - 3.7 [Анимации](#37-анимации)
4. [UI Компоненты (Reusable)](#4-ui-компоненты-reusable)
   - 4.1 [CTNavBar — навигационная панель](#41-ctnavbar--навигационная-панель)
   - 4.2 [CTTabBar — таб-бар](#42-cttabbar--таб-бар)
   - 4.3 [CTButton — кнопка](#43-ctbutton--кнопка)
   - 4.4 [CTTextField — поле ввода](#44-cttextfield--поле-ввода)
   - 4.5 [CTSearchBar — поиск](#45-ctsearchbar--поиск)
   - 4.6 [CTSectionGroup — карточка секции](#46-ctsectiongroup--карточка-секции)
   - 4.7 [CTSettingsSectionHeader — заголовок секции](#47-ctsettingssectionheader--заголовок-секции)
   - 4.8 [CTSettingsRow — строка настроек](#48-ctsettingsrow--строка-настроек)
   - 4.9 [CTRowIcon — иконка строки](#49-ctrowicon--иконка-строки)
   - 4.10 [CTAvatar — аватар](#410-ctavatar--аватар)
   - 4.11 [CTHexAvatar / MainAvatarView — гексагональный аватар](#411-cthexavatar--mainavatarview--гексагональный-аватар)
   - 4.12 [CTSeparator — разделитель](#412-ctseparator--разделитель)
   - 4.13 [CTSystemMessage — системное сообщение (> text)](#413-ctsystemmessage--системное-сообщение--text)
   - 4.14 [CTNoise — ASCII шум / фон](#414-ctnoise--ascii-шум--фон)
   - 4.15 [CTModeSelector — сегментированный контроль](#415-ctmodeselector--сегментированный-контроль)
   - 4.16 [CTLogoView — логотип](#416-ctlogoview--логотип)
   - 4.17 [ConstructActionRow — action-строка](#417-constructactionrow--action-строка)
   - 4.18 [ConstructNavRow — строка навигации](#418-constructnavrow--строка-навигации)
   - 4.19 [ConstructButtonRow — строка-кнопка](#419-constructbuttonrow--строка-кнопка)
   - 4.20 [ConnectionStatusIndicator — индикатор соединения](#420-connectionstatusindicator--индикатор-соединения)
5. [Структура экранов](#5-структура-экранов)
   - 5.1 [Onboarding / Registration](#51-onboarding--registration)
   - 5.2 [MainTabView (корневой экран)](#52-maintabview-корневой-экран)
   - 5.3 [ChatsListView — список чатов](#53-chatslistview--список-чатов)
   - 5.4 [ChatView — экран чата](#54-chatview--экран-чата)
   - 5.5 [SettingsView — экран настроек](#55-settingsview--экран-настроек)
   - 5.6 [AccountSettingsView — профиль](#56-accountsettingsview--профиль)
   - 5.7 [AppearanceSettingsView — темы](#57-appearancesettingsview--темы)
   - 5.8 [NetworkSettingsView — сеть](#58-networksettingsview--сеть)
   - 5.9 [SecurityView — безопасность](#59-securityview--безопасность)
   - 5.10 [SynapsView — контакты (соты)](#510-synapsview--контакты-соты)
6. [Генератор анонимных имён](#6-генератор-анонимных-имён)
7. [Локализация (i18n)](#7-локализация-i18n)
8. [Архитектура данных](#8-архитектура-данных)
   - 8.1 [User Identity — два типа идентификаторов](#81-user-identity--два-типа-идентификаторов)
   - 8.2 [DisplayName Resolution](#82-displayname-resolution)
   - 8.3 [Core Data — модель данных](#83-core-data--модель-данных)
9. [Структура проекта (Android Reference)](#9-структура-проекта-android-reference)
10. [Crypto Core — Rust FFI](#10-crypto-core--rust-ffi)
11. [Session Lifecycle Controller](#11-session-lifecycle-controller)
12. [Session Initialization](#12-session-initialization)
    - 12.1 [INITIATOR Flow](#121-initiator-flow)
    - 12.2 [RESPONDER Flow](#122-responder-flow)
    - 12.3 [Tie-Break (Simultaneous Init)](#123-tie-break-simultaneous-init)
13. [Auth Tokens — PASETO v4.public](#13-auth-tokens--paseto-v4public)

---

## 1. Обзор архитектуры

Construct Messenger — privacy-first E2EE-мессенджер с терминальной / ASCII-эстетикой.

**Ключевые концепции:**
- **Тёмная тема** как основная (`#090909` фон), светлая — как альтернатива
- Все строки — через `NSLocalizedString` (нет хардкода)
- **Material Icons** (`ImageVector`) для интерактивных контролов (назад, закрыть, отправить,
  таб-бар) — прямой аналог iOS SF Symbols
- **ASCII / `CTSymbol.*`** — только декоративный хром (`>` префикс, `-`/`=` разделители, `✷`).
  Глифы состояния `[ok] [err] [✓] …` **не используются** — см. §3.3
- **JetBrains Mono** — моноширинный шрифт для всего интерфейса
- **Hexagon-аватары** (круглая форма с гексагональным акцентом цвета)

---

## 2. Глоссарий терминов

| ❌ Избегать | ✅ Использовать |
|---|---|
| Account | Identity |
| Login / Sign in | Session |
| Register | Initialize |
| Device | Replica |
| Contact | Node |
| Profile | Identity |
| Server | Construct |
| Group | Cluster |
| Message thread | Stream |
| ICE (obfuscation) | VEIL |

---

## 3. Дизайн-система (Design Tokens)

### 3.1 Цветовая палитра

```kotlin
// ConstructTheme.kt
object CT {
    // Фоны
    val bg        = Color(0xFF090909)  // основной фон (dark)
    val bgLight   = Color(0xFFF2F2F2)  // основной фон (light)
    val bgMsg     = Color(0xFF202020)  // bubble incoming (dark)
    val bgMsgLight = Color(0xFFE2E2E2) // bubble incoming (light)
    val outMsgBg   = Color(0xFF111111) // bubble outgoing (dark)
    val outMsgBgLight = Color(0xFFE9E9E9) // bubble outgoing (light)

    // Акцентный синий
    val accent     = Color(0xFF0062FF) // Primary accent
    val accentDim  = Color(0xFF1E68DF) // Secondary accent

    // Текст
    val text       = Color(0xFFE8E8E8) // primary text (dark)
    val textLight  = Color(0xFF111111) // primary text (light)
    val textDim    = Color(0xFF818181) // dim text (dark)
    val textDimLight = Color(0xFF333333) // dim text (light)

    // Структура
    val noise      = Color(0xFF1E1E1E) // разделители, ASCII шум (dark)
    val noiseLight = Color(0xFFC8C8C8) // разделители, ASCII шум (light)

    // Опасность
    val danger     = Color(0xFFDC3C3C)
}
```

**Dark/Light**: цвета должны адаптироваться к системной теме.

### 3.2 Типографика

```kotlin
// CTTypography.kt
object CTFont {
    // JetBrains Mono — моноширинный шрифт для всего UI
    fun regular(size: Int) = FontFamily("JetBrains Mono", weight = FontWeight.Normal, size = size)
    fun medium(size: Int)  = FontFamily("JetBrains Mono", weight = FontWeight.Medium, size = size)
    fun bold(size: Int)    = FontFamily("JetBrains Mono", weight = FontWeight.Bold, size = size)
}
```

**Распространённые размеры:**
- `CTFont.bold(14)` — заголовки в `CTNavBar` + `tracking(4)`
- `CTFont.regular(13)` — текст в строках настроек, сообщения
- `CTFont.bold(12)` — > SECTION заголовки
- `CTFont.regular(11)` — таймстемпы, метаданные
- `CTFont.bold(16)` — заголовки в `ConstructActionRow`

В Kotlin/Compose для `tracking(4)` использовать `letterSpacing(4.sp)`.

### 3.3 Символы / иконки

> **Терминальные глифы — только декорация, не функциональные элементы (ревизия 2026-06-22).**
> Тестировщики и пользователи не приняли скобочную стилистику `[…]` на функциональных
> контролах. **Состояние и аффорданс должны читаться мгновенно**, поэтому
> `[ok] [err] [on] [off] [✓] [ ] [!] [~] [?]` и подобные заменяются на **Material-иконку +
> семантический цвет** (`CTStatus` / `CTStatusBadge`) либо на нативный контрол
> (`Switch`; галочка `Icons.Default.Check` для выбора). ASCII остаётся только как
> ненавязчивый *хром*: разделители, префикс `>` у системных сообщений и заголовков
> секций, декоративная `✷`.
>
> Это зеркалит обновлённую доктрину iOS (`construct-messenger/AGENTS.md`, раздел *Design
> System*). iOS-приложение — канон дизайна; Android повторяет за ним.

**Правило**:
- **Material Icons** (`androidx.compose.material.icons`, `ImageVector`) — для **всех
  интерактивных контролов**: назад/закрыть, кнопки действий, таб-бар, отправка, вложение,
  микрофон, поиск. Прямой аналог iOS SF Symbols.
- **`CTSymbol.*` / ASCII** — только **декоративный хром**: заголовки секций (`> TITLE`),
  разделители `-`/`=`, префикс `>` у системных сообщений.
- **Никогда** ASCII для **состояния или контролов**: статус → `CTStatusBadge`; выбор →
  `Icons.Default.Check`; вкл/выкл → `Switch`.
- Граница решения: *передаёт состояние или это тап-действие?* → Material-иконка / нативный
  контрол. *Чисто декоративный терминальный хром?* → ASCII.

```kotlin
// CTSymbol.kt — ТОЛЬКО декоративный хром
object CTSymbol {
    const val star8 = "✷"
    // Разделители (CTSep)
    fun thin(count: Int = 25)  = "- ".repeat(count)
    fun thick(count: Int = 25) = "= ".repeat(count)
}
// УДАЛЕНО из доктрины:
//   back/forward/add/close/send/media/edit/retry/upload → Material Icons (интерактив)
//   ok/delivered/error/online                           → CTStatus / CTStatusBadge
//   tabChats/tabSynaps/tabCalls/tabSettings             → таб-бар рисует Material-иконки
```

#### Статусы — `CTStatus` / `CTStatusBadge`

Канон: iOS `ConstructTheme.swift` → `enum CTStatus` + `struct CTStatusBadge`. Никогда не
рендерить статус текстовым токеном `"[ok]"` / `"[err]"`. Compose-зеркало:

```kotlin
enum class CTStatus {
    OK, ERROR, WARNING, ON, OFF, BUSY, UNKNOWN;

    val icon: ImageVector get() = when (this) {
        OK, ON  -> Icons.Filled.CheckCircle
        ERROR   -> Icons.Filled.Error
        WARNING -> Icons.Filled.Warning
        OFF     -> Icons.Outlined.Circle
        BUSY    -> Icons.Filled.Sync
        UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
    }
    val color: Color get() = when (this) {
        OK                 -> CTColor.accent
        ON                 -> CTColor.accentDim
        ERROR              -> CTColor.danger
        WARNING            -> Color(0xFFFF9500)        // orange
        OFF, BUSY, UNKNOWN -> CTColor.textDim
    }
}

@Composable
fun CTStatusBadge(status: CTStatus, size: Dp = 14.dp) {
    Icon(
        imageVector = status.icon,
        contentDescription = null,
        tint = status.color,
        modifier = Modifier.size(size),
    )
}
```

`CTSettingsRow` получает опциональный слот `status: CTStatus? = null` (как iOS
`CTSettingsRow(status:)`) и рендерит `CTStatusBadge` вместо текстового значения. Выбор в
списках — `Icons.Default.Check` в `accent`; переключатели — Material3 `Switch`.

> **Текущее состояние Android-кода**: `CTTabBar` и `CTSettingsRow` **уже** используют
> Material-иконки (опережая iOS). Но `CTSymbol.kt` всё ещё содержит мёртвые глифы
> действий/статуса — их следует выпилить при следующем касании файла (`CTNavBar`/`CTSep`/
> `MainScreen`/`OnboardingScreen` — единственные потребители). `CTStatus`/`CTStatusBadge`
> ещё не существует в коде — добавить при первой строке со статусом, не текстовый токен.

#### Фазы миграции (как на iOS — не регрессировать ранние фазы)

- **Фаза 1 (на iOS готово)**: статус-значения + галочки выбора → `CTStatusBadge` / `Check`.
- **Ожидает**: `[→]` аффорданс строки → `chevron` (`Icons.Default.ChevronRight`);
  `[ BUTTON ]` подписи → настоящие `CTButton`; ASCII row-иконки → Material Icons; глифы
  действий в запросах контактов; позже — пересмотр `> SECTION` заголовков.
- **Таб-бар**: iOS перешёл с кастомного бара на нативный `TabView`. Android-аналог канона —
  Material3 `NavigationBar` (icon-only); предпочитать его кастомному `CTTabBar` при рефакторинге.

**SF Symbols аналоги для Android (Material Icons / Custom):**

| iOS SF Symbol | Android Vector / Icon |
|---|---|
| `chevron.backward.circle.fill` | `Icons.Default.ArrowBack` |
| `xmark.circle` | `Icons.Default.Close` |
| `magnifyingglass` | `Icons.Default.Search` |
| `qrcode.viewfinder` | `Icons.Default.QrCodeScanner` |
| `message` / `message.fill` | `Icons.Default.Chat` |
| `gearshape` | `Icons.Default.Settings` |
| `phone` / `phone.fill` | `Icons.Default.Phone` |
| `circle.grid.cross` | `Icons.Default.Groups` |
| `checkmark.circle.fill` | `Icons.Default.CheckCircle` |
| `trash` | `Icons.Default.Delete` |
| `chevron.right` | `Icons.Default.ChevronRight` |
| `link` | `Icons.Default.Link` |
| `lock` | `Icons.Default.Lock` |
| `paintbrush` | `Icons.Default.Palette` |
| `bell` | `Icons.Default.Notifications` |
| `globe` | `Icons.Default.Language` |
| `info.circle` | `Icons.Default.Info` |
| `square.and.pencil` | `Icons.Default.Edit` |
| `xmark.circle.fill` (clear search) | `Icons.Default.Clear` |
| `exclamationmark.circle.fill` | `Icons.Default.Warning` |
| `chevron.down.circle.fill` | `Icons.Default.ExpandMore` |
| `qrcode` | `Icons.Default.QrCode` |
| `externaldrive` | `Icons.Default.Storage` |
| `arrow.clockwise.circle` | `Icons.Default.Refresh` |
| `laptopcomputer` | `Icons.Outlined.Computer` |
| `circle.lefthalf.filled` | `Icons.Default.DarkMode` |
| `sun.max.fill` | `Icons.Default.LightMode` |
| `moon.fill` | `Icons.Default.DarkMode` |

### 3.4 Геометрия / скругления

```kotlin
// CTGeometry.kt
object CornerRadius {
    val small       = 8.dp   // карточки, баги, кнопки
    val medium      = 12.dp
    val large       = 16.dp  // message bubbles
    val extraLarge  = 20.dp
}

// MainTabView: Rectangle() для nav bar, разделителей
// MessageBubble: RoundedRectangle(cornerRadius = 10)
// Ввод текста: RoundedRectangle(cornerRadius = 10)
// Бейджи: RoundedRectangle(cornerRadius = 6)
// SettingsSectionGroup: RoundedRectangle(cornerRadius = 8)
```

### 3.5 Сетка / Spacing

```kotlin
// Spacing.kt
object Spacing {
    val compact    = 4.dp
    val small      = 8.dp
    val standard   = 12.dp
    val medium     = 16.dp
    val large      = 24.dp
    val extraLarge = 32.dp
}

// CTLayout (для nav bar и таб бара)
object CTLayout {
    val edgePad      = 12.dp    // горизонтальный padding
    val navVPad      = 11.dp    // вертикальный padding nav bar
    val navBarHeight = 44.dp    // фикс. высота nav bar
    val navIconSize  = 20.dp    // размер Material-иконки для кнопок в nav bar
}
```

### 3.6 Тени

```kotlin
// Shadows.kt
object ShadowStyle {
    val card = Shadow(
        color = Color.Black.copy(alpha = 0.1f),
        radius = 4.dp,
        x = 0.dp,
        y = 2.dp
    )
    val inputBar = Shadow(
        color = Color.Black.copy(alpha = 0.1f),
        radius = 2.dp,
        x = 0.dp,
        y = 1.dp
    )
}
```

### 3.7 Анимации

```kotlin
object AnimationDuration {
    val veryQuick = 100.ms
    val quick     = 200.ms
    val standard  = 250.ms
    val medium    = 300.ms
    val slow      = 500.ms
}
```

---

## 4. UI Компоненты (Reusable)

### 4.1 CTNavBar — навигационная панель

```kotlin
// CTNavBar.kt
@Composable
fun CTNavBar(
    title: String,
    showBack: Boolean = false,
    isModal: Boolean = false, // macOS: true = xmark вместо chevron
    trailingIcon: ImageVector? = null,
    trailingText: String? = null, // ASCII-символ вместо SF Symbol
    onBack: () -> Unit = {},
    onTrailingAction: () -> Unit = {},
    modifier: Modifier = Modifier
)
```

**iOS Reference** (`ConstructTheme.swift`):
- Title: `.uppercased()` + `CTFont.bold(14)` + `.tracking(4)`
- Back: SF Symbol `chevron.backward.circle.fill` / `xmark.circle` (macOS)
- Trailing: SF Symbol или CTSymbol
- Frame: `frame(height: CTLayout.navBarHeight)` + `.padding(.horizontal, CTLayout.edgePad)`
- Bottom border: 0.5pt line in `Color.CT.noise`

```kotlin
// Пример использования:
CTNavBar(
    title = stringResource(R.string.settings),
    showBack = true,
    onBack = { navController.popBackStack() }
)
```

### 4.2 CTTabBar — таб-бар

```kotlin
// CTTabBar.kt
data class CTTabItem(
    val symbol: String,     // ASCII-символ (CTSymbol)
    val icon: ImageVector   // SF Symbol / Material Icon
)

@Composable
fun CTTabBar(
    selectedTab: Int,
    items: List<CTTabItem>,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
)
```

**iOS Reference** (`ConstructTheme.swift`):
- 3-4 таба: Chats / Synaps / Calls (опционально) / Settings
- Иконки SF Symbol, selected `.fill` variant
- Accent цвет для активного таба, `textDim` для неактивных
- Верхняя граница: 0.5pt `Color.CT.noise`

```kotlin
// Экраны в ZStack с opacity/allowsHitTesting — аналогично Compose:
// Box с AnimatedVisibility/alpha
// Tab content создаётся лениво (visitedTabs: Set<Int>)
```

### 4.3 CTButton — кнопка

```kotlin
// CTButton.kt
@Composable
fun CTButton(
    label: String,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
)
```

**iOS Reference** (`ConstructTheme.swift`):
- Full-width, `CTFont.bold(13)`
- Normal: `fg = Color.CT.bg`, `bg = Color.CT.accent`
- Destructive: `fg = white`, `bg = Color.CT.danger`
- Disabled: `fg = Color.CT.textDim`, `bg = Color(dark: 0x1C1C1C)`
- `cornerRadius = 8`

```kotlin
CTButton(
    label = stringResource(R.string.reg_continue),
    onClick = { onComplete() }
)
```

### 4.4 CTTextField — поле ввода

```kotlin
// CTTextField.kt
@Composable
fun CTTextField(
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    isSecure: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier
)
```

**iOS Reference** (`ConstructTheme.swift`):
- `CTFont.regular(14)`, `foregroundColor = Color.CT.text`
- `padding(.horizontal, 12).padding(.vertical, 11)`
- `background(Color.CT.bgMsg)`
- `clipShape(RoundedRectangle(cornerRadius: 8))`
- `overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.CT.noise, lineWidth: 0.5))`

### 4.5 CTSearchBar — строка поиска

```kotlin
// CTSearchBar.kt
@Composable
fun CTSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = stringResource(R.string.search_prompt),
    modifier: Modifier = Modifier
)
```

**iOS Reference** (`ConstructTheme.swift`):
- HStack: `Image(systemName: "magnifyingglass")` + `TextField` + clear button
- `CTFont.regular(13)`, `foregroundColor = Color.CT.text`
- `.padding(.horizontal, 12).padding(.vertical, 9)`
- `.background(Color.CT.bgMsg)`
- Bottom border: `.ctBorderBottom()` (0.5pt line)

### 4.6 CTSectionGroup — карточка секции

```kotlin
@Composable
fun CTSectionGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
)
```

**iOS Reference** (`ConstructTheme.swift`):
- `background(Color.CT.outMsgBg)`
- `clipShape(RoundedRectangle(cornerRadius: 8))`
- `overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.CT.noise, lineWidth: 0.5))`
- `.padding(.horizontal, 12)`

### 4.7 CTSettingsSectionHeader — заголовок секции

```kotlin
@Composable
fun CTSettingsSectionHeader(
    title: String,
    color: Color = Color.CT.accentDim
)
```

**iOS Reference** (`ConstructTheme.swift`):
- `> TITLE` — `CTFont.bold(11)` + accent color
- `.padding(.horizontal, 12).padding(.top, 16).padding(.bottom, 4)`

### 4.8 CTSettingsRow — строка настроек

```kotlin
@Composable
fun CTSettingsRow(
    label: String,
    value: String,
    icon: ImageVector? = null,
    labelColor: Color = Color.CT.text,
    valueColor: Color = Color.CT.text,
    isAction: Boolean = false,
    isDestructive: Boolean = false
)
```

**iOS Reference** (`ConstructTheme.swift`):
- HStack: [icon] + label + Spacer + value
- `CTFont.regular(13)` label / `CTFont.bold(13)` for action
- `.padding(.horizontal, 12).padding(.vertical, 9)`

### 4.9 CTRowIcon — иконка строки

```kotlin
@Composable
fun CTRowIcon(
    symbol: String,         // ASCII (CTSymbol)
    icon: ImageVector? = null,  // SF Symbol
    color: Color = Color.CT.textDim,
    size: Int = 14
)
```

**iOS Reference** (`ConstructTheme.swift`):
- Fixed-width column: `frame(minWidth: 36, alignment: .leading)`
- ASCII: `CTFont.bold(size)`, SF: `.system(size: size, weight: .medium)`

### 4.10 CTAvatar — аватар

```kotlin
@Composable
fun CTAvatar(
    userId: String,
    displayName: String = "",
    image: ImageBitmap? = null,
    size: Dp = 44.dp,
    isActive: Boolean = false,
    isOnline: Boolean = false
)
```

**iOS Reference** (`MainAvatarView.swift`):
- **Круглая форма**, не квадратная! (была гексагональной, теперь Circle)
- Цвет — детерминированный по userId: `hexagonAccent(userId)`
- Initials: 1-2 буквы, FontFamily.Monospace, FontWeight.Medium, `size * 0.33` (iOS: medium, не bold)
- Активный: accent `opacity(1.0)`, + glow outer ring
- Индикатор online: зелёная точка `size * 0.22`

```kotlin
// Детерминированный цвет из userId.
// ВАЖНО: точно как iOS Color.hexagonAccent(for:):
//  - djb2-хеш как UNSIGNED 32-bit (UInt) с переполнением — НЕ Long;
//  - по Unicode code points (codePointAt), как Swift unicodeScalars — не по Char;
//  - HSV/HSB (Color.hsv), НЕ HSL — это разные цветовые модели.
// Иначе цвета аватаров разойдутся с iOS.
fun hexagonAccent(userId: String): Color =
    Color.hsv(hexagonHue(userId).toFloat(), 0.60f, 0.55f)

/** Hue в градусах [0,360) — кросс-платформенное значение (iOS считает тот же hash % 360). */
fun hexagonHue(userId: String): Int {
    var hash = 5381u
    var i = 0
    while (i < userId.length) {
        val cp = userId.codePointAt(i)
        hash = (hash shl 5) + hash + cp.toUInt()
        i += Character.charCount(cp)
    }
    return (hash % 360u).toInt()
}
```

### 4.11 CTHexAvatar / MainAvatarView — гекса-аватар

```kotlin
// Аналогично CTAvatar, но с гексагональной формой (используется крайне редко)
enum class AvatarSize(val dp: Dp) {
    SMALL(32.dp), MEDIUM(40.dp), LARGE(56.dp), XLARGE(80.dp)
}
```

### 4.12 CTSeparator — разделитель

```kotlin
@Composable
fun CTSep(
    style: CTSep.Style = CTSep.Style.THIN
) {
    // THIN: "- - - ..."
    // THICK: "= = = ..."
    // CTFont.regular(10), Color.CT.noise
    // frame(maxWidth = .infinity, alignment = .leading)
    // .padding(.horizontal, 12)
}
```

### 4.13 CTSystemMessage — системное сообщение (> text)

```kotlin
@Composable
fun CTSystemMessage(text: String) {
    Row {
        Text(">", font = CTFont.bold(12), color = Color.CT.accentDim)
        Spacer(6.dp)
        Text(text, font = CTFont.regular(12), color = Color.CT.accentDim)
    }
    // .padding(.horizontal, 12).padding(.vertical, 2)
}
```

### 4.14 CTNoise — ASCII шум / фон

```kotlin
@Composable
fun CTNoise(
    rows: Int = 40,
    cols: Int = 22,
    opacity: Float = 0.10f
)
```

Сетка случайных ASCII-символов (`@ % # + - = : . * / \ | ~ ^ < >`), отображается с низкой непрозрачностью как текстурный фон.

```kotlin
// Аналог iOS: .ctBackground() модификатор
@Composable
fun Modifier.ctBackground(): Modifier = this.then(
    Modifier.background(Color.CT.bg)
)
```

### 4.15 CTModeSelector — сегментированный контроль

```kotlin
@Composable
fun <T> CTModeSelector(
    selected: T,
    options: List<T>,
    labels: Map<T, String>,
    onSelection: (T) -> Unit,
    modifier: Modifier = Modifier
)
```

**Стиль**: без скруглений, accent на выбранном, `Rect` border.

### 4.16 CTLogoView — логотип

```kotlin
@Composable
fun CTLogoView(
    size: Dp = 100.dp,
    color: Color = Color.CT.text
)
```

### 4.17 ConstructActionRow — action-строка

```kotlin
@Composable
fun ConstructActionRow(
    icon: ImageVector,
    title: String,
    role: ConstructRowRole,
    badge: String? = null,
    isLoading: Boolean = false,
    onClick: () -> Unit
)

enum class ConstructRowRole {
    PRIMARY,    // electric-blue tint
    ACCENT,     // lighter blue
    SECONDARY,  // neutral
    DESTRUCTIVE,// red
    DISABLED    // dimmed + "soon" badge
}
```

### 4.18 ConstructNavRow — строка навигации

```kotlin
@Composable
fun ConstructNavRow(
    icon: ImageVector,
    title: String,
    iconColor: Color = Color.CT.accent,
    onClick: () -> Unit
)
```

### 4.19 ConstructButtonRow — строка-кнопка

```kotlin
@Composable
fun ConstructButtonRow(
    icon: ImageVector,
    title: String,
    iconColor: Color = Color.CT.accent,
    showChevron: Boolean = false,
    onClick: () -> Unit
)
```

### 4.20 ConnectionStatusIndicator — индикатор соединения

```kotlin
@Composable
fun ConnectionStatusIndicator() {
    // Маленькая точка или текст в CTNavBar чат-листа
    // connected = accent, disconnected = danger, connecting = textDim
}
```

---

## 5. Структура экранов

### 5.1 Onboarding / Registration

**OnboardingView**:
- Брендинг: CTLogoView + "KONSTRUCT" заголовок
- CTTextField для username (опционально)
- Inline validation: длина 3-20, только `[a-zA-Z0-9_]`
- Debounced (400ms) проверка доступности через API
- CTButton "CREATE IDENTITY [→]"
- Ссылки: `[restore →]`, `[link device →]`

**RegistrationFlowView / RegistrationStageView**:
- Статусная машина:
  - `generatingKeys` → `fetchingChallenge` → `computingPoW` → `submittingRegistration` → `complete`
- `ConvergingSignalView` — анимация собирающихся линий
- `DetailRow` — summary после регистрации (username, deviceId)
- PoW прогресс: от 0 до 1

#### Тексты онбординга — канон (2026-07-17, iOS commit `3bb68b49`)

Правила копирайтинга для всех new-user-facing экранов (онбординг, регистрация,
ориентация, empty states первого запуска). Android обязан следовать им же:

1. **Никакого жаргона глоссария** в текстах для новичка: слова *node, replica,
   Streams / Поток, идентификатор устройства* запрещены. В UI-текстах — только
   видимые имена экранов: **Chats/Чаты, Synaps, Settings/Настройки**.
2. **Никаких лозунгов** про доверие и самоописаний: "trust is computed",
   "Establishing trust", "Доверие приглашают, а не собирают", "postmodern
   messenger" — всё это удалено на iOS и не должно появиться на Android.
3. **Декларируем ровно два тезиса** — и только в теглайне первого экрана:
   *интернет — пространство свободы выражения мысли; идентичность — конструкт*
   (бодрийяровский смысл — создаётся с нуля и не отсылает ни к чему вне себя, —
   но имя Бодрийяра в UI не упоминается).
4. RU: «идентичность», не «личность». Бренд: **Konstruct** (EN) /
   **Конструкт** (RU) / **共創** (JA) — «Construct» латиницей в RU-тексте запрещён.

Канонические значения (совпадают с iOS `Localizable.strings`, ключи Android могут
отличаться именем — значения обязаны совпадать):

| Смысл | EN | RU |
|---|---|---|
| Теглайн | the internet is a space of free thought.\nidentity is a construct. | интернет — пространство свободы выражения мысли.\nидентичность — конструкт. |
| Кнопка создания | CREATE IDENTITY | СОЗДАТЬ ИДЕНТИЧНОСТЬ |
| Стадия регистрации | Creating your keys | Создание ключей |
| PoW: энтропия | randomness collected | случайность собрана |
| PoW: подбор | anti-spam proof in progress | антиспам-проверка |
| PoW: готово | done | готово |
| QR-подпись (invite) | this code is an invitation to connect. | этот код — приглашение связаться. |
| Пустой список чатов | No conversations yet | Пока нет чатов |
| Подсказка пустого списка | Find someone by @alias or show your QR code | Найдите человека по @псевдониму или покажите свой QR-код |

#### OrientationScreen (обзор приложения) — РЕАЛИЗОВАН (commit 14bfc4c, 2026-07-18)

`ui/screens/orientation/OrientationScreen.kt` — три страницы, `HorizontalPager`,
Skip всегда доступен, точки-индикатор, кнопка Continue → Enter Konstruct. Показывается
один раз после регистрации: Onboarding → Orientation → Main (стартовый таб = Synaps).
Splash маршрутизирует initialized-but-not-oriented → Orientation (свежая регистрация ИЛИ
апгрейд с версии до фичи). Флаг завершения — `OrientationStore` (DataStore-preferences,
не Keystore). **Replay из Settings** («Как устроен Конструкт») пока не подключён — экран
уже принимает `fromSettings` для возврата назад вместо Main; остаётся добавить строку в
`SettingsScreen`. Канонические тексты en+ru совпадают с iOS `orientation_*`.

Обязательный экран после первой регистрации + replay из Settings
(«Как устроен Конструкт» / "How Konstruct works"). Три страницы, Skip всегда
доступен; на последней — кнопка «Войти в Конструкт» / "Enter Konstruct".
Канонические тексты (iOS `orientation_*`):

1. **Идентичность / Identity** — иллюстрация: гекс + ключ.
   Body EN: "No phone number, no email, no real name. Your identity is created
   from scratch on this phone and refers to nothing outside itself. Who you are
   here is what you make."
   Body RU: «Без телефона, почты и настоящего имени. Идентичность создаётся с
   нуля на этом телефоне и не отсылает ни к чему вне себя. Кто вы здесь —
   решаете вы.»
   Caption: "A public @alias is optional. Your keys never leave this phone." /
   «Публичный @псевдоним необязателен. Ключи не покидают телефон.»
2. **Люди / People** — две карточки: «QR / ссылка» (Показать или сканировать.
   Действует 5 минут.) и «Поиск» (Найти @псевдоним → отправить запрос.).
   Body RU: «Общего каталога нет, и вас нельзя найти без вашего ведома. Чтобы
   начать общение, один приглашает другого: покажите QR-код, отправьте ссылку
   или найдите @псевдоним и отправьте запрос.»
   Caption: "Nobody can message you without your consent." / «Никто не напишет
   вам без вашего согласия.»
3. **Три места / Three places** — карта приложения строками **Чаты**
   (Личная переписка), **Synaps** (Люди, поиск, запросы), **Настройки**
   (QR, восстановление, устройства).
   Caption: «Начните с Synaps — покажите QR или найдите человека.»

### 5.2 MainTabView (корневой экран)

- **ChatsListView** (Tab 0) — всегда загружен
- **SynapsView** (Tab 1) — lazyload
- **CallHistoryView** (Tab 2, опционально) — lazyload
- **SettingsView** (Tab 3) — lazyload

**ZStack pattern**: все табы существуют одновременно, opacity переключает видимость.
`allowsHitTesting` блокирует неактивные табы. `visitedTabs: Set<Int>` — ленивая загрузка.

Tab bar скрывается когда `isInChat || isInSettings == true`.

### 5.3 ChatsListView — список чатов

> **Android сейчас:** `ChatsListScreen` + `ChatRow` на `ChatsRepository` (Room).
> Empty CTA открывает Synaps. Swipe / pin / pull-to-refresh — нет.

- `CTSearchBar` вверху
- `List` / `LazyColumn` с `ChatRowView`
- Pull-to-refresh (background fetch)
- Swipe actions: pin, mark read, delete
- `contextMenu`: pin, mark read, delete
- Navigation: tap → `ChatView`
- Featured пустое состояние

**ChatRowView**:
- Аватар (CTAvatar) + текст + таймстемп
- `<@username>` если есть handle, иначе `RESOLVED_DISPLAY_NAME` + `.uppercased()`
- Badge непрочитанных сообщений `[N]`

### 5.4 ChatView — экран чата

> **Android сейчас:** `ChatScreen` + `ChatViewModel` — `LazyColumn` пузырей и
> `MessageInputView`, observe/send через `MessagesRepository`. Нет поиска,
> пагинации, звонка, swipe-to-dismiss.

- `CTNavBar` с именем контакта, статусом соединения, кнопками поиска/звонка
- `LazyColumn` с сообщениями
- Default scroll anchor: bottom
- Пагинация: "Load older messages" сверху
- `MessageBubble` для каждого сообщения
- `MessageInputView` снизу
- Режим поиска: overlay с `CTSearchBar`
- Режим редактирования/выбора: selection bar + delete button
- Scroll-to-bottom button (появляется при скролле вверх)
- Swipe-to-dismiss с левого края

**MessageBubble**:
- Incoming: `background = Color.CT.bgMsg` + 0.5pt border
- Outgoing: `background = Color.CT.accent`
- `RoundedRectangle(cornerRadius = 10)`
- Reply preview, context menu, long-press actions

### 5.5 SettingsView — экран настроек

- `CTNavBar` + `ScrollView` + `LazyColumn`
- **Секции**: Profile, Share, Settings, About, Developer
- Каждая секция: `CTSectionGroup { ... }`
- Строки: `CTSettingsRow` + `CTSep(style = .thin)` между ними
- Profile row: `CTHexAvatar` + VStack(name + username + discoverable) + `CTSymbol.forward`
- Recovery banner (если recovery не настроен): красная карточка с предупреждением

### 5.6 AccountSettingsView — профиль

- Avatar header: `MainAvatarView` (круглый, размер 100dp)
- Edit mode toggle: pencil icon → edit fields
- Identity section: username, display name, status
- Account section: user ID, linked devices, social recovery, sign out
- Backup section: export/import/nearby
- Danger zone: sign out all, delete account (с abort window 10s)
- `.sheet` для: фото, crop, QR, backup, recovery, social recovery

### 5.7 AppearanceSettingsView — темы

- `AppTheme` enum: `automatic`, `light`, `dark`
- `CTSectionGroup` с Radio-like строками
- Только dark theme реализована
- `[✓]` для выбранной темы
- "soon" badge для недоступных

### 5.8 NetworkSettingsView — сеть

- Connection status: connected/disconnected/connecting + icon + text
- Текущий traffic path
- VEIL proxy toggle
- VEIL mode selector (disabled/all/auto/geo)
- Custom server URL (debug only)

### 5.9 SecurityView — безопасность

- PIN code: enable/disable/change
- Duress PIN setup
- Recovery phrase: setup/view
- Keys recovery
- Security gate (PIN lock screen)
- Safety Numbers verification

### 5.10 SynapsView — контакты (соты)

> **Android сейчас (намеренно проще канона):** mint v5 (share/copy link),
> paste-accept, список контактов → чат, FindUser и входящие contact requests.
> Honeycomb / ZoomableCloud и профильный sheet ещё не реализованы. Deep link
> `konstruct://add` пишется в `PendingInviteStore` и гасится после онбординга.

- «Honeycomb» layout: зуммируемый/панорамируемый облако из круглых аватаров
- `ZoomableCloud` + `HoneycombCloud` composables
- Proximity effect: центральные контакты крупнее и ярче
- `CTSearchBar` + remote search (поиск пользователей на сервере)
- Incoming contact requests секция
- Tap avatar → sheet с `UserProfileView`

---

## 6. Генератор анонимных имён

```kotlin
// DisplayNameGenerator.kt
object DisplayNameGenerator {
    private val adjectives = listOf(
        "silent", "happy", "swift", "brave", "gentle", "calm", "bright", "bold",
        "quick", "quiet", "wise", "noble", "free", "kind", "pure", "jolly",
        "witty", "fierce", "proud", "sly", "lucky", "cheerful", "jovial", "merry",
        "clever", "cunning", "valiant", "humble", "clear", "cool", "warm", "soft",
        "strong", "wild", "misty", "sunny", "cloudy", "starry", "frosty", "stormy",
        "ember", "blazing", "frozen", "mountain", "ocean", "river", "forest", "desert",
        "volcanic", "thunder", "solar", "lunar", "celestial", "crystal", "golden",
        "silver", "copper", "obsidian", "amber", "crimson", "azure", "verdant",
        "sleek", "sharp", "smooth", "tall", "deep", "light", "dark", "ancient",
        "agile", "majestic", "elegant", "graceful", "giant", "tiny", "nimble",
        "radiant", "gleaming", "shadowy", "whispering", "echoing", "vivid", "mystic",
        "hidden", "lonely", "weathered",
        // bonus IT-слова
        "deprecated", "recursive", "async", "frozen", "nested", "compiled", "broken",
        "pending", "idle", "verbose", "headless", "orphaned", "forked", "stale", "cursed"
    )

    private val animals = listOf(
        "fox", "wolf", "bear", "lion", "tiger", "panda", "jaguar", "panther",
        "leopard", "cheetah", "lynx", "cougar", "hyena", "jackal", "dingo",
        "wolverine", "otter", "seal", "orca", "dolphin", "whale", "shark",
        "ferret", "mongoose", "badger", "deer", "moose", "elk", "bison",
        "hare", "rabbit", "squirrel", "beaver", "hedgehog", "bat", "boar", "ox",
        "ram", "stag", "marten", "meerkat",
        "eagle", "hawk", "owl", "raven", "falcon", "swan", "dove", "crane",
        "heron", "sparrow", "robin", "finch", "wren", "phoenix", "crow",
        "vulture", "albatross", "kingfisher", "kestrel", "harrier", "gull",
        "penguin", "peacock", "parrot", "hornbill", "nightjar",
        "dragon", "griffin", "unicorn", "pegasus", "basilisk", "chimera",
        "kraken", "hydra", "manticore", "gryphon", "yeti", "kitsune", "sphinx", "serpent",
        "cobra", "viper", "python", "rattler", "gecko", "iguana", "scorpion",
        "spider", "mantis", "beetle", "butterfly", "moth", "dragonfly",
        "mammoth", "saber", "raptor", "tricera", "rex", "titan", "direwolf"
    )

    private val itNouns = listOf(
        "printer", "keyboard", "monitor", "server", "router", "modem", "firewall",
        "switch", "hub", "rack", "cable", "dongle", "cursor", "terminal",
        "daemon", "kernel", "process", "thread", "socket", "buffer", "pointer",
        "callback", "semaphore", "mutex", "cron", "webhook", "pipeline", "protocol",
        "endpoint", "payload", "namespace", "instance", "container", "cluster",
        "registry", "proxy", "gateway", "runtime", "compiler", "debugger",
        "spreadsheet", "invoice", "deadline", "standup", "backlog", "ticket",
        "milestone", "stakeholder", "deployment", "outage", "rollback", "hotfix",
        "sprint", "retro", "roadmap", "handover", "escalation", "pivot"
    )

    /**
     * Генерирует детерминированное имя на основе userId.
     * Формат: "adjective animal" (~80%) или "adjective itNoun" (~20%) — БЕЗ капитализации,
     * в нижнем регистре (как на iOS). Не добавляй replaceFirstChar/uppercase —
     * иначе имена разойдутся с iOS.
     */
    fun generate(userId: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray())

        // NB: value is treated as UNSIGNED 32-bit (matches iOS UInt32 math).
        // Do NOT mask with 0x7FFFFFFF — that diverges from iOS and yields
        // different names for ids whose 4-byte slice has the high bit set.
        fun getIndex(bytes: ByteArray, start: Int, modulo: Int): Int {
            var value = 0u
            for (i in 0 until 4) {
                value = value or (bytes[start + i].toUByte().toUInt() shl (24 - i * 8))
            }
            return (value % modulo.toUInt()).toInt()
        }

        val adjIndex = getIndex(hash, 0, adjectives.size)
        val useITNoun = (hash[8].toInt() and 0xFF) % 5 == 0

        val noun = if (useITNoun) {
            val nounIndex = getIndex(hash, 4, itNouns.size)
            itNouns[nounIndex]
        } else {
            val animalIndex = getIndex(hash, 4, animals.size)
            animals[animalIndex]
        }

        return "${adjectives[adjIndex]} $noun"
    }

    fun generateShortId(userId: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray())
        return hash.take(3).joinToString("") { "%02x".format(it) }
    }
}
```

**Приоритет отображения имени контакта:**
1. `displayName` (если не пустой)
2. `username` (если не пустой)
3. Анонимное имя от `DisplayNameGenerator.generate(from: userId)`

---

## 7. Локализация (i18n)

```kotlin
// В iOS: NSLocalizedString("key", comment: "")
// В Android: strings.xml
// Ключи идентичны в en.lproj/Localizable.strings и ru.lproj/Localizable.strings

// Пример strings.xml:
// <string name="settings">Settings</string>
// <string name="chats">Chats</string>
// <string name="search_prompt">Search</string>
// ...

// 933 keys в en.lproj (см. en.lproj/Localizable.strings)
// Нужно поддерживать минимум: en, ru, ja (планируется)
```

**Правила локализации:**
- ВСЕ видимые строки используют `NSLocalizedString` (iOS) → `stringResource` (Android)
- Nav titles: uppercase + `letterSpacing(4)` — передаётся сырая строка
- `.uppercased()` в коде, не в переводе
- Новый ключ → добавляется в оба языка

---

## 8. Архитектура данных

### 8.1 User Identity — два типа идентификаторов

| Тип | Формат | Источник | Использование |
|---|---|---|---|
| `ServerUserId` | 36-char UUID `14f28d31-…` | Сервер | gRPC, Room, conversation/sealed recipient |
| `CryptoDeviceId` | 32-char hex `6f5e37ac…` | deriveDeviceId(identityPublicKey) | Multi-device linking, QR коды |

```kotlin
// UserIdentity.kt
data class ServerUserId(val rawValue: String)    // UUID 36
data class CryptoDeviceId(val rawValue: String)   // hex 32
```

**Важно**: NEVER путать эти типы. `ServerUserId` адресует аккаунт и gRPC/Room;
`CryptoDeviceId` адресует конкретную реплику и используется в CFE session/AD,
invite/device границах. `PeerDeviceRegistry` переводит account→device и
проверяет `deriveDeviceId(identityPublic)` перед записью. Сеть всё ещё получает
account id: server fan-out сам кладёт один envelope в per-device очереди.
Android получает account→device set, отдаёт его `planSend`, отправляет recipient
copies и own replicas, а на входе пробует все non-destructive bundle candidates
через `planReceivingInit`. Очередь нескольких carrier сообщений и live interop
ещё не покрыты.

### 8.2 DisplayName Resolution

```kotlin
// User модель
data class User(
    val id: String,
    val username: String,    // может быть пустым
    val displayName: String, // может быть пустым
    val isSharingWithMe: Boolean // true = контакт поделился профилем
) {
    val resolvedDisplayName: String
        get() = when {
            displayName.isNotEmpty() -> displayName
            username.isNotEmpty() -> username
            else -> DisplayNameGenerator.generate(from = id)
        }
}
```

### 8.3 Core Data — модель данных

На Android аналог — Room. Основные entity:

```kotlin
// Chat
@Entity
data class Chat(
    @PrimaryKey val id: String,
    val otherUserId: String,  // ServerUserId
    val lastMessageText: String?,
    val lastMessageTime: Long?,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false
)

// Message
@Entity
data class Message(
    @PrimaryKey val id: String,
    val chatId: String,
    val text: String,
    val isSentByMe: Boolean,
    val timestamp: Long,
    val deliveryStatus: DeliveryStatus, // sending, sent, delivered, read, failed
    val replyToId: String? = null,
    val mediaType: MediaType? = null, // image, video, audio, file, voice
    val mediaUrl: String? = null,
    val contentType: Int = 0          // 0 = regular; control types are never persisted as visible rows
)
```

> **Control-message render guard (mirror of iOS Fix #3).** A session-control signal
> (`ping`/`ready`/`reset_init`) must never appear in the transcript. Defense in depth — do
> ALL of these, because a single missed check leaks a bubble:
> 1. **Consumer**: dispatch on `content_type` before persisting and `return` (see
>    [Session-Control Message Format](#session-control-message-format-typed-binary--do-this-not-magic-strings)) — control rows are never created.
> 2. **At persist**: if a row is created anyway, stamp `contentType` from the decrypted
>    text (`startsWith("__session_…")` / `"session_ready_"`) so the chat query can exclude
>    it (`WHERE contentType = 0`).
> 3. **At display**: the chat query filters `contentType = 0` **and** a Kotlin-side guard
>    drops any row whose decrypted text matches a control prefix — iOS learned the hard way
>    that an at-rest-encrypted row has a null plaintext column, so a SQL `text LIKE` filter
>    silently fails; the authoritative filter runs on the decrypted display text.
```kotlin
// User (Contact)
@Entity
data class User(
    @PrimaryKey val id: String,        // ServerUserId (UUID)
    val username: String = "",
    val displayName: String = "",
    val avatarData: ByteArray? = null,
    val isContact: Boolean = false,
    val isBlocked: Boolean = false,
    val isSharingWithMe: Boolean = false
)
```

---

## 9. Структура проекта (Android Reference)

Рекомендуемая структура (цель, не текущее дерево). Фактическая раскладка
2026-09-21: `crypto/`, `data/`, `domain/`, `service/`, `invite/`, `stealth/`,
`viewmodel/`, `ui/` — см. `docs/IMPLEMENTATION_PLAN.md` File Structure Summary
и `README.md`. Пакетов `design/` и `security/` нет.

Рекомендуемая структура Android-проекта, соответствующая iOS-архитектуре:

```
app/src/main/java/com/construct/messenger/
├── ConstructApp.kt              // Application class
├── MainActivity.kt             // Single activity

├── design/                         // Design System > ConstructTheme.swift
│   ├── CTColor.kt                  // Color.CT palette
│   ├── CTTypography.kt             // CTFont
│   ├── CTSymbol.kt                 // ASCII-символы
│   ├── CTLayout.kt                 // Spacing, corner radius
│   └── CTShadows.kt                // Shadow tokens

├── ui/components/                  // Reusable > ConstructTheme.swift (components)
│   ├── CTNavBar.kt
│   ├── CTTabBar.kt
│   ├── CTButton.kt
│   ├── CTTextField.kt
│   ├── CTSearchBar.kt
│   ├── CTSectionGroup.kt
│   ├── CTSettingsSectionHeader.kt
│   ├── CTSettingsRow.kt
│   ├── CTRowIcon.kt
│   ├── CTAvatar.kt                 // MainAvatarView
│   ├── CTSep.kt
│   ├── CTSystemMessage.kt
│   ├── CTNoise.kt
│   ├── CTModeSelector.kt
│   ├── CTLogoView.kt
│   └── ConnectionStatusIndicator.kt

├── ui/screens/                     // Views/
│   ├── onboarding/                 // OnboardingView, RegistrationFlowView
│   ├── main/                       // MainTabView
│   ├── chats/                      // ChatsListView, ChatView, ChatRowView
│   │   ├── ChatListScreen.kt
│   │   ├── ChatScreen.kt
│   │   ├── ChatRow.kt
│   │   └── MessageBubble.kt
│   ├── synaps/                     // SynapsView
│   ├── settings/                   // SettingsView
│   │   ├── SettingsScreen.kt
│   │   ├── AccountSettingsScreen.kt
│   │   ├── AppearanceSettingsScreen.kt
│   │   ├── NetworkSettingsScreen.kt
│   │   └── SecurityScreen.kt
│   └── calls/                      // CallHistoryView, InCallView

├── viewmodel/                      // ViewModels/
│   ├── AuthViewModel.kt
│   ├── ChatsViewModel.kt
│   ├── ChatViewModel.kt
│   ├── SettingsViewModel.kt
│   └── ContactRequestsViewModel.kt

├── data/
│   ├── local/                      // Core Data → Room
│   │   ├── AppDatabase.kt
│   │   ├── dao/
│   │   │   ├── ChatDao.kt
│   │   │   ├── MessageDao.kt
│   │   │   └── UserDao.kt
│   │   └── entity/
│   │       ├── ChatEntity.kt
│   │       ├── MessageEntity.kt
│   │       └── UserEntity.kt
│   └── remote/                     // gRPC services
│       ├── AuthService.kt
│       ├── MessagingService.kt
│       ├── UserService.kt
│       └── ...

├── security/                       // Crypto, Keychain, auth tokens
│   ├── KeyStoreManager.kt         // KeychainManager (secure storage for keys + tokens)
│   ├── TokenUtils.kt              // PASETO v4.public claims extraction (see §13)
│   ├── AuthSessionManager.kt      // Session token state (see §13)
│   ├── TokenRefreshCoordinator.kt // Single-flight refresh actor (see §13)
│   └── DisplayNameGenerator.kt    // iOS DisplayNameGenerator -> Kotlin

├── util/                           // Utilities/
│   ├── UserIdentity.kt
│   ├── Constants.kt
│   └── Extensions.kt

└── res/
    ├── values/strings.xml          // en (default)
    ├── values-ru/strings.xml       // ru
    └── values-ja/strings.xml       // ja (planned)
```

---

> **Примечание**: iOS использует `@Observable` для ViewModels, Core Data для персистентности, gRPC-Swift для сети. На Android аналогами будут: Jetpack Compose State/ViewModel, Room, gRPC-Kotlin/OkHttp.

> **Все решения, дизайн-токены и архитектурные паттерны следует согласовывать с iOS-версией** — приложение должно выглядеть и работать одинаково на обеих платформах (за исключением platform-specific элементов управления).

---

# 10. Crypto Core — Rust FFI

**Источник**: `CryptoManager.swift`, `CryptoSessionInitializationService.swift`, `MessageCryptoService.swift`
**Rust FFI**: `construct_core.swift` (UniFFI) → `construct_core.kt` (same UniFFI bindings)

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│              CryptoManager (Kotlin)                      │
│  ┌──────────────┐  ┌─────────────────────────────────┐  │
│  │ coreLock     │  │ OrchestratorCore (Rust via FFI) │  │
│  │ (Mutex)      │  │ ┌─────────────────────────────┐ │  │
│  └──────┬───────┘  │ │ X3DH · Double Ratchet       │ │  │
│         │          │ │ Kyber-768 · PQXDH            │ │  │
│  ┌──────▼───────┐  │ │ Session heal · Archive       │ │  │
│  │ KeyManager   │  │ │ Orchestrator state           │ │  │
│  │ (Encrypted   │  │ └─────────────────────────────┘ │  │
│  │  Keystore)   │  └─────────────────────────────────┘  │
│  └──────────────┘                                        │
└─────────────────────────────────────────────────────────┘
```

---

## Android implementation contract (актуально на 2026-09-18)

`CryptoManager` — тонкая синхронная оболочка над UniFFI. До логина он держит
`ClassicCryptoCore` для bootstrap/registration; после `setLocalUserId` создаёт
`OrchestratorCore`, передавая туда только локальный `CryptoDeviceId`, выведенный
из identity key. Все native вызовы сериализованы через `coreLock`. В отличие от
старого примера ниже, Android не разбирает wire payload и не принимает решения
о heal: это делает Rust CFE. `PeerDeviceRegistry` хранит server account→device
mapping, а `CfeTimerBridge` владеет только platform wake-up для core timers.

| iOS canon | Android mirror |
|---|---|
| `CryptoManager` + `OrchestratorCore` | `CryptoManager` + `OrchestratorCore` (device-space ids) |
| Keychain session/archive/core snapshots | Room `SessionStateStore` + Keystore tokens |
| `SessionActionExecutor` | `MessageProcessor` + `ProcessorEffectsImpl` + `CfeTimerBridge` |
| typed `SaveToSecureStore(slot,data)` | `CfeSecureStoreSlot` → Room key mapping |
| `exportOrchestratorState` / PQ snapshot | same UniFFI calls, restored before stream |

The generated binding and the three `.so` files are one artifact and must be
refreshed together from the rolling construct-core Android release. Do not edit
`construct_core.kt` manually.

## Historical pseudocode (not a copy target)

```kotlin
@Singleton
class CryptoManager @Inject constructor(
    private val keychainManager: KeychainManager,
    private val sessionInitService: CryptoSessionInitializationService,
    private val messageCrypto: MessageCryptoService,
    private val pqcKeyManager: PQCKeyManager
) {
    private val coreLock = Mutex()

    private var orchestratorCore: OrchestratorCore? = null
    private var _cachedUserId: ServerUserId? = null

    val isInitialized: Boolean
        get() = orchestratorCore != null

    // ── Initialization ───────────────────────────────────────────

    fun setLocalUserId(userId: ServerUserId) {
        _cachedUserId = userId
        val cryptoId = cryptoLocalUserId
        if (orchestratorCore != null) {
            orchestratorCore!!.setLocalUserId(cryptoId)
            migrateSessionsIfNeeded(orchestratorCore!!)
            return
        }
        val keysData = keychainManager.loadPrivateKeysData()
            ?: run {
                Log.e(TAG, "setLocalUserId: no keys available")
                return
            }
        viewModelScope.launch {
            coreLock.withLock {
                val newCore = createOrchestratorCoreFromKeys(keysData, cryptoId)
                importOtpks(newCore)
                pqcKeyManager.loadCfeSnapshot(newCore)
                loadOrchestratorStateCfe(newCore)
                migrateSessionsIfNeeded(newCore)
                orchestratorCore = newCore
            }
        }
    }

    // ── Event Handling (Core Decision API) ───────────────────────

    suspend fun handleOrchestratorEvent(
        event: CfeIncomingEvent,
        tag: String? = null
    ): List<CfeAction> = coreLock.withLock {
        val core = orchestratorCore
            ?: throw CryptoManagerError.CoreNotInitialized
        val actions = core.handleEvent(event)
        logOrchestratorEvent(event, actions, tag)
        actions
    }

    // ── Session Queries ─────────────────────────────────────────

    fun hasSession(userId: ServerUserId): Boolean =
        orchestratorCore?.hasSession(userId) == true

    fun getSessionHealth(userId: ServerUserId): SessionHealthReport? =
        orchestratorCore?.getSessionHealth(userId)

    fun getAllSessionUserIds(): List<ServerUserId> =
        orchestratorCore?.getAllSessionContactIds().orEmpty()

    // ── Session Init (INITIATOR) ────────────────────────────────

    suspend fun initializeSession(
        userId: ServerUserId,
        recipientBundle: KeyBundle,
        oneTimePreKeyPublic: ByteArray? = null,
        oneTimePreKeyId: UInt? = null,
        kyberPreKeyPublic: ByteArray? = null,
        kyberOneTimePreKeyPublic: ByteArray? = null,
        kyberOneTimePreKeyId: UInt? = null,
        spkUploadedAt: ULong = 0UL,
        spkRotationEpoch: UInt = 0U,
        kyberSpkUploadedAt: ULong = 0UL,
        kyberSpkRotationEpoch: UInt = 0U
    ) = coreLock.withLock {
        sessionInitService.initializeSession(/* ... */)
    }

    // ── Session Init (RESPONDER) ────────────────────────────────

    suspend fun initReceivingSession(
        userId: ServerUserId,
        recipientBundle: KeyBundle,
        firstMessage: ChatMessage,
        spkUploadedAt: ULong = 0UL,
        spkRotationEpoch: UInt = 0U,
        kyberSpkUploadedAt: ULong = 0UL,
        kyberSpkRotationEpoch: UInt = 0U
    ): ByteArray = coreLock.withLock {
        sessionInitService.initReceivingSession(/* ... */)
    }

    // ── Encrypt / Decrypt ───────────────────────────────────────

    suspend fun encryptMessage(
        plaintext: String,
        userId: ServerUserId
    ): EncryptedMessageComponents = coreLock.withLock {
        messageCrypto.encryptMessage(/* ... */)
    }

    suspend fun decryptMessage(
        message: ChatMessage,
        contactIdOverride: ServerUserId? = null
    ): MessageDecryptResult = coreLock.withLock {
        messageCrypto.decryptMessage(/* ... */)
    }

    // ── Background Decrypt ──────────────────────────────────────

    suspend fun decryptMessageForBackground(message: ChatMessage): MessageDecryptResult = coreLock.withLock {
        if (PersistentACKStore.isProcessedInMemory(message.id)) {
            throw CryptoManagerError.DuplicateMessage
        }
        val core = orchestratorCore ?: throw CryptoManagerError.CoreNotInitialized
        if (!core.hasSession(message.from)) {
            throw CryptoManagerError.SessionNotFound
        }
        val contentForDecrypt = MessagePadding.unpadCiphertext(message.content)
        val result = core.decryptMessage(
            contactId = message.from,
            ephemeralPublicKey = message.ephemeralPublicKey,
            messageNumber = message.messageNumber,
            content = contentForDecrypt
        )
        saveSessionToKeychain(message.from)
        MessageDecryptResult(result.plaintext, result.storageKey)
    }

    // ── Orchestrator State ──────────────────────────────────────

    fun saveOrchestratorStateCfe() {
        viewModelScope.launch {
            coreLock.withLock {
                val core = orchestratorCore ?: return@launch
                val blob = core.exportOrchestratorState()
                keychainManager.saveData(blob, ORCHESTRATOR_STATE_KEY)
            }
        }
    }

    fun loadOrchestratorStateCfe(core: OrchestratorCore) {
        val data = keychainManager.loadData(ORCHESTRATOR_STATE_KEY) ?: return
        core.importOrchestratorState(data)
    }

    fun clearOrchestratorStateCfe() {
        keychainManager.deleteData(ORCHESTRATOR_STATE_KEY)
    }

    // ── Session Persistence ─────────────────────────────────────

    private fun saveSessionToKeychain(userId: ServerUserId) {
        val sessionData = orchestratorCore?.exportSession(userId) ?: return
        keychainManager.saveSessionData(sessionData, userId)
        saveOrchestratorStateCfe()
    }

    // ── Key Management ──────────────────────────────────────────

    fun rotateSignedPrekey(): RotatedSpkBundle {
        val core = orchestratorCore ?: throw CryptoManagerError.CoreNotInitialized
        return core.rotateSignedPrekey()
    }

    fun generateOneTimePrekeys(count: UInt): List<OtpkPair> {
        val core = orchestratorCore ?: throw CryptoManagerError.CoreNotInitialized
        return core.generateOneTimePrekeys(count)
    }

    fun oneTimePrekeyCount(): UInt =
        orchestratorCore?.oneTimePrekeyCount() ?: 0U

    fun deleteAllCryptoKeys() {
        orchestratorCore = null
        keychainManager.deletePrivateKeys()
        keychainManager.deleteAllKeys()
    }
}
```

## Key Differences from iOS

| Aspect | iOS | Android |
|--------|-----|---------|
| Lock | `NSRecursiveLock()` | `synchronized(coreLock)` in `CryptoManager` |
| Thread | `@MainActor` | `MessagingRuntime`/effects on `Dispatchers.IO` |
| Secure Storage | Keychain | EncryptedSharedPreferences + Keystore |
| Core Init | Sync in `setLocalUserId` | `viewModelScope.launch` for async init |
| Error Handling | `throw` + `try?` | exceptions at the CFE boundary + explicit `ProcessingOutcome` |

## iOS Anti-patterns Fixed

1. No `try?` swallowing — all errors propagate
2. Mutex instead of NSLock — proper async-compatible locking
3. No direct Core access — all through `coreLock.withLock`
4. No `UserDefaults` for crypto state — only EncryptedSharedPreferences
5. Exhaustive error types — sealed class for all `CryptoManagerError` variants

---

# 11. Session Lifecycle Controller

**Источник**: `SessionLifecycleController.swift`, `SessionCoordinator.swift`
**Принцип**: Единственный entry point для всех session lifecycle операций

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   UI Layer                               │
│  ChatScreen · Settings · UserProfile                     │
│         │                    │                            │
│         ▼                    ▼                            │
│  ViewModel               ViewModel                        │
│         │                    │                            │
│         └────────┬───────────┘                            │
│                  ▼                                        │
│  ┌───────────────────────────────────────────────────┐   │
│  │          SessionController (DI-injected)          │   │
│  │  • routeIncomingMessage(message)                  │   │
│  │  • prewarmSessions(contactIds)                    │   │
│  │  • sendEndSession(userId, reason)                 │   │
│  │  • sendEndSessionToAllContacts(reason)            │   │
│  │  • handleKeySyncRequest(userId)                   │   │
│  │  • hasActiveSession(userId): Boolean              │   │
│  │  • onEphemeralSubscriptionNeeded: Callback         │   │
│  │  • onE2EDeliveryReceiptDecrypted: Callback         │   │
│  └─────────────────────┬─────────────────────────────┘   │
│                        │                                  │
│                        ▼                                  │
│  ┌───────────────────────────────────────────────────┐   │
│  │          SessionCoordinator (internal)            │   │
│  │  • MessageRouter                                  │   │
│  │  • PublicKeyBundleHandler                         │   │
│  │  • SessionInitializationService                   │   │
│  │  • Tie-break watchdogs                            │   │
│  │  • Responder fallback                             │   │
│  │  • Cooldown management                            │   │
│  └─────────────────────┬─────────────────────────────┘   │
│                        │                                  │
│                        ▼                                  │
│  ┌───────────────────────────────────────────────────┐   │
│  │          CryptoManager                            │   │
│  │          SessionActionExecutor                    │   │
│  │          MessageStreamManager                     │   │
│  └───────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

---

## SessionController Interface

```kotlin
@Singleton
class SessionController @Inject constructor(
    private val sessionCoordinator: SessionCoordinator,
    private val cryptoManager: CryptoManager
) {
    fun configure(streamManager: MessageStreamManager) {
        sessionCoordinator.configure(streamManager)
    }

    fun setContext(database: RoomDatabase) {
        sessionCoordinator.setDatabase(database)
    }

    var onEphemeralSubscriptionNeeded: ((ServerUserId) -> Unit)? = null
    var onE2EDeliveryReceiptDecrypted: ((List<String>) -> Unit)? = null

    fun routeIncomingMessage(message: ChatMessage, database: RoomDatabase) {
        sessionCoordinator.routeIncomingMessage(message, database)
    }

    fun prewarmSessions(
        contactIds: List<ServerUserId>,
        skipEndSessionNotification: Boolean = false
    ) {
        sessionCoordinator.prewarmSessions(contactIds, skipEndSessionNotification)
    }

    suspend fun sendEndSession(userId: ServerUserId, reason: String = "manual_reset") {
        sessionCoordinator.sendEndSession(userId, reason)
    }

    suspend fun sendEndSessionToAllContacts(reason: String = "logout") {
        sessionCoordinator.sendEndSessionToAllContacts(reason)
    }

    fun handleKeySyncRequest(userId: ServerUserId) {
        sessionCoordinator.handleKeySyncRequest(userId)
    }

    fun hasActiveSession(userId: ServerUserId): Boolean =
        cryptoManager.hasSession(userId)
}
```

## What NOT to do (iOS lessons)

**Don't create ad-hoc SessionCoordinator instances** — DI-injected `SessionController`.
**Don't call CryptoManager.hasSession from Views** — expose through ViewModel StateFlow.
**Don't pass sessionCoordinator through DI chain** — inject once at the right level.

## Internal Components

### SessionCoordinator (internal)

Owns all session state: `sessionStates`, `endSessionSentAt`, `resendAttemptedAt`, `tieBreakWatchdogs`, `responderFallbackTasks`, `messageRouter`, `publicKeyBundleHandler`, `sessionInitService`.

### SessionActionExecutor

Android's executor is `MessageProcessor`. It routes the CFE result and
`ProcessorEffectsImpl` applies the outward effects. The switch must keep the
Rust action set visible: `ApplyPqContribution` mutates the core, every
`SaveToSecureStore` writes its typed slot, `SessionTerminated` archives bytes
and removes hot state, and `HealSuppressed`/`EndSessionSuppressed`/
`MessageQueuedPendingInit` hold the cursor without ACK. Unknown actions must be
added explicitly when the UDL changes; never restore the former string-key
`SaveSessionToSecureStore` API.

### MessageRouter

Routes incoming messages: ACK/dedup via `AckStore`, sealed-sender resolution,
control/message classification, and delegation to `MessageProcessor`. It must
not grow a second crypto or healing implementation; CFE is the decision source.

## Concurrency Model

| Component | Threading |
|-----------|-----------|
| SessionController | `@MainScope` (ViewModel scope) |
| SessionCoordinator | `Dispatchers.Main` |
| SessionActionExecutor | `Dispatchers.Main` (called from router) |
| MessageRouter | `Dispatchers.Main` (called from stream) |
| CryptoManager | `Mutex` for core access, `Dispatchers.IO` for crypto ops |

**Rule**: All session state mutations on `Dispatchers.Main`. Crypto operations on `Dispatchers.IO` with `Mutex` protection.

---

# 12. Session Initialization

**Источник**: `SessionInitializationService.swift`, `CryptoSessionInitializationService.swift`, `PublicKeyBundleHandler.swift`, `SessionCoordinator.swift`
**Протокол**: X3DH → Double Ratchet → PQXDH (Kyber-768)

---

## Protocol Overview

```
Alice (INITIATOR)                          Bob (RESPONDER)
     │                                          │
     │  1. Fetch Bob's pre-key bundle (gRPC)    │
     │─────────────────────────────────────────>│
     │  2. X3DH key agreement (Rust)            │
     │  3. Create sending chain                 │
     │  4. Send msgNum=0 (encrypted ping)       │
     │─────────────────────────────────────────>│
     │                           5. msgNum=0 received
     │                           6. Fetch Alice's bundle (gRPC)
     │                           7. X3DH + initReceivingSession
     │                           8. Decrypt msgNum=0
     │                           9. PQXDH decapsulation (if Kyber)
     │  10. Send session_ready (encrypted)      │
     │<─────────────────────────────────────────│
     │  11. session_ready received              │
     │  12. Both sides ready → send messages    │
```

## Session-Control Message Format (typed binary — DO THIS, not magic strings)

> ⚠️ **Android: implement the typed format from day one.** The handshake signals
> (`ping`, `ready`, `reset_init`) are **protocol control, not chat content** — they must
> never render as a bubble. iOS historically encoded them as plaintext magic strings
> (`"__session_ready_<UUID>__"`), which leaked into the transcript and broke on format
> skew. That approach is being retired (see
> `decisions/binary-control-message-format.md`). The correct encoding puts the
> discriminator in the Envelope **`content_type`** field; the discriminator is therefore
> outside the renderable text pipeline and can never become a chat bubble.

### Wire encoding

The control signal rides a normal Double-Ratchet-encrypted message whose Envelope
`content_type` identifies the op. The `content_type` is **not** part of the AEAD
associated data (AD = `AD_VERSION ‖ local_user_id ‖ contact_id ‖ session_id ‖ dh_pub ‖
msg_num`), so setting it never affects decryption.

| Signal | `content_type` | Direction | Payload |
|--------|---------------:|-----------|---------|
| Session ping     | `25` `CONTENT_TYPE_SESSION_PING`        | INITIATOR → peer (tie-break nudge) | `SessionControl{op=PING}` |
| Session ready    | `26` `CONTENT_TYPE_SESSION_READY`       | RESPONDER → INITIATOR (phase 2)    | `SessionControl{op=READY}` |
| Session reset-init | `24` `CONTENT_TYPE_SESSION_RESET_INIT` | tie-break winner (atomic re-init)  | real X3DH first-ratchet carrier (msgNum=0) — **NOT** a pure signal |
| End session      | `21` `CONTENT_TYPE_SESSION_RESET`       | either                              | 16-byte sentinel (unencrypted) |

`SessionControl` (in `messaging/e2ee.proto`):

```protobuf
message SessionControl {
  uint32 version = 1;   // unknown versions are ignored (forward-compat)
  SessionOp op = 2;     // PING / READY / RESET_INIT / END — mirrors content_type
  bytes nonce = 3;      // random per-signal; dedup + tie-break watchdog correlation
}
enum SessionOp { SESSION_OP_UNSPECIFIED=0; PING=1; READY=2; RESET_INIT=3; END=4; }
```

No checksum: integrity is already guaranteed by the Double Ratchet AEAD tag. The byte
budget is spent on `version` + `op` for forward-compat.

### Consumer rule (byte-sniff — accept BOTH)

Dispatch on `content_type` **before** the chunk reassembler / text pipeline. Fall back to
the legacy plaintext prefix only to interop with older iOS peers still in the field:

```kotlin
fun sessionOp(contentType: Int, decryptedPlaintext: String?): SessionOp? =
    when (contentType) {
        25 -> SessionOp.PING
        26 -> SessionOp.READY
        24 -> SessionOp.RESET_INIT
        21 -> SessionOp.END
        else -> decryptedPlaintext?.let {            // legacy fallback (old iOS)
            when {
                it.startsWith("__session_ping")  -> SessionOp.PING
                it.startsWith("__session_ready") || it.startsWith("session_ready_") -> SessionOp.READY
                it.startsWith("__session_reset_init") || it.startsWith("session_reset_init_") -> SessionOp.RESET_INIT
                else -> null
            }
        }
    }
// A non-null result → handle as control, return BEFORE persisting. Never create a Message row.
// RESET_INIT (24) is special: the X3DH init already consumed the payload; the inner is a sentinel.
// Also keep a render-time guard (see §8.3): never show a row whose decrypted text matches these prefixes.
```

### Producer rule (S3 binary payload — current state as of 2026-07-17)

**iOS flipped S3 ON 2026-07-17** (`FeatureFlags.binarySessionControlPayload` default `true`):
producers now send a serialized `SessionControl{op, nonce}` as the encrypted payload and the
legacy magic string is dropped from the wire. Android should do the same **from day one** —
there is no reason for a new platform to ever produce magic strings:

- Producer: typed `content_type` (24/25/26) + payload = `SessionControl{op, nonce}.serialize()`.
- Consumer: dispatch on `content_type` first, **keep the legacy string parser as a fallback
  forever** (see Consumer rule above) — older iOS builds in the field may still produce strings.
- Escape hatch (mirrors iOS): if an ancient pre-typed peer resurfaces, iOS can be toggled back
  to string-producing dual-send per-device; Android does not need this toggle unless the same
  situation arises.

> **Rollout / server dependency**: the server must know `content_type` 25/26 or it
> re-emits them as `E2EE_SIGNAL` (1) and the typed path goes inert (it does **not** drop
> the message — it is fail-open). The server proto was updated 2026-06-23
> (`construct-server/shared/proto/core/envelope.proto`) and is deployed fleet-wide.

### Control-plane storm hardening (END_SESSION / SESSION_RESET_INIT) — MANDATORY

iOS shipped these protections 2026-07-16/17 after a production desync storm (one OTPK
mismatch → 7+ END_SESSIONs re-delivered from the offline queue → parallel INITIATOR
re-inits destroyed a freshly established healthy session → permanent one-way messaging).
Full root cause: `sessions/2026-07-16-end-session-storm-fix.md`. Android MUST implement
the same invariants — they are protocol behaviour, not iOS implementation detail:

1. **Inbound control coalesce (receive side).** After handling one END_SESSION or
   SESSION_RESET_INIT for a peer, further control messages of the same class from that peer
   within a **45 s** cooldown window are ACK'd only (mark processed + delivery receipt) and
   NOT acted upon. An SRI also counts as END_SESSION for the coalesce window (it already
   reset the peer). The server offline queue re-delivers control batches on every reconnect;
   acting on each copy re-archives keys and re-tears sessions.
2. **Debounced re-init + fresh-session guard.** On END_SESSION, delay the INITIATOR re-init
   (~1.5 s debounce, one pending task per peer). When the debounce fires, **skip the re-init
   entirely if a session with that peer now exists** — it was established after the
   END_SESSION arrived and must not be destroyed. (Safe because the router wipes the old
   session *before* delegating: any live session is post-END by construction.)
3. **Cancel pending re-init on progress.** An incoming ping / session_ready / SRI, or a
   successful RESPONDER init for that peer, cancels the pending END_SESSION re-init.
4. **Single in-flight INITIATOR re-init per peer.** Coalesce concurrent re-init requests;
   never run two X3DH inits for the same peer in parallel.
5. **Outbound END_SESSION rate limit.** Per-peer cooldown on *sending* END_SESSION
   (prewarm "session missing", init-failure paths). Re-delivered copies of the same failed
   init must not each emit a fresh END_SESSION.
6. **Stale END_SESSION filter.** Persist `establishedAt` per peer (survives restart, e.g.
   Keychain/EncryptedSharedPreferences); on restore, hydrate it for CFE-restored sessions
   *before* processing any queued control message. An END_SESSION whose timestamp pre-dates
   the current session's `establishedAt` is stale — ACK and drop.
7. **Never replay control carriers as "orphaned init".** END_SESSION / SRI / sender-sync
   messages must be excluded from any msgNum=0 reprocessing queue — replaying them loops
   session teardown on every reconnect.

## Key Bundle Structure

```kotlin
data class PublicKeyBundle(
    val userId: ServerUserId,
    val identityPublic: ByteArray,
    val signedPrekeyPublic: ByteArray,
    val signature: ByteArray,               // Ed25519 signature of SPK
    val verifyingKey: ByteArray,
    val suiteId: UInt,                      // 1 = X3DH, 2 = PQXDH
    val oneTimePreKeyPublic: ByteArray?,
    val oneTimePreKeyId: UInt?,
    val kyberPreKeyPublic: ByteArray?,
    val kyberOneTimePreKeyPublic: ByteArray?,
    val kyberOneTimePreKeyId: UInt?,
    val spkUploadedAt: ULong,
    val spkRotationEpoch: UInt,
    val kyberSpkUploadedAt: ULong,
    val kyberSpkRotationEpoch: UInt
)
```

## INITIATOR Flow

### Step 1: Fetch Pre-Key Bundle

```kotlin
suspend fun fetchPublicKeyWithRetry(
    userId: ServerUserId,
    deviceId: String? = null,
    maxAttempts: Int = 3,
    initialDelay: Duration = 1.seconds
): PublicKeyBundle {
    var lastError: Throwable? = null
    var delay = initialDelay
    for (attempt in 1..maxAttempts) {
        try {
            return keyServiceClient.getPreKeyBundle(userId, deviceId)
        } catch (e: Throwable) {
            lastError = e
            if (attempt < maxAttempts) { delay(delay); delay *= 2 }
        }
    }
    throw lastError ?: NetworkException("Failed to fetch pre-key bundle")
}
```

### Step 2: Validate Bundle

```kotlin
fun validateBundle(bundle: PublicKeyBundle) {
    val knownEpoch = keyStore.loadSpkEpoch(bundle.userId)
    if (bundle.spkRotationEpoch < knownEpoch) {
        throw SessionError.StaleSpkBundle(bundle.spkRotationEpoch, knownEpoch)
    }
    keyStore.saveSpkEpoch(bundle.spkRotationEpoch, bundle.userId)
    if (bundle.suiteId == 2U && bundle.kyberSpkRotationEpoch == 0U) {
        throw SessionError.KyberEpochRequired
    }
}
```

### Step 3: Initialize Session (Rust)

Call `CryptoManager.initializeSession()` ([§10](#10-crypto-core--rust-ffi)).

### Step 4: Send Session Ping (msgNum=0)

Dual-send: `content_type = ContentType.SESSION_PING` (= 25) **+** legacy string payload
(see [Session-Control Message Format](#session-control-message-format-typed-binary--do-this-not-magic-strings)).

```kotlin
suspend fun sendSessionPing(userId: ServerUserId) {
    val pingContent = "__session_ping_${UUID.randomUUID()}__"   // legacy payload (interop w/ old iOS)
    val payload = outboundSessionService.encryptSessionControl(
        plaintext = pingContent,
        messageId = UUID.randomUUID().toString(),
        recipientId = userId
    )
    messagingServiceClient.sendMessage(
        messageId = pingId, recipientId = userId,
        senderId = currentUserId,
        conversationId = ConversationId.direct(currentUserId, userId),
        encryptedPayload = payload, timestamp = currentTimeMillis(),
        contentType = ContentType.SESSION_PING
    )
}
```

## RESPONDER Flow

Triggered by `MessageRouter.routeIncomingMessage` when `messageNumber == 0` and no session exists:

```kotlin
if (!cryptoManager.hasSession(otherUserId)) {
    pendingQueue.enqueue(message, otherUserId)
    fetchPublicKeyBundleAndInit(otherUserId, message)
    return
}
```

Call `CryptoManager.initReceivingSession()` ([§10](#10-crypto-core--rust-ffi)). Rust returns decrypted plaintext.

### PQXDH Decapsulation

```kotlin
if (firstMessage.kemCiphertext.isNotEmpty()) {
    val kyberOtpkId = firstMessage.kyberOtpkId
    if (kyberOtpkId > 0) {
        val otpkSecret = pqcKeyManager.getKyberOtpkSecret(kyberOtpkId)
        pqcKeyManager.decapsulateAndStrengthen(
            kemCiphertext = firstMessage.kemCiphertext,
            contactId = userId,
            secretKeyOverride = otpkSecret
        )
        pqcKeyManager.deleteKyberOtpk(kyberOtpkId)
    } else {
        pqcKeyManager.decapsulateAndStrengthen(
            kemCiphertext = firstMessage.kemCiphertext,
            contactId = userId
        )
    }
}
```

### Send Session Ready

Dual-send: typed `content_type` **+** legacy string payload (see
[Session-Control Message Format](#session-control-message-format-typed-binary--do-this-not-magic-strings)).
The `content_type = ContentType.SESSION_READY` is **mandatory** — omitting it is exactly the
bug that let `session_ready` render as a chat bubble on the peer.

```kotlin
suspend fun sendSessionReady(userId: ServerUserId) {
    val readyContent = "__session_ready_${UUID.randomUUID()}__"   // legacy payload (interop w/ old iOS)
    val payload = outboundSessionService.encryptSessionControl(
        plaintext = readyContent, messageId = UUID.randomUUID().toString(),
        recipientId = userId
    )
    messagingServiceClient.sendMessage(
        /* ... */,
        contentType = ContentType.SESSION_READY   // = 26, typed dispatch on the peer
    )
    // S3 (post legacy-removal): payload = SessionControl{op=READY, nonce=…}.serialize(), no string.
}
```

When INITIATOR receives `session_ready`: cancels tie-break watchdog, marks session active, confirms in `SessionConfirmationTracker`, drains pending queue.

## Tie-Break (Simultaneous Init)

```kotlin
if (DeviceIdOrdering.isNaturalInitiator(myId, peerId)) {
    // I WIN → INITIATOR role
    sendSessionResetInit(peerId)
    startTieBreakWatchdog(peerId)
} else {
    // I LOSE → RESPONDER role
    startResponderFallback(peerId)
}
```

### SESSION_RESET_INIT (atomic)

Already typed (`content_type = ContentType.SESSION_RESET_INIT` = 24). Unlike ping/ready this
carries a **real** X3DH first-ratchet payload (msgNum=0), so the consumer must NOT discard the
payload — only the post-init sentinel inner is dropped. See
[Session-Control Message Format](#session-control-message-format-typed-binary--do-this-not-magic-strings).

```kotlin
suspend fun sendSessionResetInit(userId: ServerUserId) {
    val sriContent = "__session_reset_init_${UUID.randomUUID()}__"
    val payload = outboundSessionService.encryptSessionControl(
        plaintext = sriContent, messageId = UUID.randomUUID().toString(),
        recipientId = userId
    )
    messagingServiceClient.sendMessage(/* ... */, contentType = ContentType.SESSION_RESET_INIT)
}
```

### Watchdog Timers

- **Tie-Break Watchdog** (30s): if no confirmation, re-prewarm + re-send SESSION_RESET_INIT
- **Responder Fallback** (60s): if no init from peer, take INITIATOR role

### Session Confirmation

```kotlin
class SessionConfirmationTracker {
    private val pendingSessions = mutableSetOf<ServerUserId>()
    fun markPending(userId: ServerUserId) { pendingSessions.add(userId) }
    fun markConfirmed(userId: ServerUserId) { pendingSessions.remove(userId) }
    fun isPending(userId: ServerUserId): Boolean = pendingSessions.contains(userId)
}
```

While session is pending, outgoing messages are buffered to prevent ratchet desync.

## Stale SPK Handling

```kotlin
suspend fun initializeSessionProactively(
    userId: ServerUserId, onSuccess: () -> Unit, onFailure: (Throwable) -> Unit
) {
    val staleSPKMaxRetries = 2
    val staleSPKRetryDelay = 60.seconds
    val staleSPKFastFailDays = 10.25
    for (attempt in 0..staleSPKMaxRetries) {
        if (attempt > 0) delay(staleSPKRetryDelay)
        try {
            val bundle = fetchPublicKeyWithRetry(userId)
            initializeSession(userId, bundle, deleteExisting = true)
            onSuccess(); return
        } catch (e: SessionError.PeerSpkStale) {
            if (attempt < staleSPKMaxRetries && e.ageDays < staleSPKFastFailDays) continue
            break
        } catch (e: Throwable) { break }
    }
    onFailure(lastError ?: NetworkException("Connection failed"))
}
```

## Error Types

```kotlin
sealed class SessionError : Exception() {
    data class StaleSpkBundle(val receivedEpoch: UInt, val knownEpoch: UInt) : SessionError()
    data class PeerSpkStale(val ageDays: Double) : SessionError()
    object KyberEpochRequired : SessionError()
    data class PqOtpkMissing(val keyId: UInt) : SessionError()
    object CoreNotInitialized : SessionError()
    object SessionNotFound : SessionError()
}
```

## iOS Anti-patterns Fixed

| iOS Problem | Android Fix |
|---|---|
| `initializeSessionProactively` uses callbacks | `suspend fun` with proper error propagation |
| SPK stale retry mixed with network retry | Separate `SessionError.PeerSpkStale` |
| Tie-break watchdog uses `Task` without cancellation | `CoroutineScope` with `Job` cancellation |
| Session ping/ready sent as raw strings | Structured `ContentType` enum |
| `SessionConfirmationTracker` is singleton | DI-injected, scoped to SessionController |

## Key Differences from iOS

| Aspect | iOS | Android |
|--------|-----|---------|
| Async model | `async/await` + `Task` | Coroutines (`suspend fun`) |
| Callbacks | `onSuccess: @escaping () -> Void` | `suspend fun` returns result |
| Error handling | `throw` + `try?` swallowing | Sealed class errors |
| Timer/timeout | `Task.sleep` + manual cancel | `withTimeoutOrNull` |
| State management | `@MainActor` dictionaries | `Mutex` + `StateFlow` |
| DI | Singletons everywhere | Hilt injection |

## Background Delivery (No GMS)

Android does not register an FCM token and must not depend on Google Play Services.
The base delivery path is the existing authenticated `MessageStream`, hosted by
`MessagingForegroundService` with a persistent, user-visible notification.

- The service starts after session restore/registration and stops on logout or when no
  identity can be restored.
- `START_STICKY` recreation restores the identity before restarting `MessagingRuntime`;
  the runtime mutex prevents a duplicate stream during concurrent startup.
- Android 13+ requests `POST_NOTIFICATIONS`; Android 14+ supplies the
  `remoteMessaging` service type. Android 11 uses the normal two-argument
  `startForeground` and notification-channel path.
- Cold start/reconnect still follows one pipeline: import CFE state → hydrate ACK store →
  drain `GetPendingMessages` → open the stream.
- UnifiedPush may be an optional accelerator in the future, but it must never become a
  prerequisite or a second source of protocol truth.

The remaining work is hardware validation: process death, reboot, network loss/reconnect,
pending drain, Doze/battery behavior, and live iOS↔Android delivery. Call wake-up policy is
deferred with the WebRTC/Telecom slice; do not introduce FCM as a shortcut.

---

# 13. Auth Tokens — PASETO v4.public

> **Android реализует PASETO v4.public нативно — БЕЗ поддержки JWT.**
> Полная спецификация формата токенов, gRPC metadata, refresh-флоу и force-refresh
> migration-стратегии — в [`docs/TOKEN_AUTH.md`](TOKEN_AUTH.md). Ниже — краткая сводка.

> ⚠️ ** НЕ добавляйте JWT-парсер.** iOS несёт dual-format parser переходно, пока сервер
> не завершит миграцию. Android — greenfield, получает PASETO с первого дня. Любой
> токен, не начинающийся с `v4.public.`, должен трактоваться как ошибка → device re-auth.

---

## 13.1 Формат токена

```
v4.public.<payload>[.<footer>]
```

| Сегмент | Кодировка | Содержимое |
|---|---|---|
| `v4.public.` | literal | Header pre-auth |
| `<payload>`  | base64url (без padding) | `nonce(32 байта) \|\| message(JSON claims) \|\| signature(64 байта Ed25519)` |
| `.<footer>`  | base64url, опционально | Свободные метаданные (для auth-токенов не используется) |

Подпись Ed25519 вычисляется сервером над `"paseto.v4.public." || nonce || message`.
Клиент подпись **не верифицирует** — это делает сервер. Клиент только извлекает claims
для last-resort восстановления `userId` (см. §13.4).

### Claims

| Claim | Тип | Обязателен | Назначение |
|---|---|---|---|
| `sub` | string (UUID 36) | да | User ID (server UUID) |
| `jti` | string (UUID) | да | Token ID — для blocklist/revocation |
| `exp` | int64 (unix sec) | да | Expiration |
| `iat` | int64 (unix sec) | да | Issued at |
| `iss` | string | да | Issuer (`construct-server`) |
| `device_id` | string | нет | Device identifier (32-char hex) |

---

## 13.2 Хранение и доставка

### Хранение

Токены хранятся как **opaque-строки** — клиент НЕ парсит их на hot path. Платформенный
secure store:

- **Android**: `EncryptedSharedPreferences` или Keystore-backed. Никогда `SharedPreferences`
  (plaintext).
- **iOS**: Keychain (`AfterFirstUnlockThisDeviceOnly`).

`expires_at` (protobuf `AuthTokensResponse.expires_at`, int64 Unix sec) сохраняется
**вместе** с токенами и drive-ит расписание refresh. Не парсите `exp` из токена для
расчёта expiry — используйте protobuf-поле.

### gRPC metadata (каждый authenticated RPC)

```kotlin
Authorization: Bearer <access_token>
x-user-id:     <userId>        // из cached session state, НЕ re-parse per call
x-device-id:   <deviceId>      // из Keystore
```

**Unauthenticated RPCs** (НЕ несут auth headers): `GetPowChallenge`, `RegisterDevice`,
`AuthenticateDevice`, `RefreshToken`, `CheckUsernameAvailability`.

---

## 13.3 Refresh-флоу

- Refresh планируется на `expires_at - 5 минут`.
- **Single-flight `TokenRefreshCoordinator`** сериализует concurrent refresh-запросы,
  чтобы несколько UI-компонентов, hit-нувших `.unauthenticated` одновременно, не
  соревновались на одном refresh-токене (одна сторона получила бы "already used").
- На permanent failure (`revoked` / `already used` / `.unauthenticated` у refresh):
  wipe сохранённых токенов → **device re-auth** (PoW challenge + device signature).
  Messenger не имеет login/logout — identity восстанавливается из Ed25519
  signing-key устройства в Keystore.

### TTL (server-side)

- access token: **24 часа** (`ACCESS_TOKEN_TTL_HOURS`)
- refresh token: **90 дней** (`REFRESH_TOKEN_TTL_DAYS`)

---

## 13.4 Last-resort userId recovery

Если `userId` потерян из cached session state (Keystore-entry lost, install без full
device reset), клиент может восстановить его из `sub` claim токена. Это **единственная**
операция клиент-side parsing на hot path.

```kotlin
object TokenUtils {
    fun extractUserId(token: String): String? {
        if (!token.startsWith("v4.public.")) return null
        val payloadB64 = token.removePrefix("v4.public.").substringBefore('.')  // footer optional
        val payload = base64UrlDecode(payloadB64) ?: return null
        // nonce(32) + message(variable) + signature(64)
        if (payload.size <= 32 + 64) return null
        val message = payload.copyOfRange(32, payload.size - 64)
        val claims = runCatching { JSONObject(String(message, Charsets.UTF_8)) }.getOrNull() ?: return null
        return claims.optString("sub").takeIf { it.isNotEmpty() }
    }

    private fun base64UrlDecode(input: String): ByteArray? {
        val padded = input.replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        return runCatching { Base64.decode(padded, Base64.NO_WRAP) }.getOrNull()
    }
}
```

> Client-side signature verification не требуется — см. threat model в `TOKEN_AUTH.md` §3.4.

---

## 13.5 Format guard при load session

На app launch, после загрузки кэшированного токена, проверьте формат. Принимайте
только `v4.public.*`:

```kotlin
fun loadSessionToken() {
    accessToken = keyStore.loadAccessToken()
    refreshToken = keyStore.loadRefreshToken()
    userId = keyStore.loadUserId()

    if (accessToken != null && !accessToken!!.startsWith("v4.public.")) {
        Log.e(TAG, "Unexpected token format in cache — clearing session")
        clearSession()
        isSessionInvalidated = true
        return
    }
    syncAuthCache()
}
```

---

## 13.6 Force-refresh migration (JWT → PASETO)

Сервер мигрирует с RS256 JWT на PASETO v4.public. Messenger **не имеет** login/logout,
и принудительная инвалидация сессии может привести к потере аккаунта пользователем.
Поэтому миграция идёт через **force-refresh с заменой токена**, не через hard cutover:

1. **Сервер выдаёт PASETO** (после cutover). Новые login/device-init получают PASETO.
2. **Существующие JWT-сессии продолжают работать** — `verify_token` принимает оба
   формата в переходном окне.
3. **Force-refresh**: при очередном истечении access-токена клиент вызовет
   `RefreshToken` со старым JWT refresh-токеном. Сервер принимает его (dual verify),
   потребляет JWT refresh `jti`, возвращает **PASETO**-пару. Сессия теперь на PASETO.
4. **Окно ротации**: до `refresh_token_ttl_days` (90 дней) для естественного перехода.
   После — stale JWT refresh истёк, оставшиеся клиенты re-auth via device signature (PoW).
5. **Legacy JWT код удаляется** с сервера и iOS-клиента после завершения окна ротации
   и нулевого объёма JWT verify в течение недели.

**Android (этот репо) — greenfield, JWT не реализует.** Если Android-клиент получит
токен не `v4.public.*`, трактуйте как ошибку → device re-auth. Сервер не выдаёт JWT
новым клиентам после cutover.

---

## 13.7 Imports / proto

`AuthTokensResponse` определён в `shared/proto/services/auth_service.proto`:

```protobuf
message AuthTokensResponse {
    string access_token  = 2;
    string refresh_token = 3;
    int64  expires_at    = 4;   // unix sec — drive refresh schedule from THIS
}
message RefreshTokenRequest  { string refresh_token = 1; }
```

**Важно**: `expires_at` — авторитетный expiry. Не парсите `exp` claim токена для расчёта
expiry на клиенте — используйте protobuf-поле напрямую. Это делает клиентов robust к
server-side TTL changes.

---

## 13.8 Реализация (checklist)

- [ ] `TokenUtils.kt` — `extractUserId`, формат-детектор — см. §13.4
- [ ] `KeyStoreManager.kt` — secure storage для access/refresh tokens + userId + deviceId
- [ ] `AuthInterceptor.kt` — gRPC metadata injection (Bearer + x-user-id + x-device-id)
- [ ] `TokenRefreshCoordinator.kt` — single-flight refresh actor
- [ ] `AuthSessionManager.kt` — observable session state, `saveTokens`/`clearSession`
- [ ] `AuthService.kt` — gRPC client wrapper для RegisterDevice / AuthenticateDevice /
      RefreshToken
- [ ] Device re-auth fallback на permanent refresh failure
- [ ] Unit tests: `TokenUtilsTest` (extract sub; reject non-PASETO; malformed/too-short
      payload; bad JSON; token with footer), `AuthSessionManagerTest` (format guard),
      `TokenRefreshCoordinatorTest` (single-flight, permanent-invalid classification)

> Полный технический документ: [`docs/TOKEN_AUTH.md`](TOKEN_AUTH.md).
