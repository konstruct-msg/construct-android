# Гайд для Android-разработчика: API (gRPC + protobuf) и крипто-ядро

> Практическое руководство по двум самым нетривиальным слоям клиента: сетевому
> (gRPC/protobuf поверх VEIL) и крипто-ядру (`construct-core` через UniFFI).
>
> **Канон — iOS.** Когда код/доки расходятся, источник истины — рабочая Swift-реализация
> в `~/Code/construct-messenger`. Этот гайд написан по её разбору и по реальному API
> сгенерированных биндингов, а не по плану. Ссылки на Swift-файлы даны намеренно —
> при сомнении смотри туда.
>
> **Актуальное состояние слоя (2026-09-18):** `docs/GRPC_LAYER.md` и
> `docs/IMPLEMENTATION_PLAN.md`. Этот гайд — разбор iOS API, не трекер статусов.
> Протосы vendored в `app/src/main/proto/` (`scripts/sync-protos.sh`); stubs
> генерит Gradle plugin, не `generate_grpc_kotlin.sh`. Android-репо — не «только
> UI»: runtime, send/receive и v5 invites уже в дереве.
>
> Связанные доки: `IMPLEMENTATION_PLAN.md` (Phases 1, 2.3, 5), `AGENTS.md`,
> `ANDROID_ONBOARDING.md`, `CRYPTO_CORE.md`, `SESSIONS.md`,
> `GRPC_LAYER.md`, `WIRE_FORMAT_RULES.md`

---

## Карта репозиториев

Клиент собирается из **трёх** репозиториев. Kotlin — оболочка вокруг Rust core
и gRPC; криптографию на Kotlin не пишем.

| Репо | Что даёт | Где ожидается |
|------|----------|---------------|
| `construct-android` | этот проект: Kotlin/Compose, обёртки | — |
| `construct-core` | Rust-крипто-ядро (источник `.so` + UniFFI-биндингов) | `construct-core` | https://github.com/konstruct-msg/construct-core
| `construct-protos` | `.proto`-контракты API (источник gRPC-кода) | `construct-protos` | https://github.com/konstruct-msg/construct-protos

Оба внешних репо подтягиваются скриптами сборки (см. ниже). Если их нет рядом —
скрипты упадут с понятной ошибкой. Cейчас работает CI на GitHub который должен при обновлении ядра собирать новую версию библиотек, при обновлении Protobuf-файлов биндинги придётся перегенерировать вручную.

---

# Часть 1. API: gRPC + Protobuf

## 1.1 Откуда берётся код API

`.proto`-файлы **не лежат** в Android-репо. Они в `construct-protos`. Kotlin/Java-код
из них генерится скриптом:

```bash
./generate_grpc_kotlin.sh          # генерация
./generate_grpc_kotlin.sh --clean  # очистить output и пересобрать
./generate_grpc_kotlin.sh --check  # только проверить, что стоят зависимости
```

Что делает скрипт:

1. Находит `protoc` + `protoc-gen-grpc-kotlin` (см. шапку скрипта по установке —
   `brew install protobuf` и jar grpc-kotlin v1.4.1).
2. Берёт все `*.proto` из `$PROTOS_DIR` (`~/Code/construct-protos`).
3. Генерирует **Java** для messages, **Kotlin** DSL для messages и **gRPC Kotlin**
   stubs для сервисов.
4. Кладёт всё в:

```
app/src/main/java/com/construct/messenger/data/api/proto/
├── *.java, *.kt   ← сообщения (messages)
└── *Grpc*.kt      ← gRPC-стабы сервисов
```

5. Дописывает gRPC-зависимости в `app/build.gradle`, если их там ещё нет
   (`grpc-kotlin`, `grpc-okhttp`, `protobuf-kotlin` и т.д.).

> **Важно:** `proto/` — это сгенерированный код. **Не редактируй руками.** При
> изменении контрактов меняй `.proto` в `construct-protos` и перегенерируй.

