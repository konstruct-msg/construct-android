# Construct Messenger — Android Implementation Guide

> **Цель**: предоставить Android-разработчику полное понимание архитектуры, дизайн-системы, UI-компонентов, бизнес-логики и крипто-протокола для реализации на Kotlin / Jetpack Compose.
>
> **Статус реализации — не здесь.** Это справочник, не трекер. Дизайн-часть (§3–4) описывает
> текущие CT-токены и компоненты, а не цель: с 2026-10-08 Android переходит на Material 3, канон —
> Figma-файл дизайнера, не iOS (`AGENTS.md` → Design System). Что сделано и что дальше — `docs/IMPLEMENTATION_PLAN.md`; сессии — `docs/SESSIONS.md`.
> Этот файл — единственная копия: версия в хранилище `construct-docs` ссылается сюда.
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
   - 3.5 [Сетка, иконки / CTSpace, CTIcon](#35-сетка-иконки--ctspace-cticon)
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
   - 4.12 [CTSep / CTRowDivider — разделитель (удалены)](#412-ctsep--ctrowdivider--разделитель-удалены-2026-10-09)
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
11. [Сессии](#11-сессии)
12. [Предключи и доставка](#12-предключи-и-доставка)
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

> **Текущее состояние, не цель (2026-10-08).** Android переходит на Material 3: цвета — роли
> `ColorScheme`, шрифты — `Typography`, элементы управления — компоненты Material; источник —
> Figma-файл дизайнера, iOS больше не канон внешнего вида. Остаются JetBrains Mono и тёмная тема по
> умолчанию. Таблицы ниже верны, пока миграция их не заменит; ссылки «Канон: iOS» и «iOS
> Reference» — история. Решение: `construct-docs/decisions/android-is-material-3.md`.

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

    // Статус «подключено»: точка соединения и статус сети. Только статус — не кнопка, не текст
    val online      = Color(0xFF30D158) // dark
    val onlineLight = Color(0xFF248A3D) // light: 3,9:1 на bgLight (0x34C759 даёт ~2,0)
}
```

**Dark/Light**: цвета должны адаптироваться к системной теме.

### 3.2 Типографика

```kotlin
// ui/theme/Type.kt
// JetBrains Mono вложен в приложение: res/font/jetbrains_mono_{regular,medium,semibold,bold}.ttf —
// те же четыре файла, что в iOS `Fonts/` (OFL 1.1, лицензия в assets/licenses/).
val CTFontFamily = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal), /* … */)
object CTFont {            // = iOS CTFont
    fun ui(size: Int, weight: FontWeight = FontWeight.Normal)   // кегль вне шкалы
    fun mono(size: Int, weight: FontWeight = FontWeight.Normal) // id, отпечатки, логи
    val title         // Bold 18 — заголовки экранов и листов
    val headline      // Bold 14 — заголовок корня вкладки, подзаголовки
    val body          // Regular 13 — строки, текст, пустые экраны
    val bodyEmphasis  // Bold 13 — кнопки, имя в списке чатов
    val secondary     // Regular 12 — значения, вторые строки, превью
    val caption       // Regular 11 — время, подвалы, подсказки
    val micro         // Regular 10 — время в пузыре, подписи кнопок звонка
    val badge         // Bold 11 — заголовки секций, счётчики
    @Composable fun message(size: Int) // текст сообщений: выбор читателя (System / JetBrains Mono) × размер
}
```

Экран выбирает **роль**, а не кегль; кегль вне шкалы — `CTFont.ui(size, weight)`. С 2026-10-07
`ctRegular(n)` / `ctBold(n)` удалены, как на iOS старые `CTFont.regular/medium/bold`: при них один и
тот же тип элемента набирался 2–5 кеглями. Системный шрифт — только в тексте сообщений
(`CTFont.message`) и в строке «системный» выбора шрифта в Оформлении. Живая сверка ролей iOS ↔ Android —
документ «Konstruct: типографика iOS и Android» (ссылка в vault, TODO 122).

**Частые места:**
- `CTFont.ui(17, FontWeight.SemiBold)` — заголовок `CTNavBar` подэкрана, без разрядки
- `CTFont.headline` + `letterSpacing(4.sp)` + капс — шапка корня вкладки (Settings, Chats, Synaps)
- `CTFont.body` — текст в строках настроек
- `CTFont.badge` — > SECTION заголовки
- `CTFont.caption` — таймстемпы, метаданные
- `CTFont.ui(16, FontWeight.Bold)` — заголовки в `ConstructActionRow` и строках выбора

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
    // Разделители — Material `HorizontalDivider` (ASCII-линии удалены 2026-10-09)
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

### 3.5 Сетка, иконки / CTSpace, CTIcon

```kotlin
// ui/theme/Dimens.kt — = iOS CTSpace / CTIcon / CTLayout
object CTSpace { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp }

object CTIcon {            // размер иконки — ступень шкалы, не число по месту
    val caption = 12.dp    // рядом с подписью: плашки, статус, замок в строке
    val row     = 16.dp    // в строке списка
    val nav     = 20.dp    // действия в панели навигации
    val navLg   = 22.dp    // выделенное действие, кнопка шапки чата
    val control = 24.dp    // в круглой кнопке: звонок, панель медиа
    val overlay = 32.dp    // поверх медиа: воспроизвести, скачать, повторить
    val hero    = 48.dp    // пустой экран, единственный символ экрана
}

object CTLayout {
    val edgePad      = CTSpace.m    // горизонтальный padding
    val navVPad      = 11.dp        // вертикальный padding nav bar
    val navBarHeight = 44.dp        // фикс. высота nav bar
    val navIconSize  = CTIcon.nav   // иконка кнопки в nav bar
    val inlinePad    = CTSpace.s
    val sectionGap   = CTSpace.l
    // …
}
```

Поверх картинки (звонок, просмотр медиа, камера) — не цвета темы, а `CTColor.onMedia`, `onMediaDim`,
`mediaScrim`, `mediaControl`, `mediaControlOn`, `answer` (= iOS `Color.CT`) и `mediaGround` (чёрная
подложка медиа и камеры), `mediaBadge` (плашка на картинке, 55 %), `onMediaControlOn`. Ещё: `onFill`
(значок на залитой accent/danger кнопке), `scrim` (затемнение поверх приложения), `delivered` (зелёная
галочка), `qrPaper`/`qrInk`/`qrInkSoft` (QR всегда тёмный на белом), `fieldStroke` (рамка поиска).
С 2026-10-07 вне `ui/theme` цвет-литерал один — цвет контура в `TabIcons`, который перекрывает tint.

Отступ, равный ступени, пишется через `CTSpace`; значения вне шкалы (6, 10, 2, 14, 20 …) пока остаются
числами — решение о шкале общее с iOS (2026-10-07).

`scripts/check_ui_tokens.sh` (часть `verify.sh`, значит и CI) считает размеры, кегли, системный
шрифт, отступы и цвета, заданные мимо токенов, и падает, если счётчик вырос. Миграция, которая его понижает,
понижает базовую линию в том же коммите.

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
- Title (подэкран): как задан, `CTFont.ui(17, .semibold)`, без разрядки — iOS снял капс и
  `tracking(4)` со всех экранов («читалось как машинная метка, а не имя экрана»)
- Корень вкладки (без back): капс, `bold(14)`, `tracking(4)`, без нижней линии (шапка `SettingsView`)
- Back: SF Symbol `chevron.backward.circle.fill` 22pt в accent → на Android акцентный круг со
  шевроном, вырезанным цветом фона
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

### 4.3 CTButton — кнопка (удалён 2026-10-08)

Заменён на Material `Button` (`docs/MATERIAL3_MIGRATION.md`, шаг 2): `Button(onClick, Modifier.fillMaxWidth()) { Text(label) }`.
Цвета, форма и шрифт — из темы (`primary`/`onPrimary`, `labelLarge`). Разрушительное действие —
`ButtonDefaults.buttonColors(containerColor = colorScheme.error, contentColor = colorScheme.onError)`.

### 4.4 CTTextField — поле ввода (удалён 2026-10-08)

Заменён на Material `OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), placeholder = { Text(…) }, singleLine = true)`
(`docs/MATERIAL3_MIGRATION.md`, шаг 2). Шрифт — `bodyLarge` (14, как у CT-полей), рамка `outline`,
в фокусе `primary`. По центру (алиас в онбординге): `textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center)`.

### 4.5 CTSearchBar — строка поиска (удалён 2026-10-08)

Заменён на `FilterSearchBar` (`ui/components/FilterSearchBar.kt`): свёрнутый Material `SearchBar` с
`SearchBarDefaults.InputField`, иконка поиска слева, очистка справа, когда что-то введено. Фильтрует
список на месте (чаты, Synaps) и никогда не раскрывается в экран поиска.

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

### 4.8 CTSettingsRow — строка настроек (на Material `ListItem` с 2026-10-08)

Сигнатура прежняя (`label`, `value`, `icon`, `status`, `disclosure`, `isAction`, `isDestructive`);
внутри — Material `ListItem` с прозрачным контейнером: высота от 56 dp, цвета ролей (`onSurface`,
`onSurfaceVariant`, `error`, `primary`). Обернуть `clickable`-модификатором для нажатия.

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

### 4.12 CTSep / CTRowDivider — разделитель (удалены 2026-10-09)

Material `HorizontalDivider` (цвет `outlineVariant`, толщина Material). Между строками в карточке —
во всю ширину; в списках с аватарами (чаты, звонки) — с отступом до колонки текста.

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

### 4.15 CTModeSelector — сегментированный контроль (удалён 2026-10-08)

Заменён на Material `SingleChoiceSegmentedButtonRow` + `SegmentedButton`
(`docs/MATERIAL3_MIGRATION.md`, шаг 2): «Сеть» → VEIL, «Звонки» → фильтр. Ширина — по самой длинной
подписи (Material), выбранный сегмент — галочка и `secondaryContainer` (акцент поверх фона).

### 4.16 CTLogoView — логотип

```kotlin
@Composable
fun CTLogoView(
    size: Dp = 100.dp,
    color: Color = Color.CT.text
)
```

### 4.17 ConstructActionRow — action-строка (удалён 2026-10-08)

Единственное место — «Связанные устройства» → выход: теперь `OutlinedButton` в `error` с иконкой.

### 4.18 ConstructNavRow — строка навигации (удалён 2026-10-08, не использовался)

### 4.19 ConstructButtonRow — строка-кнопка (удалён 2026-10-08, не использовался)

### 4.20 ConnectionStatusIndicator — индикатор соединения

```kotlin
@Composable
fun ConnectionStatusIndicator() {
    // Маленькая точка или текст в CTNavBar чат-листа
    // connected = online (зелёная, ровная, не гаснет), disconnected = danger, connecting = textDim
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
> `MessageInputView`, observe/send через `MessagesRepository`. Ответ на сообщение
> едет как iOS: `QuotedMessage` внутри зашифрованного `TextMessage` (id, превью
> ≤200, media type для фото/голоса/файла), полоска в пузыре, долгое нажатие →
> ответить / копировать / изменить своё / удалить у себя, тап по полоске
> прокручивает к исходному. Правка — `MessageContent.edit` внутри того же
> шифротекста; строка хранится под UUID заголовка KNST, на который правка
> ссылается. Удаление из меню локальное, как на iOS. Входящий
> `DELETE_SCOPE_EVERYONE` снимает строку автора и пузырём не становится.
> Нет поиска, пагинации, звонка, реакций, swipe-to-reply.

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
- Строки: `CTSettingsRow` + `HorizontalDivider()` между ними
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
    val replyPreview: String? = null, // wire text_preview, ≤200 chars
    val replyMediaType: String? = null, // proto MediaType name when the quote is media
    val isEdited: Boolean = false, // MessageContent.edit replaced text; timestamp stays
    val mediaType: MediaType? = null, // image, video, audio, file, voice
    val mediaUrl: String? = null,
    val contentType: Int = 0          // 0 = regular; control types are never persisted as visible rows
)
```

> **Control-message render guard.** A session-control signal must never appear in
> the transcript. Android handles outer 21/24 before chat persistence and filters
> decrypted KNST 25/26 in `IncomingPlaintext`. `MessageDao.observeChat` additionally
> restricts rows to `contentType = 0`; any future path that deliberately stores a
> control audit row must stamp the non-zero type. Android does not infer protocol
> state by matching plaintext magic strings.
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
│   ├── Dimens.kt                   // CTLayout, CTSpace, CTIcon, corner radius
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
│         │          │ │ ML-KEM-1024 · PQXDH v2      │ │  │
│  ┌──────▼───────┐  │ │ Previous states · Retire    │ │  │
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
о сессиях: это делает Rust CFE. `PeerDeviceRegistry` хранит server account→device
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

# 11. Сессии

**Актуально на 2026-09-28** (construct-core `0.20.0`). Полное описание того, что делает Android и
что делает ядро, — **`docs/SESSIONS.md`**. Здесь только то, что нужно для ориентации.

- Сессия — рэтчет между двумя **устройствами**; всё, что уходит в ядро, — `CryptoDeviceId`.
- **Сессия обновляется отправкой.** Любое сообщение с заголовком рукопожатия открывает новое
  состояние рядом с текущим; ядро хранит прежние состояния и продвигает то, которое расшифровало.
- **Первое сообщение открывается без сервера** — по ключу из сертификата отправителя.
- **Нечитаемое сообщение получает DECRYPTION_ERROR** (тип 28), который называет состояние. Автор
  выводит его из текущих, только если оно текущее, и переотправляет сообщение один раз.
- Ручной сброс, удаление чата или контакта и выход из аккаунта — **локальные**, пиру ничего не
  уходит.

До 2026-09-27 здесь стояли разделы 11–12 о контроллере жизненного цикла, ping/ready,
SESSION_RESET_INIT, тай-брейке, сторожевых таймерах, лечении и END_SESSION с его «защитой от
шторма». Всё это удалено из протокола (`decisions/sessions-renew-by-sending.md`). **Не
переносите это с iOS-кода старых ревизий и не восстанавливайте по памяти** — это ровно то, что
заменили. Прежний текст — в истории git этого файла.

# 12. Предключи и доставка

## One-time prekey privates survive the process — MANDATORY

The core holds OTPK privates in memory only, and `exportPrivateKeys()` does not include them.
Android lost every one on each restart until 2026-09-24 (`construct-android@914892f`), and the
next peer's X3DH failed with `OTPK id=… not found — sender used 4-DH but we cannot reproduce it`.
Same rules as iOS (`crypto_otpks`, `OtpkReplenishmentService.persistOtpks`):

1. **Persist before upload.** `exportOneTimePrekeys()` → `KeystoreManager.saveOneTimePrekeys`
   (synchronous `commit()`) after generating and *before* `UploadPreKeys`. Once public, a key
   may be used at any moment.
2. **Import before `setLocalUserId`** on login and session restore — the bootstrap core's OTPKs
   are carried into the orchestrator there. An undecodable blob is deleted, not ignored.
3. **Nothing persisted → replace the server pool** (`replace_existing = true`), never append:
   an append leaves the unanswerable keys in circulation.
4. **Re-persist after a receiving open consumes a key.**

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
