# Konstruct Messenger — Android

Android-клиент privacy-first E2EE-мессенджера Construct. Kotlin + Jetpack Compose + Hilt.
Криптоядро — общий `construct-core` (Rust), подключается напрямую через UniFFI/JNI
(тот же путь, что и на iOS).

---

## Источник правды

При любых расхождениях между кодом, документами и реальностью **каноном считается
iOS-приложение** `construct-ios`. Документы могли устареть; iOS-исходники — нет.

| Что | Где смотреть на iOS |
|---|---|
| Дизайн-система, токены, компоненты | `construct-ios/ConstructMessenger/Utilities/ConstructTheme.swift` |
| Аватары | `.../Views/Components/MainAvatarView.swift` |
| Экраны | `.../Views/` |
| ViewModels / бизнес-логика | `.../ViewModels/` |
| Строки (i18n) | `.../en.lproj/`, `.../ru.lproj/`, `.../ja.lproj/` |
| Сеть, gRPC | `.../Networking/` |

Подробный перенос iOS → Android (с примерами на Kotlin) описан в
`[ANDROID_ONBOARDING.md]`. Используй его как карту, но значения
токенов и поведение периодически сверяй с iOS-исходником.

> Пакет приложения — **`com.construct.messenger`** (namespace + applicationId),

---

## Требования

- **JDK 21**
- **Android SDK** (compileSdk 35), minSdk 26
- **Gradle 9.3.1** (через `./gradlew`, скачивается автоматически)
- Для пересборки криптоядра: Rust + Android NDK + `uniffi-bindgen`
- Для генерации gRPC: `protoc`, `protoc-gen-grpc-kotlin`, `protoc-gen-java`

`local.properties` должен указывать `sdk.dir` (генерируется Android Studio).

---

## Сборка и запуск

```bash
./gradlew assembleDebug      # debug APK
./gradlew test               # unit-тесты (JVM)
./gradlew connectedAndroidTest  # инструментальные тесты (устройство/эмулятор)
./gradlew assembleRelease    # release APK
```

Запуск проще всего из Android Studio (Run ▶ на конфигурации `app`).

### Troubleshooting

- **`jlink executable ... does not exist` / `Could not resolve ... androidJdkImage`** —
  Gradle запущен на JRE (например, bundled JRE расширения VSCode «Red Hat Java»),
  а сборке нужен полноценный **JDK 21** (в JRE нет `jlink`). Убедись, что
  `JAVA_HOME` указывает на полный JDK 21 (`/opt/homebrew/opt/openjdk@21` или JBR
  из Android Studio), и сбрось залежавшийся демон: `./gradlew --stop`. Машинно-
  специфичный путь намеренно **не** коммитим в `gradle.properties`.
- **`Unable to strip ... libconstruct_core.so` / `libjnidispatch.so`** — это
  предупреждение, не ошибка; сборка проходит.

---

## Криптоядро (construct-core)

Нативная либа `libconstruct_core.so` лежит в `app/src/main/jniLibs/<abi>/`.
Сейчас в репозитории собран только `arm64-v8a`; для эмулятора нужен `x86_64`.

Пересборка под все ABI и генерация Kotlin-биндингов локально:

```bash
./build_crypto_lib.sh          # все таргеты
./build_crypto_lib.sh --arm64  # только arm64-v8a (устройство)
./build_crypto_lib.sh --x86    # только x86_64 (эмулятор)
```

Скрипт собирает Rust, мёрджит VEIL-символы и генерирует UniFFI Kotlin bindings.
**Сгенерированный биндинг-файл не редактируется руками** — всё через обёртку
`CryptoManager` (см. конвенции ниже).

> Сейчас ядро автоматически собирается на CI/CD в GitHub Actions тут: https://github.com/konstruct-msg/construct-core

Чтобы сгененрировать gRPC-клиент из `.proto` используй скрипт:

```bash
./generate_grpc_kotlin.sh
```
Protobuf-репозиторий тут: https://github.com/konstruct-msg/construct-protos.git

---

## Карта проекта

```
construct-android/
├── app/src/main/
│   ├── java/com/construct/messenger/
│   │   ├── MainActivity.kt
│   │   ├── KonstructApp.kt
│   │   ├── ui/
│   │   │   ├── components/   — reusable UI-компоненты (см. список ниже)
│   │   │   ├── navigation/   — NavHost, Screen
│   │   │   ├── screens/      — splash, onboarding, main, chats, chat, synaps, calls, settings
│   │   │   └── theme/        — дизайн-токены (Color, Type, Symbol, Theme, Layout)
│   │   ├── data/
│   │   │   ├── model/        — ChatSummary, Message, AuthState
│   │   │   └── mock/         — MockChatsRepository, MockAuthRepository
│   │   ├── viewmodel/        — MainViewModel, OnboardingViewModel, SplashViewModel
│   │   └── crypto/           — CryptoManager (обёртка над UniFFI; заглушка/скелет)
│   ├── jniLibs/<abi>/        — libconstruct_core.so
│   └── res/values/           — strings.xml (en, ru), themes.xml
├── build_crypto_lib.sh       — сборка Rust-ядра + UniFFI bindings
├── generate_grpc_kotlin.sh   — генерация gRPC из .proto
├── AGENTS.md                 — контекст для AI-агентов
├── IMPLEMENTATION_PLAN.md    — план на 9 фаз (цель, не текущее состояние)
├── GOOD_FIRST_ISSUES.md      — с чего начать новому разработчику
└── docs/
    └── ANDROID_ONBOARDING.md — подробный перенос iOS → Android
```