## 1.2 Сервисы

Полный список (см. `IMPLEMENTATION_PLAN.md` §2.3). Каждый — отдельный gRPC-сервис:

| Сервис | RPC |
|--------|-----|
| `AuthService` | `RegisterDevice`, `Login`, `RefreshToken`, `Logout` |
| `DeviceService` | `GetDevices`, `RevokeDevice` |
| `KeyService` | `UploadKeyBundle`, `FetchKeyBundle`, `UploadOneTimePrekeys` |
| `MessagingService` | `SendMessage`, `MessageStream` (server-stream), `GetPendingMessages` |
| `UserService` | `GetProfile`, `UpdateProfile`, `SearchUsers` |
| `NotificationService` | `RegisterPushToken`, `UnregisterPushToken` |
| `SentinelService` | `GetCallCredentials` (TURN/WebRTC) |

В Android-слое каждый сервис заворачивается в свой Kotlin-класс
(`data/api/AuthService.kt`, `KeyService.kt`, `MessagingService.kt`,
`MessageStreamService.kt`), а канал общий — см. ниже.

## 1.3 Один канал на всё приложение

**Канал gRPC создаётся один раз и живёт в синглтоне** (`GrpcClient` под Hilt
`@Singleton`). Не создавай `ManagedChannel` на каждый экран/запрос — это убивает
переиспользование соединения и ломает стриминг.

iOS-аналог для образца — `GRPCChannelManager.swift` (`static let shared`). Из него
стоит перенести в Kotlin несколько неочевидных вещей:

- **Хост/порт конфигурируемы.** Дефолт `ams.konstruct.cc:443` (прямой TLS), но есть
  override через настройки (custom server). На iOS — `customHostKey`/`customPortKey`
  в `UserDefaults`; на Android — DataStore/SharedPrefs.
- **Канал инвалидируется при смене сети.** На переключении WiFi↔cellular / VPN
  старое TCP-соединение мертво — его надо проактивно сбрасывать, иначе первый
  RPC после переключения падает. iOS слушает `networkPathChanged` и вызывает
  `invalidatePersistentClient()`. На Android — `ConnectivityManager.NetworkCallback`.
- **Авторизация** идёт метаданными (`Authorization: Bearer <token>`) через gRPC
  interceptor/`CallOptions`, токен — из Keystore (см. `KeystoreManager`).

## 1.4 VEIL: маршрутизация (читать обязательно)

Это главная ловушка сетевого слоя. **Не пиши Kotlin-фолбэк между прямым
подключением и обходными мостами.**

Как это устроено (см. `IMPLEMENTATION_PLAN.md` §5.1 и Swift
`Networking/gRPC/VEIL/`):

- В нецензурируемой сети gRPC идёт **напрямую** на `ams.konstruct.cc:443`.
- При блокировках весь трафик заворачивается через **VEIL** (`construct-veil`) —
  обфускационный прокси-слой (obfs4 / WebTunnel / будущий veil-front).
- Гонку методов, выбор моста, backoff, latency-aware переключение и silent
  fallback делает **Rust** (happy-eyeballs координатор за FFI `veil_start`).
  **Не Kotlin.**

Роль Kotlin сведена к тонкой обёртке `VeilProxy.kt`:

```kotlin
// VeilProxy.kt — тонкая обёртка над Rust FFI, НЕ реализация роутинга
class VeilProxy(private val core: ClassicCryptoCore) {
    // 1. fetchManifest() -> дескрипторы мостов + ticket-бандлы
    // 2. core.veilStart(bundles)  ← Rust параллельно гоняет методы
    // 3. возвращает локальный TCP-порт; gRPC подключается к нему как h2c
}
```

Поток: получили манифест → отдали бандлы в Rust → Rust поднял локальный прокси-порт →
gRPC-канал нацелили на `localhost:<port>`. На iOS это `setDirectProxyPort()` в
`GRPCChannelManager` — когда порт задан, канал ходит через прокси; `nil` = прямой путь.

