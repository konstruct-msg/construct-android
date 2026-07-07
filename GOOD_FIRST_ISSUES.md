# Good First Issues

Список задач, с которых удобно начать знакомство с `construct-android`. Все они
маленькие, самодостаточные и не требуют глубокого погружения в крипто или сеть.

Перед началом работы прочитай:
- `AGENTS.md` — контекст и конвенции.
- `docs/ANDROID_ONBOARDING.md` §3–§5 — дизайн-система и компоненты.
- `README.md` — текущее состояние проекта.

---

## Как взять задачу

1. Напиши в чат/тикет, что берёшь issue, чтобы не дублироваться.
2. Создай ветку от `develop`.
3. Реализуй по аналогии с существующими компонентами.
4. Добавь `@Preview` для нового UI.
5. Запусти:
   ```bash
   rtk gradlew :app:compileDebugKotlin test --continue
   ```
6. Сделай PR в `develop`.

---

## ✅ Уже реализовано (справка)

**Дизайн-токены:** `CTColor`, `CTFont`, `CTSymbol`, `CTLayout`, `Spacing`, `CornerRadius`.

**UI-компоненты:**
`CTNavBar`, `CTTabBar`, `CTButton`, `CTTextField`, `CTSearchBar`,
`CTSectionGroup`, `CTSettingsSectionHeader`, `CTSettingsRow`, `CTSep`,
`CTSystemMessage`, `CTStatusBadge`, `CTAvatar`, `CTLogoView`,
`ConstructNavRow`, `ChatRow`.

**Экраны (skeleton / mock):**
`SplashScreen`, `OnboardingScreen`, `MainTabView`, `ChatsListScreen`,
`ChatScreen`, `SynapsScreen`, `CallsScreen`, `SettingsScreen`.

---

## 🟢 UI-компоненты (маленькие, изолированные)

### 1. `ConstructButtonRow`
**Файл:** `app/src/main/java/com/construct/messenger/ui/components/ConstructButtonRow.kt`

Строка-кнопка с иконкой слева и тайтлом, без chevron. По спеке
`docs/ANDROID_ONBOARDING.md` §4.19.

**API:**
```kotlin
ConstructButtonRow(
    icon: ImageVector,
    title: String,
    iconColor: Color = CTColor.accent,
    onClick: () -> Unit,
)
```

**Что сделать:**
- Скопировать структуру `ConstructNavRow`.
- Убрать trailing chevron.
- Добавить preview.

---

### 2. `ConstructActionRow`
**Файл:** `app/src/main/java/com/construct/messenger/ui/components/ConstructActionRow.kt`

Action-строка с ролью (`PRIMARY`, `ACCENT`, `SECONDARY`, `DESTRUCTIVE`,
`DISABLED`) и опциональным badge. По спеке §4.17.

**API:**
```kotlin
ConstructActionRow(
    icon: ImageVector,
    title: String,
    role: ConstructRowRole,
    badge: String? = null,
    isLoading: Boolean = false,
    onClick: () -> Unit,
)
```

**Что сделать:**
- Создать enum `ConstructRowRole`.
- Для `DISABLED` добавить "soon" badge и понизить alpha.
- Добавить preview со всеми ролями.

---

### 3. `CTModeSelector`
**Файл:** `app/src/main/java/com/construct/messenger/ui/components/CTModeSelector.kt`

Сегментированный контроль без скруглений, accent на выбранном. По спеке §4.15.

**API:**
```kotlin
@Composable
fun <T> CTModeSelector(
    selected: T,
    options: List<T>,
    labels: Map<T, String>,
    onSelection: (T) -> Unit,
)
```

**Что сделать:**
- Row с равными сегментами.
- Каждый сегмент — `Text` в `Box` с border.
- Выбранный сегмент: `CTColor.accent` фон + белый текст.
- Невыбранный: прозрачный фон + `CTColor.text`.

---

### 4. `ConnectionStatusIndicator`
**Файл:** `app/src/main/java/com/construct/messenger/ui/components/ConnectionStatusIndicator.kt`

Маленький индикатор соединения для `CTNavBar` чат-листа. По спеке §4.20.

**API:**
```kotlin
ConnectionStatusIndicator(status: ConnectionStatus)
```

**Статусы:** `CONNECTED` (accent dot), `CONNECTING` (pulsing / dim dot),
`DISCONNECTED` (danger dot).

---

### 5. `MessageBubble`
**Файл:** `app/src/main/java/com/construct/messenger/ui/components/MessageBubble.kt`