> `IMPLEMENTATION_PLAN.md` описывает **целевую** архитектуру (крипто, gRPC, VEIL,
> WebRTC, FCM). Сейчас активно наращивается **mock-UI**: навигация, дизайн-система и
> экраны работают на тестовых данных без реального бэкенда.

---

## Текущее состояние

### Реализовано (UI / mock-уровень)

**Дизайн-система:**
- Токены: `CTColor`, `CTFont`, `CTSymbol`, `CTLayout`, `Spacing`, `CornerRadius`.
- Тема Jetpack Compose (dark/light).

**Reusable UI-компоненты:**
- `CTNavBar` — навигационная панель с заголовком и trailing action.
- `CTTabBar` — кастомный таб-бар (устаревающий, предпочтителен Material3 `NavigationBar`).
- `CTButton` — основная кнопка.
- `CTTextField` — поле ввода.
- `CTSearchBar` — строка поиска.
- `CTSectionGroup` / `CTSettingsSectionHeader` / `CTSettingsRow` — секции настроек.
- `CTSep` — ASCII-разделитель.
- `CTSystemMessage` — системное сообщение (`> text`).
- `CTStatusBadge` — Material-иконка статуса (ok/error/warning/on/off/busy/unknown).
- `CTAvatar` — круглый аватар с детерминированным accent-цветом и identicon.
- `CTLogoView` — логотип приложения (vector asset `ic_logo`).
- `ConstructNavRow` — строка навигации с иконкой и chevron.
- `ChatRow` — строка списка чатов.

**Экраны (Compose + ViewModel):**
- `SplashScreen` — заглушка запуска.
- `OnboardingScreen` — онбординг с username-полем.
- `MainTabView` — корневой таб-контейнер (Chats / Synaps / Calls / Settings) на Material3 `NavigationBar`.
- `ChatsListScreen` — список чатов с `CTSearchBar` и `ChatRow`.
- `ChatScreen` — скелет экрана чата.
- `SynapsScreen` — заглушка контактов.
- `CallsScreen` — заглушка звонков.
- `SettingsScreen` — скелет настроек.

**Данные / ViewModels:**
- `ChatSummary`, `Message`, `AuthState`.
- `MockChatsRepository` с тестовыми чатами.
- `MainViewModel`, `OnboardingViewModel`, `SplashViewModel`.

**Локализация:**
- `strings.xml` для `en` и `ru` (ключевые экраны).

### Не реализовано

- Реальная регистрация / сессии / PoW.
- Полноценная обёртка `CryptoManager` и интеграция с `construct-core`.
- gRPC-сервисы и сетевая подсистема.
- Room-хранилище (сейчас только mock-репозитории).
- Большинство экранов настроек (Account, Appearance, Network, Security).
- `MessageBubble`, `MessageInputView`, отправка сообщений.
- Push-уведомления (FCM), WebRTC/звонки, VEIL.
- Полная локализация (`ja` и оставшиеся ключи).

### Известные расхождения

- Hilt подключён, но `Application`-класс пока минимален.
- Некоторые компоненты ещё не перенесены из iOS (`ConstructActionRow`,
  `ConstructButtonRow`, `CTModeSelector`, `ConnectionStatusIndicator`).
- До появления реального бэкенда все экраны работают на mock-данных.

Последние изменения и задачи — в `GOOD_FIRST_ISSUES.md` и в истории коммитов.

---

## Конвенции (желательно)

- **Compose-only**, без XML-лейаутов.
- **Все видимые строки** — через `stringResource` / `strings.xml`. Хардкода в UI нет.
  Новый ключ добавляется во **все** языки (`values/`, `values-ru/`).
- Nav-заголовки: текст в ресурсе обычный, `.uppercase()` и `letterSpacing` — в коде.
- **Все ViewModels** — `@HiltViewModel`, без ручных фабрик.
- **Крипто** — только через `CryptoManager`. UniFFI-биндинги из UI напрямую не дёргать.
- Локальное хранилище сообщений — Room.
- gRPC-канал — синглтон в сервисе, не пересоздаётся на каждый экран.
- Дизайн-токены и поведение **сверяются с iOS** — приложения должны выглядеть одинаково.

## Trademark

**Konstruct™** / **Конструкт™** and the logo are trademarks of Maxim Eliseyev. The open-source
license on this code does **not** grant trademark rights — see [TRADEMARK.md](TRADEMARK.md).
Forks that distribute a modified version must rebrand.