**Почему так:** на iOS когда-то были две параллельные реализации роутинга (Swift и
Rust), они разъехались и это вычистили. Android повторять эту ошибку не должен —
потребляем тот же Rust-координатор через UniFFI.

Детали, которые **остаются в Rust** и которые не надо переизобретать:

- параллельная гонка по `MethodId` (`obfs4`, `WebTunnel`, future `VeilFront`);
- `PersistentScores` — предпочтение метода по сети;
- silent fallback, backoff, переключение по латентности.

Адреса мостов **не хардкодим**: они приходят из подписанного манифеста
`.well-known/veil-bridges` при старте, с SPKI-пином как последним фолбэком.
Текущие obfs4/WebTunnel endpoints намеренно ротируются.

`TransportRouter.kt` (опционально) — это **зеркало** FSM Rust-роутера для UI/наблюдаемости,
а не вторая реализация маршрутизации.

## 1.5 Message Stream

Долгоживущий server-stream, живёт в фоновом сервисе (`service/`, см.
`MessageStreamService.kt`), не в экране:

```
Client → Server: Subscribe(user_id)
Server → Client: MessageEnvelope (поток)
Client → Server: Ack(message_id)

KeepAlive: каждые 30s
На дисконнект: reconnect → дренаж pending
```

---
---

# Часть 2. Крипто-ядро (`construct-core` через UniFFI)

Это самый нетривиальный слой. Прочитай раздел целиком до первого вызова крипто.

## 2.1 Из чего состоит интеграция (два артефакта)

UniFFI даёт **два** артефакта, оба генерятся скриптом `build_crypto_lib.sh`:

1. **Нативные библиотеки** — само ядро, скомпилированный Rust:
   ```
   app/src/main/jniLibs/arm64-v8a/libconstruct_core.so
   app/src/main/jniLibs/armeabi-v7a/libconstruct_core.so
   app/src/main/jniLibs/x86_64/libconstruct_core.so   ← эмулятор
   ```
2. **Сгенерированные Kotlin-биндинги** — обёртки над FFI:
   ```
   app/src/main/java/com/construct/messenger/crypto/uniffi/construct_core/construct_core.kt
   ```
   Пакет: `uniffi.construct_core`. **Не редактировать руками** — перегенерируется.

Биндинги грузят `.so` через **JNA** (не прямой JNI) — поэтому в `app/build.gradle`
есть зависимость JNA и `jniLibs.srcDirs`. В merged-сборке рядом окажется
`libjnidispatch.so` (это JNA, так и должно быть).

## 2.2 Как пересобрать ядро

```bash
./build_crypto_lib.sh          # все три таргета (arm64, armv7, x86_64)
./build_crypto_lib.sh --arm64  # только arm64-v8a
./build_crypto_lib.sh --x86    # только эмулятор
./build_crypto_lib.sh --clean  # cargo clean перед сборкой
./build_crypto_lib.sh --debug  # debug вместо release
```

Что делает скрипт:

1. Находит `cargo`, `uniffi-bindgen`, **Android NDK** (ищет в `~/Library/Android/sdk/ndk/...`).
2. Кросс-компилит `construct-core` под каждый таргет с фичами `android,post-quantum`.
   - Тонкость: использует **target-specific** `CC_<target>=`, а не generic `CC=` —
     иначе host build-scripts (`libsqlite3-sys` и пр.) пытаются собираться
     android-clang'ом и падают на `stdio.h not found`.
3. Копирует `.so` в `jniLibs/<abi>/`.
4. Генерирует UniFFI Kotlin-биндинги **только из arm64** `.so` (этого достаточно —
   API один на все ABI).

