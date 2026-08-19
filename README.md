# Konstruct Messenger — Android

Android-клиент privacy-first E2EE-мессенджера Construct. Kotlin + Jetpack Compose + Hilt.
Криптоядро — общий `construct-core` (Rust), подключается напрямую через UniFFI/JNI
(тот же путь, что и на iOS).

---

## Источник правды

При любых расхождениях между кодом, документами и реальностью **каноном считается
iOS-приложение** `construct-ios`. Документы могли устареть; iOS-исходники — нет.

| Что                                | Где смотреть на iOS                                               |
|------------------------------------|-------------------------------------------------------------------|
| Дизайн-система, токены, компоненты | `construct-ios/ConstructMessenger/Utilities/ConstructTheme.swift` |
| Аватары                            | `.../Views/Components/MainAvatarView.swift`                       |
| Экраны                             | `.../Views/`                                                      |
| ViewModels / бизнес-логика         | `.../ViewModels/`                                                 |
| Строки (i18n)                      | `.../en.lproj/`, `.../ru.lproj/`, `.../ja.lproj/`                 |
| Сеть, gRPC                         | `.../Networking/`                                                 |

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
  из Android Studio), и сбрось залежавшийся демон: `./gradlew --stop`. Машинно-специфичный путь намеренно **не** коммитим в `gradle.properties`.
- **`Unable to strip ... libconstruct_core.so` / `libjnidispatch.so`** — это
  предупреждение, не ошибка; сборка проходит.

---

## Криптоядро (construct-core)

Нативная либа `libconstruct_core.so` лежит в `app/src/main/jniLibs/<abi>/`
(`arm64-v8a`, `armeabi-v7a`, `x86_64`).

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

`.proto` vendored в `app/src/main/proto/` (источник — `construct-protos`).
Синхронизация: `scripts/sync-protos.sh`. Stubs генерирует Gradle protobuf plugin
на сборке.

---

## Карта проекта

```
construct-android/
├── app/src/main/
│   ├── java/com/construct/messenger/
│   │   ├── MainActivity.kt / KonstructApp.kt
│   │   ├── ui/               — Compose (screens, components, theme)
│   │   ├── viewmodel/        — Splash, Onboarding, Orientation, Main, Chat, Synaps
│   │   ├── data/             — gRPC, Room, repositories (не моки)
│   │   ├── domain/usecase/   — register, login, upload prekeys, send
│   │   ├── service/          — MessagingRuntime, SessionManager, router/processor
│   │   ├── crypto/           — CryptoManager (UniFFI; не вызывать из UI)
│   │   ├── invite/           — device-minted v5
│   │   └── stealth/          — sealed sender (fail-closed on send)
│   ├── jniLibs/<abi>/        — libconstruct_core.so (arm64, armeabi-v7a, x86_64)
│   └── res/values/           — strings.xml (en, ru)
├── scripts/sync-protos.sh    — копия construct-protos → app/src/main/proto/
├── AGENTS.md
├── GOOD_FIRST_ISSUES.md      — UI-задачи после 1:1 slice
└── docs/
    ├── IMPLEMENTATION_PLAN.md
    ├── ANDROID_ONBOARDING.md
    ├── GRPC_LAYER.md
    └── WIRE_FORMAT_RULES.md
```

> `docs/IMPLEMENTATION_PLAN.md` — целевые фазы **и** актуальные статусы (обновлено
> 2026-08-19). 1:1 текст по production gRPC уже в дереве; VEIL / FCM / звонки /
> recovery — нет.

---

## Текущее состояние (2026-08-19, `develop` @ `46f515d`)

Рабочий срез — **1:1 текст по production gRPC**. UI ходит только в репозитории.

### Протокол

- Cold start: `AuthRepository.restoreSession` → `MessagingRuntime` (import CFE-сессий, hydrate ACK, drain pending, стрим).
- Приём: `MessageRouter` → CFE `handleEvent` → `ProcessorEffectsImpl` → Room.
- Отправка: `SendMessageUseCase` (MessageContent → KNST → CFE OutgoingMessage). Stealth fail-closed. Identified конверт без `conversation_id`.
- Контакты: mint v5 (QR ttl=300, link=43200), paste/`konstruct://add`, `AcceptInvite` / `RevokeInvite`.
- OTPK upload после регистрации. `GetPreKeyBundle` на verify инвайта — `consume_one_time_prekey=false`.

### UI

- Онбординг + Orientation; табы Chats / Synaps / Calls / Settings.
- Список чатов из Room; пустой CTA открывает Synaps.
- Чат: пузыри + composer, `ChatViewModel` observe/send.
- Synaps: share invite, paste accept, список контактов (не honeycomb).
- Дизайн-токены и CT*-компоненты, включая `MessageBubble` / `MessageInputView`.

### Не сделано

- Heal / END_SESSION на проводе; хендлеры session-control (роутер уже классифицирует 21/24/25/26).
- VEIL, FCM, WebRTC/звонки, BIP39 recovery, media, MLS.
- FindUser / contact requests; QR-экран инвайта; honeycomb Synaps.
- Экраны настроек (Account, Appearance, Network, Security).
- `ja`. Live iOS↔Android interop не гоняли. Эмуляторный smoke упёрся в отсутствующий system image.

Моки (`data/mock/`) остались для тестов; Hilt биндит `*Impl`.

Дальше — `GOOD_FIRST_ISSUES.md` и `docs/IMPLEMENTATION_PLAN.md`.

---

## Конвенции (желательно)

- **Compose-only**, без XML-лейаутов.
- **Все видимые строки** — через `stringResource` / `strings.xml`. Хардкода в UI нет.
  Новый ключ добавляется во **все** языки (`values/`, `values-ru/`).
- Nav-заголовки: текст в ресурсе обычный, `.uppercase()` и `letterSpacing` — в коде.
- **Все ViewModels** — `@HiltViewModel`, без ручных фабрик.
- **Крипто** — только через `CryptoManager`. UniFFI-биндинги из UI напрямую не дёргать.
- **UI / ViewModel** — только репозитории. Не импортировать `data/api`, `crypto`, `stealth`, proto envelopes.
- Локальное хранилище сообщений — Room.
- gRPC — `GrpcClient` (два канала). Cold start — `MessagingRuntime`.
- Дизайн-токены и поведение **сверяются с iOS** — приложения должны выглядеть одинаково.