Бабл сообщения. По спеке §5.4.

**Что сделать:**
- Incoming: `CTColor.bgMsg` + 0.5dp border `CTColor.noise`.
- Outgoing: `CTColor.accent`.
- `RoundedCornerShape(10.dp)`.
- Поддержка длинного текста и timestamp внутри/снизу.

---

### 6. `MessageInputView`
**Файл:** `app/src/main/java/com/construct/messenger/ui/components/MessageInputView.kt`

Поле ввода сообщения с кнопками attach / mic / send. По спеке §5.4.

**Что сделать:**
- `CTTextField` по центру.
- Material-иконки: `Icons.Default.AttachFile`, `Icons.Default.Mic`,
  `Icons.Default.Send`.
- Кнопка send появляется только при непустом тексте.

---

## 🟡 Экраны (средние)

### 7. `AccountSettingsScreen`
**Файл:** `app/src/main/java/com/construct/messenger/ui/screens/settings/AccountSettingsScreen.kt`

Профиль пользователя. По спеке §5.6.

**Что сделать:**
- `CTNavBar` с заголовком `identity`.
- Аватар 100dp + имя / username.
- Секции: Identity, Account, Backup, Danger zone.
- Использовать `CTSectionGroup`, `ConstructNavRow`, `CTSettingsRow`.

---

### 8. `AppearanceSettingsScreen`
**Файл:** `app/src/main/java/com/construct/messenger/ui/screens/settings/AppearanceSettingsScreen.kt`

Выбор темы. По спеке §5.7.

**Что сделать:**
- `AppTheme` enum: `AUTOMATIC`, `LIGHT`, `DARK`.
- `CTSectionGroup` с radio-like строками.
- Галочка `Icons.Default.Check` для выбранной темы.
- "soon" badge для недоступных вариантов.

---

### 9. Доработать `ChatScreen`
**Файл:** `app/src/main/java/com/construct/messenger/ui/screens/chat/ChatScreen.kt`

Сейчас экран почти пустой. Добавить:
- Список сообщений (`LazyColumn` с mock-данными).
- `MessageBubble`.
- `MessageInputView` снизу.
- Скролл к последнему сообщению.

---

## 🔵 Данные / Mock

### 10. `MockMessagesRepository`
**Файл:** `app/src/main/java/com/construct/messenger/data/mock/MockMessagesRepository.kt`

Аналог `MockChatsRepository`, но для сообщений. Нужен для `ChatScreen`.

**Что сделать:**
- Создать `Message` model (если ещё не финализирован).
- `getMessages(chatId): Flow<List<Message>>`.
- `sendMessage(chatId, text)` — добавляет outgoing сообщение.
- Набор тестовых сообщений для `test_contact`.

---

### 11. `ChatViewModel`
**Файл:** `app/src/main/java/com/construct/messenger/viewmodel/ChatViewModel.kt`

ViewModel для экрана чата.

**Что сделать:**
- `@HiltViewModel`.
- Принимает `chatId` через `SavedStateHandle`.
- Экспонирует `messages: StateFlow<List<Message>>`.
- `sendMessage(text)` через `MockMessagesRepository`.

---

## 🟣 Локализация

### 12. Добавить недостающие русские строки
Проверить `app/src/main/res/values-ru/strings.xml` на полноту относительно
`values/strings.xml`. Добавить переводы для новых ключей.

### 13. Подготовить `values-ja/strings.xml`
Скопировать структуру `values/strings.xml` и добавить японские переводы для
ключевых экранов (onboarding, main, settings).

---

## ⚫ Тесты

### 14. UI-тесты для `CTLogoView`
Проверить, что `CTLogoView` рендерится с заданным tint.

### 15. Тесты для `ConstructNavRow`
Проверить, что `onClick` вызывается по тапу.

---

## Куда смотреть за примерами

- Новый компонент — по аналогии с `ConstructNavRow.kt` или `CTLogoView.kt`.
- Новый экран — по аналогии с `SettingsScreen.kt` или `ChatsListScreen.kt`.
- ViewModel + mock repository — по аналогии с `MainViewModel.kt` и
  `MockChatsRepository.kt`.
- Preview — обязательно для всех новых composable.

---

## Вопросы?

- iOS-канон: `construct-messenger/ConstructMessenger/`.
- Общая архитектура / крипто: `construct-docs/`.
- Быстрая проверка: `rtk gradlew :app:compileDebugKotlin test --continue`.