> Когда в `construct-veil` приедет `veil-front` (новый MethodId): пересобрать
> `.so`, перегенерировать биндинги, поднять флаг в манифесте — **нового Kotlin-кода
> роутинга не пишем**.

## 2.3 Два уровня ядра

В биндингах два публичных класса ядра. Понимать разницу обязательно:

| Класс | Когда | Что это |
|-------|-------|---------|
| `ClassicCryptoCore` | до логина (bootstrap) | низкоуровневое ядро: прямые `encryptMessage`/`decryptMessage`/`initSession`, генерация ключей |
| `OrchestratorCore` | после `setLocalUserId(accountId)` | высокоуровневая **event-driven CFE-машина** — основной рабочий объект; внутрь передаётся только `CryptoDeviceId` |

`OrchestratorCore` оборачивает `ClassicCryptoCore` и добавляет: ACK-стор, очередь
сообщений, ждущих открытия сессии, прежние состояния сессии и решение, какое из них
расшифровывает, ответ на нечитаемое сообщение (DECRYPTION_ERROR).
Именно через него идёт вся боевая работа после авторизации.

**Двухфазная инициализация** (канон — `CryptoManager.swift`):

1. При старте `CryptoCoreProvider.loadCore()` читает приватные ключи из защищённого
   хранилища (на iOS — Keychain, на Android — Keystore/EncryptedPrefs) и создаёт
   **`ClassicCryptoCore`** (`createCryptoCoreFromKeys(keys)`). Если ключей нет —
   ядро ещё не инициализировано (юзер не зарегистрирован).
2. Когда становится известен серверный account `userId` (после логина/регистрации) —
   `setLocalUserId(userId)` выводит локальный `CryptoDeviceId` из identity key и
   создаёт **`OrchestratorCore`** из тех же ключей
   (`createOrchestratorCoreFromKeys(keysData, cryptoDeviceId)`), импортирует OTPK,
   восстанавливает CFE-состояние, и `_bootstrapCore` обнуляется. Account→device
   mapping для peers хранится в `PeerDeviceRegistry`.

```kotlin
// Псевдокод по мотивам Swift CryptoManager
class CryptoManager {
    private var bootstrapCore: ClassicCryptoCore?   // фаза 1
    var orchestratorCore: OrchestratorCore? = null  // фаза 2 (рабочий)

    val isInitialized get() = orchestratorCore != null

    fun setLocalUserId(accountId: String) {
        val cryptoDeviceId = deriveDeviceId(identityPublic)
        orchestratorCore?.let { it.setLocalUserId(cryptoDeviceId); return }
        val keys = keystore.loadPrivateKeys() ?: bootstrapCore?.exportPrivateKeys() ?: return
        val core = createOrchestratorCoreFromKeys(keys, cryptoDeviceId)
        keystore.loadOtpks()?.let { core.importOneTimePrekeys(it) }
        // восстановить PQ-снапшот, ACK-стор, очередь ожидания из CFE
        orchestratorCore = core
        bootstrapCore = null
    }
}
```

> **Тонкость с `userId` (стоила iOS багов):** `local_user_id` для AEAD associated data
> ДОЛЖЕН быть серверным UUID (36 символов), а не device-hash (32 hex). Инициатор
> кладёт `local_user_id = serverUUID`, получатель кладёт `contact_id = serverUUID`
> того же лица — тогда swapped AD симметричен и расшифровка сходится. Перепутаешь —
> вечный AEAD failure.

## 2.4 CFE: общение с оркестратором через события, а не вызовы

Самое неинтуитивное. С `OrchestratorCore` ты, как правило, **не вызываешь
`encrypt`/`decrypt` напрямую**. Ты шлёшь ему **событие** и получаешь список
**действий**, которые обязан исполнить:

```kotlin
fun handleEvent(event: CfeIncomingEvent): List<CfeAction>
```

