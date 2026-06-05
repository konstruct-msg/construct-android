# Construct Messenger — Android (Kotlin) Onboarding

> **Цель**: предоставить Android-разработчику полное понимание архитектуры, дизайн-системы, UI-компонентов и бизнес-логики iOS-приложения Construct Messenger для последующей реализации на Kotlin / Jetpack Compose.

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

---

## 1. Обзор архитектуры

Construct Messenger — privacy-first E2EE-мессенджер с терминальной / ASCII-эстетикой.

**Ключевые концепции:**
- **Тёмная тема** как основная (`#090909` фон), светлая — как альтернатива
- Все строки — через `NSLocalizedString` (нет хардкода)
- **SF Symbols** для интерактивных контролов (кнопки назад, закрыть, отправить)
- **ASCII-символы** (`[→]`, `[×]`, `>`, `✷`) для декоративных / структурных элементов
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

**Правило**: SF Symbols → интерактивные контролы. ASCII-символы → декоративные элементы.

```kotlin
// CTSymbol.kt
object CTSymbol {
    val star8   = "✷"

    // Navigation
    val back    = "[←]"
    val forward = "[→]"

    // Actions
    val add     = "[+]"
    val close   = "[×]"
    val send    = "[→]"
    val media   = "[◎]"
    val edit    = "[edit]"
    val retry   = "[↺]"
    val upload  = "[↑]"

    // Status
    val ok        = "[✓]"
    val delivered = "[✓✓]"
    val error     = "[!]"
    val online    = "[[ONLINE]]"

    // Tab bar
    val tabChats    = "[msg]"
    val tabSynaps   = "[syn]"
    val tabCalls    = "[tel]"
    val tabSettings = "[cfg]"

    // Separators
    fun thin(count: Int = 25)  = "- ".repeat(count)
    fun thick(count: Int = 25) = "= ".repeat(count)
}
```

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
    val navIconSize  = 20.dp    // SF Symbol размер для кнопок в nav bar
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

### 5.2 MainTabView (корневой экран)

- **ChatsListView** (Tab 0) — всегда загружен
- **SynapsView** (Tab 1) — lazyload
- **CallHistoryView** (Tab 2, опционально) — lazyload
- **SettingsView** (Tab 3) — lazyload

**ZStack pattern**: все табы существуют одновременно, opacity переключает видимость.
`allowsHitTesting` блокирует неактивные табы. `visitedTabs: Set<Int>` — ленивая загрузка.

Tab bar скрывается когда `isInChat || isInSettings == true`.

### 5.3 ChatsListView — список чатов

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
| `ServerUserId` | 36-char UUID `14f28d31-…` | Сервер | Все session addressing: local_user_id, contact_id, conversation_id |
| `CryptoDeviceId` | 32-char hex `6f5e37ac…` | deriveDeviceId(identityPublicKey) | Multi-device linking, QR коды |

```kotlin
// UserIdentity.kt
data class ServerUserId(val rawValue: String)    // UUID 36
data class CryptoDeviceId(val rawValue: String)   // hex 32
```

**Важно**: NEVER путать эти типы. `CryptoDeviceId` НЕ передаётся в Rust session layer.

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
    val mediaUrl: String? = null
)

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

├── security/                       // Crypto, Keychain
│   ├── KeyStoreManager.kt         // KeychainManager
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
