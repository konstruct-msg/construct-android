# Konstruct Android — план работ

> **Актуализировано 2026-09-28.** `develop`, версия приложения 0.2.0 (2),
> construct-core `0.20.0+83ccd84`.
>
> **Это единственный план.** Статус и очередь задач живут здесь и больше нигде: `README.md`
> даёт снимок и ссылается сюда, `GOOD_FIRST_ISSUES.md` — вход для нового человека, задачи в нём
> — подмножество раздела «Очередь». Закрыл задачу — поправь эту таблицу в том же коммите.

---

## 1. Как войти (человеку и агенту)

Порядок чтения — каждый следующий файл предполагает предыдущие:

1. `AGENTS.md` — инварианты. Короткий, читать целиком.
2. Этот файл — что сделано и что брать.
3. `README.md` — сборка, требования, карта пакетов.
4. По области задачи:

| Область | Читать |
|---|---|
| UI, экраны, токены | `docs/ANDROID_ONBOARDING.md` §3–§5 (канон дизайна — iOS) |
| Сессии, крипто-путь | `docs/SESSIONS.md`, затем `docs/API_CRYPTO_GUIDE.md` |
| Отправка, конверт, stealth, мультидевайс | `docs/WIRE_FORMAT_RULES.md` — **обязательно** до правки `SendMessageUseCase` |
| gRPC | `docs/GRPC_LAYER.md` |
| Токены, авторизация | `docs/TOKEN_AUTH.md` |
| Стенд Android↔iOS | `docs/STAND.md` |
| Подпись release | `~/Code/construct-docs/client/android/android-sign.md` |

Почему решения такие, какие есть, — хранилище `~/Code/construct-docs` (`decisions/`). Если его
нет, всё, что нужно для работы, есть в этом репозитории; хранилище — это «почему», не «как».

**Проверка перед коммитом:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`.
CI, который это делает, пока нет (задача A3) — прогон на своей машине обязателен.

---

## 2. Что работает

| Область | Состояние |
|---|---|
| Регистрация, вход, PASETO, PoW, OTPK (персист до загрузки) | ✅ |
| Ротация SPK и Kyber-ключа (`RotateSignedPreKeyUseCase`) | ✅ |
| 1:1 текст по production gRPC: отправка, приём, квитанции (KNST 14) | ✅ |
| Правка и удаление текста, цитаты-ответы (внутри шифротекста) | ✅ |
| Sealed sender, fail-closed при отправке | ✅ |
| Мультидевайс: fan-out по устройствам получателя и своим, SENDER_SYNC (`OwnDeviceCopy`) | ✅ (стенд Android↔iOS одного аккаунта — не гонялся) |
| Сессии: обновление отправкой, открытие по сертификату, DECRYPTION_ERROR | ✅ стенд 2026-09-28, см. `SESSIONS.md` |
| PQXDH v2 (ML-KEM-1024), обязательный | ✅ для новых сессий; апгрейд старых — задача A1 |
| Доставка без GMS: foreground service + постоянный поток | ✅ на эмуляторе; на железе — задача B5 |
| Инвайты v5: QR (CameraX + ZXing), вставка, `konstruct://add`, отзыв | ✅ |
| FindUser, запросы в контакты | ✅ |
| Экраны: онбординг, Orientation, чаты, чат, Synaps (список), настройки: Account, Security | ✅ |
| Локализация `en` + `ru` | ✅ полный паритет; `ja` — B6 |

---

## 3. Очередь

Приоритет: **A** — блокирует или ломает то, что уже работает; **B** — нужно до публичной сборки;
**C** — большие направления, отдельный план у каждого.

### A — сначала

| # | Задача | Где начать | Готово, когда |
|---|---|---|---|
| A1 | **Стенд `CfeAction.OpenSession`.** Исполнитель добавлен: свежий bundle конкретного устройства передаётся в `reopen_session`, сохраняющем прежнее состояние. | `SessionManager.reopenSessionForDevice`; `CfeTimerBridge` / `MessageProcessor`; `SESSIONS.md` §8 | Пара Android↔iOS со старой сессией через ~15 с после запуска показывает `pq_handshake=InitialV2` на обеих сторонах |
| A2 | **Стенд SENDER_SYNC Android↔iOS одного аккаунта.** Копии между платформами одного аккаунта ни разу не прогонялись. | `docs/STAND.md`; `WIRE_FORMAT_RULES.md` §SSR1 | Сообщение, отправленное с iOS, появляется исходящим на Android того же аккаунта и наоборот, по одному разу |
| A3 | **CI, который компилирует.** Гейт `checkCoreLibrary` стоит перед компилятором: без `.so` нужной версии компилятор не запускается, и коммиты уходили без компиляции (vault TODO 67). | GitHub Actions: скачать архив ядра по ссылке из `construct-core.lock`, затем `:app:testDebugUnitTest` | Коммит, ломающий `when` по `CfeAction`, краснеет в CI до мержа |
| A4 | **Подписанный release.** Сейчас собирается только debug. | `android-sign.md` (хранилище); `signingConfigs` в `app/build.gradle` читает `~/.gradle/gradle.properties` | `./gradlew assembleRelease` даёт подписанный APK; ключ и пароль в резервной копии вне машины |