Это конечный автомат (CFE = Crypto FSM Engine). Логику переходов держит Rust; Kotlin
только подаёт события извне (пришло сообщение, юзер отправляет текст, сработал таймер)
и исполняет возвращённые действия (отправить по сети, сохранить в БД, показать в UI).

**Входящие события** (`CfeIncomingEvent`):

`MessageReceived`, `OutgoingMessage`, `OutgoingCallSignal`, `SessionInitCompleted`,
`AckReceived`, `KeyBundleFetched`, `TimerFired`, `AckDbResult`, `HeartbeatReceived`,
`NetworkReconnected`, `AppLaunched`, `DecryptionErrorReceived`. `ActiveChatChanged` ядро больше не принимает,
и Android его не слал.

**Действия** (`CfeAction`) — что Kotlin обязан выполнить в ответ (ядро `0.20.0`):

- сетевые: `SendEncryptedMessage`, `SendReceipt`, `SendDecryptionError` (готовый
  запечатанный payload типа 28 — отправить как есть);
- хранилище: `SaveToSecureStore`, `PersistAck`, `PruneAckStore`, `CheckAckInDb`.
  `PersistMessage` ядро убрало: расшифрованный текст пишет `onDecrypted`;
- маршрутизация: `DuplicateDropped` — сообщение уже обработано, пометить и сдвинуть
  курсор, квитанцию `delivered` не слать;
- UI/уведомления: `NotifyNewMessage`, `NotifySessionCreated`, `NotifyError`,
  `MarkMessageDelivered`, `MessageDecrypted`, `CallSignalDecrypted`;
- крипто/жизненный цикл: `DecryptMessage`, `EncryptMessage`, `InitSession`,
  `ArchiveSession`, `OpenReceiving`, `MessageQueuedPendingInit`, `SessionRetired`
  (текущее состояние выведено — следующая отправка откроет новое), `ResendMessage`
  (переотправить названное сообщение один раз), `ScheduleTimer`, `CancelTimer`;
- `OpenSession` — просьба ядра открыть сессию (апгрейд до PQXDH v2). Android пока
  только логирует её — главный открытый пробел, см. `SESSIONS.md` §8.

Heal, END_SESSION, SESSION_RESET_INIT, ping/ready и их действия удалены из ядра
27–28.09.2026 (`decisions/sessions-renew-by-sending.md`).

`ApplyPqContribution` удалён вместе с ML-KEM-768 (PQXDH v2): ML-KEM-1024 входит в начальный
ключ сессии, ядро декапсулирует само.

Поэтому «отправить сообщение» выглядит так: отдаёшь `OutgoingMessage` →
получаешь `SendEncryptedMessage` (+ возможно `ScheduleTimer`) →
исполняешь их. «Пришло сообщение»: `MessageReceived` → `MessageDecrypted` +
`SendReceipt` + `NotifyNewMessage`. Повтор уже обработанного — `DuplicateDropped`.

Образец обёртки — `CryptoManager.handleOrchestratorEvent` в Swift:

```kotlin
@Throws
fun handleOrchestratorEvent(event: CfeIncomingEvent, tag: String? = null): List<CfeAction> {
    require(isMainThread()) { "Orchestrator event off main thread" }  // см. 2.6
    coreLock.withLock {
        val core = orchestratorCore ?: throw CoreNotInitialized
        val actions = core.handleEvent(event)
        logOrchestratorEvent(event, actions, tag)   // логируй событие+действия
        return actions
    }
}
```

> Низкоуровневые `OrchestratorCore.encryptMessage(contactId, plaintext)` /
> `decryptMessage(...)` тоже существуют и нужны для отдельных путей (например
> хранилище, фоновая дешифровка), но **основной путь сообщений — через CFE-события**.

## 2.5 Бинарный пайплайн — обязателен (никакого JSON)

Всё, что пересекает FFI и идёт в хранилище/сеть, — **бинарь**, не JSON-строки:

