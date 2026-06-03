# Konstruct Messenger — Android

Android-клиент privacy-first E2EE-мессенджера Construct. Kotlin + Jetpack Compose + Hilt.
Криптоядро — общий `construct-core` (Rust), подключается напрямую через UniFFI/JNI
(тот же путь, что и на iOS — **не** через `construct-engine`).

---

## Источник правды (канон)

При любых расхождениях между кодом, документами и реальностью **каноном считается
iOS-приложение** `construct-messenger`. Документы могли устареть; iOS-исходники — нет.

| Что | Где смотреть на iOS |
|---|---|
| Дизайн-система, токены, компоненты | `construct-messenger/ConstructMessenger/Utilities/ConstructTheme.swift` |
| Аватары | `.../Views/Components/MainAvatarView.swift` |
| Экраны | `.../Views/` |
| ViewModels / бизнес-логика | `.../ViewModels/` |
| Строки (i18n) | `.../en.lproj/`, `.../ru.lproj/`, `.../ja.lproj/` |
| Сеть, gRPC | `.../Networking/` |

Подробный перенос iOS → Android (с примерами на Kotlin) описан в
`[ANDROID_ONBOARDING.md]`. Используй его как карту, но значения
токенов и поведение периодически сверяй с iOS-исходником.

> ⚠️ Доки используют тестовый пакет `com.construct.messenger`. **Реальный пакет —
> `com.maxeliseyev.konstructmessenger`.** надо будет потом это всё свести к одному.

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

Пересборка под все ABI и генерация Kotlin-биндингов:

```bash
./build_crypto_lib.sh          # все таргеты
./build_crypto_lib.sh --arm64  # только arm64-v8a (устройство)
./build_crypto_lib.sh --x86    # только x86_64 (эмулятор)
```

Скрипт собирает Rust, мёрджит VEIL-символы и генерирует UniFFI Kotlin bindings.
**Сгенерированный биндинг-файл не редактируется руками** — всё через обёртку
`CryptoManager` (см. конвенции ниже).

gRPC-клиент из `.proto`:
репозиторий тут: https://github.com/konstruct-msg/construct-protos.git

```bash
./generate_grpc_kotlin.sh
```

---

## Карта проекта

```
construct-android/
├── app/src/main/
│   ├── java/com/maxeliseyev/konstructmessenger/
│   │   ├── MainActivity.kt
│   │   ├── ui/
│   │   │   ├── theme/        — дизайн-токены (Color, Type, Symbol, Theme)
│   │   │   ├── navigation/   — NavHost, Screen
│   │   │   └── screens/      — splash, onboarding, main (пока заглушки)
│   │   ├── (далее по плану: crypto/, data/, domain/, di/, viewmodel/, service/)
│   ├── jniLibs/<abi>/        — libconstruct_core.so
│   └── res/values/           — strings.xml, colors.xml, themes.xml
├── build_crypto_lib.sh       — сборка Rust-ядра + UniFFI bindings
├── generate_grpc_kotlin.sh   — генерация gRPC из .proto
├── AGENTS.md                 — контекст для AI-агентов
├── IMPLEMENTATION_PLAN.md    — план на 9 фаз (цель, не текущее состояние)
└── GOOD_FIRST_ISSUES.md      — с чего начать новому разработчику
```

> `IMPLEMENTATION_PLAN.md` описывает **целевую** архитектуру (крипто, gRPC, VEIL,
> WebRTC, FCM). Фактически реализован только UI-скелет — см.
> [`GOOD_FIRST_ISSUES.md`](GOOD_FIRST_ISSUES.md).

---

## Текущее состояние

Реализовано: тема (Compose), навигация (Splash → Onboarding → Main), экраны-заглушки.
**Не реализовано:** криптоядро-обёртка, gRPC, регистрация/сессии, хранилище (Room),
большинство компонентов и экранов из дизайн-системы, локализация.

Есть известные расхождения (Hilt без Application-класса, дрейф цветов, хардкод строк) —
они оформлены как первые задачи в [`GOOD_FIRST_ISSUES.md`](GOOD_FIRST_ISSUES.md).

---

## Конвенции

- **Compose-only**, без XML-лейаутов.
- **Все видимые строки** — через `stringResource` / `strings.xml`. Хардкода в UI нет.
  Новый ключ добавляется во **все** языки (`values/`, `values-ru/`).
- Nav-заголовки: текст в ресурсе обычный, `.uppercase()` и `letterSpacing` — в коде.
- **Все ViewModels** — `@HiltViewModel`, без ручных фабрик.
- **Крипто** — только через `CryptoManager`. UniFFI-биндинги из UI напрямую не дёргать.
- Локальное хранилище сообщений — Room.
- gRPC-канал — синглтон в сервисе, не пересоздаётся на каждый экран.
- Дизайн-токены и поведение **сверяются с iOS** — приложения должны выглядеть одинаково.
