# gRPC Layer — состояние, архитектура, как подключать UI

**Обновлено:** 2026-09-18. Аудитория: UI и протокол. 1:1 text slice замкнут
(runtime → receive → send → invites → Chat/Synaps). Здесь: что работает,
что каркас, что отсутствует. UI ходит только в репозитории.

Канон по фазам: `docs/IMPLEMENTATION_PLAN.md`. Крипто-пайплайн:
`docs/CRYPTO_CORE.md`, `docs/FFI_BINARY_FORMAT.md`.

## 1. Карта слоя (что где лежит)

```
data/api/
├── GrpcClient.kt            # 2 канала + все coroutine-стабы   [РАБОТАЕТ]
├── MessagingService.kt      # унарные send/sendSealed/pending  [РАБОТАЕТ]
├── MessageStreamService.kt  # bidi-стрим приёма                [РАБОТАЕТ]
data/auth/
├── AuthInterceptor.kt       # Bearer + x-user-id/x-device-id   [РАБОТАЕТ]
├── TokenRefreshCoordinator.kt                                  [РАБОТАЕТ]
├── AuthSessionManager.kt    # format guard §13.5 + 401→refresh→ [ГОТОВ+тесты]
│                            # retry (single), permanent→re-auth
data/local/
├── KeystoreManager.kt       # токены + private keys (CFE)      [РАБОТАЕТ]
├── AckStore.kt              # durable dedup (Room + in-memory   [ГОТОВ+тесты]
│                            # mirror, hydrate() до стрима!)
├── SessionStateStore.kt     # typed CFE slots + establishedAt [ГОТОВ+тесты]
├── PeerDeviceRegistry.kt    # durable account→device mapping [ГОТОВ]
├── db/                      # Room: chats/messages/users/       [ГОТОВ]
│                            # acked_messages/session_state/session_meta
service/
├── SessionManager.kt        # DR-сессии поверх CryptoManager   [РАБОТАЕТ]
├── MessageRouter.kt         # стрим → домен-события: dedup,    [ГОТОВ+тесты]
│                            # sealed-résolve, control/message
├── MessageProcessor.kt      # CFE handleEvent → typed actions   [ГОТОВ+тесты]
│                            # /decrypt/persist/ack; Gateway+Effects
├── CfeTimerBridge.kt        # AppLaunched/reconnect/timers     [ГОТОВ]
crypto/
├── CryptoManager.kt         # двухфазное ядро (Classic→Orchestr) [РАБОТАЕТ]
│                            # + OrchestratorGateway (handleEvent)
│                            # + orchestrator/PQ state snapshots
│                            # wire-формат парсит ТОЛЬКО Rust core
│                            # (wire_payload.rs; дублей на Kotlin нет)
di/
├── CryptoModule.kt          # bind OrchestratorGateway→CryptoMgr [ГОТОВ]
├── DatabaseModule.kt        # Room DB + DAOs + AckStore         [ГОТОВ]
invite/                      # v5 mint / verify / AcceptInvite  [РАБОТАЕТ]
stealth/                     # sealed sender (см. §5)           [НА SEND]
├── StealthPolicy.kt  ServerKeysProvider.kt  TokenWalletService.kt
├── BlindTokenService.kt  StealthSenderService.kt
service/MessagingRuntime.kt  # cold start → stream              [РАБОТАЕТ]
domain/usecase/SendMessageUseCase.kt                            [РАБОТАЕТ]
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

1. **Приём.** `AuthRepository.restoreSession()` / `initializeIdentity()` поднимает
   `MessagingRuntime`: restore orchestrator/PQ snapshots → import only `Session`
   slots → hydrate ACK → drain `GetPendingMessages` → `MessageRouter` +
   `MessageProcessor` + стрим. `ProcessorEffectsImpl` пишет в Room and applies
   every typed secure-store action. Подписки `direct:<sorted ids>` из `ChatDao`.
   Решение по основе: `construct-docs/decisions/android-receive-path-cfe-not-component.md`.
2. **Отправка.** `ChatViewModel` → `MessagesRepository.send` →
   `SendMessageUseCase` (KNST + CFE `OutgoingMessage` + fail-closed stealth).
   Не `encryptMessage` на сыром UTF-8. Identified конверт без `conversation_id`.
3. **Догон.** `MessagingService.getPendingMessages(cursor)` на холодном старте
   до открытия стрима; курсор стрима персистится самим `MessageStreamService`.
4. **Stealth-бутстрап.** После логина: `ServerKeysProvider.prefetch()` +
   `BlindTokenService.bootstrapInitialBatch()` (см. §5).

## 4. Что каркас и чего в нём осознанно нет

`MessagingService`/`MessageStreamService` компилируются против свежих протосов
и готовы к вызову, но:

- **retry/backoff отправки** — bounded retry уже в `SendMessageUseCase`;
- **re-subscribe без реконнекта** — `updateSubscriptions` применяется со
  следующего коннекта;
- **acks/errors/presence из стрима** — логируются, но не пробрасываются:
  расширить `StreamEvent`, когда появится потребитель;
- **VEIL-фолбэк транспорта** — не подключён (оба канала direct TLS);
- **multi-device account→device routing** — registry and device-only core
  addressing are connected; full fan-out and receive candidate walk remain;
- **CFE timers / AppLaunched / reconnect events** — wired through
  `CfeTimerBridge`; production transport coverage remains;
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

Send path: fail-closed sealed inner (`SealedInner.content_type` unspecified).
Receive: `MessageRouter` sealed-resolve. **Unauthenticated sealed transport
flag still false** (lockstep with iOS). Wallet/cert prefetch after login is
not yet a dedicated bootstrap step. Live iOS↔Android sealed round-trip has
not run.

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
- [x] ProcessorEffects в репозитории/session-слое → инжект MessageProcessor,
      подключение к MessageRouter.routed (§3.1) — `ProcessorEffectsImpl` +
      `MessagingRuntime` (2026-09-17). Typed secure-store, PQ contribution,
      session archive/termination, persist+ACK and receipts are wired; timer
      bridge and full device routing remain open.
- [x] SendMessageUseCase (§3.2) — KNST + CFE OutgoingMessage + fail-closed stealth.
      Bounded retry still on the caller. Identified envelope без conversation_id.
- [x] Contacts / invites — mint v5 + AcceptInvite + RevokeInvite (2026-08-19)
- [x] Stealth на send (fail-closed) + sealed-resolve на приёме (2026-08-19)
- [x] Heal / END_SESSION on the wire + RESPONDER init + E2E receipts + GetIdentityKey (2026-08-19)
- [x] FindUser / contact requests (UserService)
- [ ] Unauth sealed transport flag flip + e2e iOS↔Android
- [ ] invite QR; honeycomb Synaps
- [ ] Расширение StreamEvent (ack/error/presence) под нужды UI
- [ ] VEIL-фолбэк каналов (после стабилизации direct-пути)