- Сессии: `exportSession(contactId): List<UByte>` / `importSession(contactId, data)`.
  Формат — **CFE** (16-байтовый заголовок + MessagePack через `rmp_serde`), не JSON.
- OTPK: `exportOneTimePrekeys()` / `importOneTimePrekeys(data)`.
- Приватные ключи: `exportPrivateKeys()` / `importPrivateKeys(data)`.
- Состояние оркестратора: `exportOrchestratorState()`.
- Kyber prekeys (ML-KEM-1024, сиды): `exportKyberPrekeys()` / `importKyberPrekeys(data)` —
  хранятся в `KeystoreManager` рядом с приватными ключами, импортируются в
  `CryptoManager.setLocalUserId` до того, как оркестратор становится доступен.

Правило: **никаких `base64EncodedString`-стрингификаций в прикладном коде.** Байты
ходят через границу UniFFI как `ByteArray`/`List<UByte>`. То же правило соблюдает iOS
(см. `construct-messenger/AGENTS.md` §"Binary Data Pipeline").

> В Kotlin-биндингах многие параметры типизированы как `List<kotlin.UByte>`, а не
> `ByteArray` (артефакт UniFFI). Конвертацию `ByteArray ↔ List<UByte>` прячь внутри
> `CryptoManager`, наружу отдавай `ByteArray`.

## 2.6 Threading — один поток, иначе UB

`ClassicCryptoCore`/`OrchestratorCore` **не потокобезопасны**. Все вызовы крипто
должны идти с **одного потока** (на iOS — `@MainActor`). Правила:

- Не вызывай крипто из произвольных корутин/потоков. На Android — единый
  крипто-диспетчер (single-thread executor) или `Main`, и `withContext` на него.
- Доступ к `orchestratorCore` сериализуй **reentrant-локом** (`NSRecursiveLock` на iOS →
  `ReentrantLock` на Kotlin) — нужен именно реентрантный, т.к. бывают вложенные вызовы
  (encrypt → saveSessionToSecureStore).
- Исключение на iOS — `BackgroundFetchManager`, который пробрасывает крипто через
  `MainActor.run`. На Android фоновую дешифровку гони через тот же крипто-диспетчер.

## 2.7 Правило обёртки: только `CryptoManager`

**UI и репозитории НИКОГДА не вызывают биндинги напрямую.** Всё крипто проходит через
синглтон `CryptoManager` (Hilt `@Singleton`, `di/AppModule.kt`). Причины: единая точка
для локов, двухфазной инициализации, логирования CFE и бинарной конвертации.

```
UI / ViewModel / Repository
        │  (доменные вызовы: sendMessage, registerDevice, …)
        ▼
   CryptoManager            ← локи, threading, CFE, бинарь
        │
        ▼
 OrchestratorCore / ClassicCryptoCore   (uniffi.construct_core)
        │  JNA
        ▼
   libconstruct_core.so
```

## 2.8 Поток регистрации (где сходятся API и крипто)

Канон — `AuthViewModel.swift`. Порядок (см. `IMPLEMENTATION_PLAN.md` §3.1):

1. Сгенерировать ключевой бандл: `getRegistrationBundleFields()` (+ при необходимости
   `generateOneTimePrekeys(count)`).
2. Получить PoW-challenge с сервера → решить (PoW считает Rust; есть
   `PowChallenge`/`PowSolution` и callback прогресса `PowProgressCallback`).
3. `AuthService.RegisterDevice(bundle, powSolution)` → `device_id`, `auth_token`.
4. Загрузить стартовый батч OTPK: `KeyService.UploadOneTimePrekeys` — **минимум 20**
   (порог поднят с 10 до 20 под iOS-прод; ниже 20 сервер помечает девайс на дозаливку).
5. Сохранить `auth_token` в Keystore.
6. `CryptoManager.setLocalUserId(serverUserId)` → выводится локальный
   `CryptoDeviceId`, затем создаётся `OrchestratorCore` в device-space.