### B — до публичной сборки

| # | Задача | Где начать | Готово, когда |
|---|---|---|---|
| B1 | Карта серверных id (`ServerMessageIds`) переживает перезапуск | `service/ServerMessageIds.kt` → Room | DECRYPTION_ERROR после перезапуска отправителя переотправляет сообщение |
| B2 | Убрать `session_meta.establishedAtMs` — пишется, не читается (кормил фильтр END_SESSION) | Room-миграция, `SessionStateStore` | Поля нет в схеме, миграция покрыта тестом |
| B3 | Сузить `buildSealedInner(contentType)` до закрытого перечисления | `stealth/StealthSenderService.kt`; `WIRE_FORMAT_RULES.md` §2 | Ненулевой тип снаружи можно передать только как `DECRYPTION_ERROR` |
| B4 | Восстановление аккаунта по BIP39 | §4 ниже; ядро: `generateMnemonic`, `deriveRecoveryKeypair` | Установка → фраза → новое устройство входит по фразе |
| B5 | Проверка на железе: Android 11, убийство процесса, перезагрузка, Doze, смена сети | `MessagingForegroundService` | Сообщения доходят во всех пяти случаях, записано в заметке сессии |
| B6 | Локализация `ja` | `res/values-ja/strings.xml` | Все переводимые ключи `values/` есть в `ja` |
| B7 | Настройки: Appearance, Network | `GOOD_FIRST_ISSUES.md` №1–2 | Экраны открываются из Settings, строки во всех локалях |

### C — направления

| # | Направление | Состояние | Опора |
|---|---|---|---|
| C1 | VEIL (обфускация транспорта) | не начато | §5; уже внутри `libconstruct_core.so`, Kotlin-маршрутизации не писать |
| C2 | Звонки (WebRTC) | не начато | §6 |
| C3 | Медиа (вложения, голосовые) | не начато | канон iOS `Services/Media` |
| C4 | Группы (MLS) | не начато | канон iOS |
| C5 | Synaps в виде сот | не начато | `ANDROID_ONBOARDING.md` §5.10 |

**Чего не будет:** FCM и любая зависимость от Google Play Services (`AGENTS.md`); второй APK-flavour;
Kotlin-реализация любого решения, которое принимает ядро.

---

## 4. Восстановление (B4) — набросок

```
Первый раз:
1. mnemonic = core.generateMnemonic(12)
2. keypair  = core.deriveRecoveryKeypair(mnemonic)
3. RPC SetRecoveryKey(keypair.publicKey)
4. Показать фразу один раз

Новое устройство:
1. keypair = core.deriveRecoveryKeypair(mnemonic)
2. RPC InitiateRecovery() → challenge
3. signature = core.signRecoveryChallenge(keypair.privateKey, challenge)
4. RPC CompleteRecovery(signature) → новый токен
5. Перерегистрировать ключи, загрузить OTPK
```

Имя пользователя при восстановлении нормализуется так же, как при регистрации
(`trim().lowercase()`) — iOS потерял на этом вход по смешанному регистру.

## 5. VEIL (C1)

- Прямой путь — TLS на `ams.konstruct.cc:443`. Адреса мостов **не** зашиваются: подписанный
  манифест `.well-known/veil-bridges` + SPKI-пин.
- Выбор метода (obfs4 / WebTunnel / veil-front), гонка, запасной путь — всё в Rust
  (`veil_start`). Kotlin — тонкая обёртка, возвращающая локальный порт; gRPC ходит в него как h2c.
- iOS удалил собственную логику «прямой путь, затем запасной», потому что две реализации разошлись.
  Не повторять.
- «VEIL» ≠ «WebRTC ICE». Первое — наша обфускация, второе — NAT-traversal звонков.

## 6. Звонки (C2)

- TURN-учётки — короткоживущие, из `SignalingService.GetTurnCredentials(callId)`; статический
  секрет у клиента не хранится.
- Сигналинг идёт через E2EE-поток (content type 12).
- Системный UI — `ConnectionService` + `TelecomManager` (аналог CallKit).
- Уроки iOS: аудиосессию настраивать до того, как система её активирует; гасить локальный
  гудок, как только соединение установлено; системный выбор аудиомаршрута вместо тумблера
  «динамик».

---

## 7. Сделанные фазы — коротко

Фазы 0–3 и 5.2, 5.3, 7 закрыты. Подробности того, как именно, — в истории git этого файла
(ревизия до 2026-09-28) и в `sessions/` хранилища. Из них стоит помнить:

- **Сессии в CFE-бинарнике, никогда не в JSON.** Байты через UniFFI — `ByteArray`.
- **Hilt без `AppModule`**: синглтоны с `@Inject constructor()`; модуль появляется только для `@Binds`.
- **Keystore хранит только токены.** Ключи личности живут в ядре и сохраняются его экспортом.
- **gRPC-стабы генерирует Gradle-плагин** из `app/src/main/proto/` (vendored,
  `scripts/sync-protos.sh`). grpc-kotlin кладёт `XxxCoroutineStub` внутрь `object XxxGrpcKt`.
- **Состояние сессии сохраняется до отправки RPC.**
