# gRPC Layer — состояние, архитектура, как подключать UI

**Обновлено:** 2026-07-03. Аудитория: следующая смена, задача которой —
соединить UI (`ui/`, `viewmodel/`) с функциональными слоями через
репозитории. Здесь: что уже работает, что каркас, что отсутствует, и в каком
порядке подключать.

Канон по фазам: `docs/IMPLEMENTATION_PLAN.md`. Крипто-пайплайн:
`docs/CRYPTO_CORE.md`, `docs/FFI_BINARY_FORMAT.md`.

## 1. Карта слоя (что где лежит)

```
data/api/
├── GrpcClient.kt            # 2 канала + все coroutine-стабы   [РАБОТАЕТ]
├── MessagingService.kt      # унарные send/sendSealed/pending  [КАРКАС — см. §4]
├── MessageStreamService.kt  # bidi-стрим приёма                [КАРКАС — см. §4]
data/auth/
├── AuthInterceptor.kt       # Bearer + x-user-id/x-device-id   [РАБОТАЕТ]
├── TokenRefreshCoordinator.kt                                  [РАБОТАЕТ]
├── AuthSessionManager.kt    # format guard §13.5 + 401→refresh→ [ГОТОВ+тесты]
│                            # retry (single), permanent→re-auth
data/local/
├── KeystoreManager.kt       # токены + private keys (CFE)      [РАБОТАЕТ]
├── AckStore.kt              # durable dedup (Room + in-memory   [ГОТОВ+тесты]
│                            # mirror, hydrate() до стрима!)
├── SessionStateStore.kt     # CFE session blobs + establishedAt [ГОТОВ+тесты]
├── db/                      # Room: chats/messages/users/       [ГОТОВ]
│                            # acked_messages/session_state/session_meta
service/
├── SessionManager.kt        # DR-сессии поверх CryptoManager   [РАБОТАЕТ]
├── MessageRouter.kt         # стрим → домен-события: dedup,    [ГОТОВ+тесты]
│                            # sealed-résolve, control/message
├── MessageProcessor.kt      # CFE handleEvent → decrypt/persist [ГОТОВ+тесты]
│                            # /ack; OrchestratorGateway+Effects
crypto/
├── CryptoManager.kt         # двухфазное ядро (Classic→Orchestr) [РАБОТАЕТ]
│                            # + OrchestratorGateway (handleEvent)
│                            # wire-формат парсит ТОЛЬКО Rust core
│                            # (wire_payload.rs; дублей на Kotlin нет)
di/
├── CryptoModule.kt          # bind OrchestratorGateway→CryptoMgr [ГОТОВ]
├── DatabaseModule.kt        # Room DB + DAOs + AckStore         [ГОТОВ]
stealth/                     # sealed sender (см. §5)           [КАРКАС]
├── StealthPolicy.kt  ServerKeysProvider.kt  TokenWalletService.kt
├── BlindTokenService.kt  StealthSenderService.kt
```

## 2. Два канала — почему их два и что по какому ходит

`GrpcClient` держит два `ManagedChannel` на один и тот же `ams.konstruct.cc:443`:

- **authChannel** (+`AuthInterceptor`) — ВСЁ, кроме sealed-отправки: auth, key,
  user, messaging (унарные и стрим), notification, sentinel.
- **sealedChannel** (без интерцептора) — ТОЛЬКО `SendSealedMessage`. Отдельное
  HTTP/2-соединение обязательно: общий коннект позволил бы серверу связать
  анонимную отправку с аутентифицированным пользователем (stealth-sealed-sender-v2
  Phase 2, см. `construct-docs/decisions/stealth-sealed-sender-v2-always-on.md`).

## 3. Порядок подключения UI → функциональные слои

Рекомендуемая последовательность (каждый шаг тестируем сам по себе):

1. **Приём.** `MessageStreamService.start(scope)` + `MessageRouter.start(scope)`
   после логина → collect `MessageRouter.routed` → на `RoutedEvent.Incoming`
   вызвать `MessageProcessor.process(msg)`. Роутер (dedup/sealed-resolve/
   классификация) и процессор (CFE `handleEvent` → decrypt/persist/ack) готовы и
   покрыты юнит-тестами. `OrchestratorGateway` уже реализован —
   `CryptoManager` держит двухфазное ядро (`ClassicCryptoCore` →
   `OrchestratorCore` в `setLocalUserId`, single-thread `coreLock`) и забинжен
   через `di/CryptoModule`; оба флоу логина уже зовут `setLocalUserId`.
   **Осталась ОДНА зависимость процессора:**
   - `ProcessorEffects` — реализовать в репозитории/session-слое (persist из
     `messageJson`, отправка receipt, notify, heal/END_SESSION/keyBundle,
     `isAckedInDb` из БД). Семантику действий брать из iOS
     `SessionActionExecutor` + свитча `MessageRouter.swift`. После этого
     `MessageProcessor` можно инжектить и подключать к `MessageRouter.routed`.
   Решение по основе: `construct-docs/decisions/android-receive-path-cfe-not-component.md`.
   Подписки: `updateSubscriptions(listOf("direct:<idA>:<idB>", …))` — id
   отсортированы, как на iOS.