7. (Опционально) recovery-фраза: `generateMnemonic` / `deriveRecoveryKeypair` (BIP39) →
   `SetRecoveryKey`.

> На рестарте `OrchestratorCore` поднимается из сохранённого CFE-состояния. Если
> состояния нет (свежая инициализация), `next_otpk_id` сбрасывается — и следующая
> заливка OTPK должна идти с `replaceExisting=true`, чтобы затереть устаревшие
> серверные ключи (флаг `needsFullOtpkReplacement` / `wasRestoredFromKeychain` на iOS).

## 2.9 Жизненный цикл сессии

Полное описание — `SESSIONS.md`. Коротко: сессия обновляется отправкой, запись хранит
прежние состояния, нечитаемое сообщение получает DECRYPTION_ERROR (тип 28).

- `hasSession(deviceId)`, `getAllSessionContactIds()`, `retireSession(deviceId)`;
  account ids переводятся через `PeerDeviceRegistry` до входа в core.
- **Не чини сессию руками** — реагируй на CFE-действия. Никаких окон, кулдаунов и
  повторов по времени: ошибка расшифровки называет состояние, ядро решает само.
- Ротация SPK: `rotateSignedPrekey()` → `RotatedSpkBundle` (новый pubkey + подпись) вместе с
  `beginKyberSpkRotation()` → одна `RotateSignedPreKey` с обеими гибридными подписями;
  `commitKyberSpkRotation()` после ответа сервера, `rollbackKyberSpkRotation()` если сервер
  отверг Kyber-ключ (`RotateSignedPreKeyUseCase`).
- Post-quantum — **PQXDH v2**, обязательный. ML-KEM-1024 входит в начальный ключ сессии:
  инициатор (`initSession`) отказывает бандлу без Kyber-ключа с обеими подписями и гибридной
  идентичности (`PQ_REQUIRED` в сообщении `CryptoException.SessionInitializationFailed` —
  `CryptoManager.isPeerNotPostQuantum`); ответчик открывается по сертификату отправителя
  (`OpenReceiving`, `ReceivingOpenUseCase`) без запроса к серверу, ядро само декапсулирует
  своим Kyber-секретом.
  Ключи генерирует и подписывает ядро, приложение хранит и публикует их — `KyberPrekeyService`.
  Замена живой сессии (ответ на `OpenSession`, когда он появится) — `reopenSession` ядра, не
  «remove, потом init».

---
---

# Чеклист

**Сетап:**
- [ ] Склонировать рядом `construct-core` и `construct-protos` (в `~/Code/`).
- [ ] Поставить Rust + Android NDK (через Android Studio → SDK Tools → NDK).
- [ ] `./build_crypto_lib.sh` → проверить `.so` в `jniLibs/` и свежий `construct_core.kt`.
- [ ] `brew install protobuf` + grpc-kotlin jar → `./generate_grpc_kotlin.sh`.
- [ ] `./gradlew assembleDebug` на эмуляторе (нужен `x86_64` таргет ядра).

**Чего НЕ делать:**
- [ ] ❌ Не редактировать `crypto/uniffi/.../construct_core.kt` и `data/api/proto/` руками.
- [ ] ❌ Не вызывать UniFFI-биндинги из UI/репозиториев — только через `CryptoManager`.
- [ ] ❌ Не писать Kotlin-фолбэк для VEIL — роутинг в Rust.
- [ ] ❌ Не стрингифицировать крипто-данные в JSON/base64 — только бинарь (CFE).
- [ ] ❌ Не звать крипто с произвольных потоков — единый крипто-диспетчер + reentrant-лок.
- [ ] ❌ Не путать VEIL ("ICE" в старых драфтах) и WebRTC ICE — это разные вещи.

**При сомнениях:** смотри Swift в `~/Code/construct-messenger`
(`Security/CryptoManager.swift`, `Networking/gRPC/`).
