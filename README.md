# Konstruct Messenger — Android

Android-клиент privacy-first E2EE-мессенджера Konstruct. Kotlin + Jetpack Compose + Hilt.

**Новый здесь?** Порядок чтения — `AGENTS.md` → `docs/IMPLEMENTATION_PLAN.md` (единственный план:
что сделано, что брать) → этот файл → документ своей области. Первая задача —
`GOOD_FIRST_ISSUES.md`.
Криптоядро — общий `construct-core` (Rust), подключается напрямую через UniFFI/JNI
(тот же путь, что и на iOS).

---

## Источник правды

При любых расхождениях между кодом, документами и реальностью **каноном считается
iOS-приложение** `construct-ios` (github.com/konstruct-msg/construct-ios; локальная папка может называться `construct-messenger`). Документы могли устареть; iOS-исходники — нет.

| Что                                | Где смотреть на iOS                                               |
|------------------------------------|-------------------------------------------------------------------|
| Дизайн-система, токены, компоненты | **не iOS** с 2026-10-08: Android — Material 3, канон — Figma-файл дизайнера (`AGENTS.md` → Design System) |
| Аватары                            | `.../Views/Components/MainAvatarView.swift`                       |
| Экраны                             | `.../Views/`                                                      |
| ViewModels / бизнес-логика         | `.../ViewModels/`                                                 |
| Строки (i18n)                      | `.../en.lproj/`, `.../ru.lproj/`, `.../ja.lproj/`                 |
| Сеть, gRPC                         | `.../Networking/`                                                 |

Подробный перенос iOS → Android (с примерами на Kotlin) описан в
`docs/ANDROID_ONBOARDING.md`. Используй его как карту, но значения
токенов и поведение периодически сверяй с iOS-исходником.

> Пакет приложения — **`com.construct.messenger`** (namespace + applicationId),

---

## Требования

- **JDK 21**
- **Android SDK** (compileSdk 35), minSdk 26
- **Gradle 9.5.0** (через `./gradlew`, скачивается автоматически)
- Для пересборки криптоядра: Rust + Android NDK + `uniffi-bindgen`
- gRPC-стабы генерирует Gradle-плагин сам. На Apple Silicon нужен **Rosetta**
  (`softwareupdate --install-rosetta`): плагин grpc-java с меткой `osx-aarch_64` на деле x86_64,
  и смена версии этого не меняет. Не запускай Gradle с `--rerun-tasks`.

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

Нативная либа `libconstruct_core.so` лежит в `app/src/main/jniLibs/<abi>/` и **не хранится в git**.
APK несёт только `arm64-v8a` (`coreAbis` в `app/build.gradle`, с 2026-10-01 — ради размера);
скрипт по-прежнему собирает и `armeabi-v7a`, `x86_64`. После клона её нет, и сборка
остановится на `checkCoreLibrary`. Два способа получить её:

- без Rust: `scripts/fetch_core.sh` — скачивает опубликованную сборку, которую называет
  `construct-core.lock`, и раскладывает `.so`, `construct_core.kt` и ядро для unit-тестов;
- из исходников: `construct-core` рядом (`~/Code/construct-core`), Rust + NDK, затем скрипт ниже.

`construct-core.lock` называет версию ядра, с которой собирается приложение; `checkCoreLibrary`
сверяет её со штампом внутри каждой `.so`. Unit-тесты на JVM вызывают **настоящее** ядро — ту же
сборку для этой машины (`app/src/test/host/<os>-<cpu>/`, тоже не в git); `checkHostCoreLibrary`
сверяет и его штамп.

Пересборка под все ABI и генерация Kotlin-биндингов локально:

```bash
./build_crypto_lib.sh          # все таргеты
./build_crypto_lib.sh --arm64  # только arm64-v8a (устройство)
./build_crypto_lib.sh --x86    # только x86_64 (эмулятор)
```

Скрипт собирает Rust (VEIL входит в `.so`, но из Kotlin пока не вызывается) и генерирует UniFFI Kotlin bindings.
**Сгенерированный биндинг-файл не редактируется руками** — всё через обёртку
`CryptoManager` (см. конвенции ниже).

Если NDK на машине новее того, что прописан в `construct-core/.cargo/config.toml`, укажи
линкер через `CARGO_TARGET_<TRIPLE>_LINKER` / `_AR` вместо правки конфига ядра.

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
│   │   ├── viewmodel/        — по одному на экран (@HiltViewModel)
│   │   ├── data/             — gRPC, Room, repositories (не моки)
│   │   ├── domain/usecase/   — register, login, prekeys, send/resend, receiving open
│   │   ├── service/          — MessagingRuntime, SessionManager, router/processor
│   │   ├── crypto/           — CryptoManager (UniFFI; не вызывать из UI)
│   │   ├── invite/           — device-minted v5
│   │   └── stealth/          — sealed sender (fail-closed on send)
│   ├── jniLibs/<abi>/        — libconstruct_core.so (не в git, см. выше)
│   └── res/values/           — strings.xml (en, ru)
├── scripts/sync-protos.sh    — копия construct-protos → app/src/main/proto/
├── AGENTS.md
├── construct-core.lock       — версия ядра, с которой собирается приложение
├── GOOD_FIRST_ISSUES.md      — первые задачи
└── docs/
    ├── IMPLEMENTATION_PLAN.md — единственный план и статус
    ├── SESSIONS.md           — сессии: что делает ядро, что Android
    ├── STAND.md              — живой стенд Android↔iOS
    ├── ANDROID_ONBOARDING.md — канон дизайна и справочник
    ├── API_CRYPTO_GUIDE.md / CRYPTO_CORE.md / FFI_BINARY_FORMAT.md
    ├── GRPC_LAYER.md / TOKEN_AUTH.md
    └── WIRE_FORMAT_RULES.md  — что можно класть на провод
```

---

## Текущее состояние

Не ведётся здесь, чтобы не расходиться с планом: **`docs/IMPLEMENTATION_PLAN.md` §2–§3.**
Одной строкой (2026-09-28, версия 0.2.0): 1:1 текст по production gRPC, мультидевайс, sealed
sender, PQXDH v2, доставка без GMS; сессии обновляются отправкой, нечитаемое сообщение получает
DECRYPTION_ERROR (END_SESSION больше нет). Нет VEIL, звонков, медиа, групп, восстановления.

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