2. **Отправка.** ViewModel → SendMessageUseCase (нет; создать) →
   `SessionManager.encryptMessage` → ветвление из KDoc `MessagingService`
   (identified / legacy-sealed / Phase-2-sealed) → статусы в UI из `SendResult`.
3. **Догон.** `MessagingService.getPendingMessages(cursor)` на холодном старте
   до открытия стрима; курсор стрима персистится самим `MessageStreamService`.
4. **Stealth-бутстрап.** После логина: `ServerKeysProvider.prefetch()` +
   `BlindTokenService.bootstrapInitialBatch()` (см. §5).

## 4. Что каркас и чего в нём осознанно нет

`MessagingService`/`MessageStreamService` компилируются против свежих протосов
и готовы к вызову, но:

- **retry/backoff отправки** — на вызывающей стороне (iOS: bounded retry в
  send-координаторе; здесь его ещё нет);
- **re-subscribe без реконнекта** — `updateSubscriptions` применяется со
  следующего коннекта;
- **acks/errors/presence из стрима** — логируются, но не пробрасываются:
  расширить `StreamEvent`, когда появится потребитель;
- **VEIL-фолбэк транспорта** — не подключён (оба канала direct TLS);
- **`SEALED_UNAUTHENTICATED_TRANSPORT = false`** — флип синхронно с iOS
  `FeatureFlags.sealedSenderUnauthenticatedTransport` (rollout-порядок в
  decision-доке §4).

## 5. Stealth (sealed sender) — состояние

Крипта вся в construct-core (Phase 5.1, один код на обе платформы):
`sealedSealSenderCert` / `sealedUnsealSenderCert` / `sealedVerifySenderCert` /
`ppBlindToken` / `ppFinalizeToken` / `ppVerifyClient` / `ppSealTokenBytes`.

Kotlin-обвязка зеркалит iOS: политика (always-on в release, toggle в debug),
well-known-ключи (24ч кэш), кошелёк (EncryptedSharedPreferences), issuance
(лимит сервера 20/час), сертификат (кэш 24ч, verify bundle-ключом).

**Интеграции в send/receive ещё нет** — это часть шагов 1–2 из §3.
E2e-проверка iOS↔Android закроет пункт §5 decision-дока.

## 6. Gotchas (стоившие времени — не наступать повторно)

1. **Протосы vendored и разъезжаются.** Источник истины —
   `~/Code/construct-protos`; при изменении копировать сюда файл целиком.
   2026-07-03 здесь отсутствовал `SendSealedMessage` (18 строк) — уже
   синхронизировано. Проверка: `diff` c каноном перед началом работы.
2. **Имена сгенерённых классов** — см. `data/api/README.md` («Gotcha: grpc-kotlin
   stub class names»). Коротко: coroutine-стаб вложен в `XxxGrpcKt`,
   сообщения — в `XxxOuterClass`; для `core/envelope.proto` это
   `EnvelopeOuterClass`, для `signaling/presence.proto` — `Presence`.
3. **UniFFI-биндинги приезжают из CI construct-core** (release-тег `latest`,
   `construct-core-android.tar.gz`: jniLibs + `construct_core.kt`). Локально
   ничего не генерируется. Если Kotlin не видит новую FFI-функцию — артефакт
   старее, чем UDL; проверить дату `last-modified` и зелёность CI. CI гоняет
   fmt + clippy + тесты в ДВУХ конфигурациях (default и `post-quantum`) —
   локально перед пушем core: `cargo fmt --all -- --check && cargo clippy
   --all-targets -- -D warnings && cargo clippy --all-targets --features
   post-quantum -- -D warnings && cargo test && cargo test --features
   post-quantum`.
4. **Sealed-конверт на приёме**: `envelope.sender` пуст, полезная нагрузка НЕ в
   `encrypted_payload`, а внутри `sealed_inner` (реальный `content_type` — тоже
   там; внешний всегда generic). Не пытаться расшифровывать внешний конверт.
5. **`status` — зарезервированная переменная zsh** (для скриптов CI-поллинга).

## 7. Definition of done для этого слоя

- [x] MessageRouter (dedup / sealed-resolve / классификация; 2026-07-03)
- [x] ~~WirePayloadCodec~~ — удалён (2026-07-06): wire-формат парсит только Rust
      core (`wire_payload::unpack` внутри `handleEvent`; поля msgNum/kemCt/otpkId
      события — legacy, Android передаёт нули)
- [x] MessageProcessor — CFE routing FSM + action executor, юнит-тесты на фейках
      (2026-07-04). Основа: `construct-docs/decisions/android-receive-path-cfe-not-component.md`
- [x] OrchestratorGateway — двухфазный OrchestratorCore в CryptoManager +
      CryptoModule bind (2026-07-04)
- [ ] ProcessorEffects в репозитории/session-слое → инжект MessageProcessor,
      подключение к MessageRouter.routed (§3.1)
- [ ] SendMessageUseCase с retry/backoff (§3.2)
- [ ] Stealth в send/receive путях + e2e iOS↔Android
- [ ] Расширение StreamEvent (ack/error/presence) под нужды UI
- [ ] VEIL-фолбэк каналов (после стабилизации direct-пути)
