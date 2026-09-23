# Good First Issues

UI-задачи после 1:1 text slice (актуализировано 2026-09-21). Протокол
(runtime, send/receive, v5 invites, multi-device fan-out и foreground delivery)
уже в дереве — **не** начинай с gRPC, CFE или конвертов.

Перед началом:
- `AGENTS.md` — конвенции. UI ходит только в репозитории.
- `docs/ANDROID_ONBOARDING.md` §3–§5 — дизайн-система и канон экранов.
- `README.md` — текущее состояние.
- `docs/IMPLEMENTATION_PLAN.md` — статусы фаз.

## Как взять задачу

1. Напиши в чат/тикет, что берёшь issue.
2. Ветка от `develop`.
3. Реализуй по аналогии с существующими компонентами.
4. Добавь `@Preview` для нового UI.
5. `./gradlew :app:compileDebugKotlin test --continue`
6. PR в `develop`.

---

## Уже сделано (не брать)

Дизайн-токены, CT*-компоненты включая `MessageBubble`, `MessageInputView`,
`ConstructActionRow`, `ConstructButtonRow`, `CTModeSelector`,
`ConnectionStatusIndicator`, `CTNoise`.

Экраны на реальных репозиториях: Splash, Onboarding, Orientation, MainTabView,
ChatsList, Chat (пузыри + send), Synaps (mint v5 / paste / список / FindUser /
contact requests).

`ChatViewModel` / `SynapsViewModel`. Hilt биндит `*RepositoryImpl`, не моки.

---

## Ещё UI

### 1. `AccountSettingsScreen`

`ui/screens/settings/AccountSettingsScreen.kt` — профиль по §5.6.
`CTNavBar` `identity`, аватар 100dp, секции Identity / Account / Backup /
Danger zone. Данные — `AuthRepository`, без новых RPC.

### 2. `AppearanceSettingsScreen`

`ui/screens/settings/AppearanceSettingsScreen.kt` — §5.7.
`AppTheme`: AUTOMATIC / LIGHT / DARK. `Icons.Default.Check` на выбранном.

### 3. `NetworkSettingsScreen`

`ui/screens/settings/NetworkSettingsScreen.kt` — §5.8.
Показать connected / connecting / disconnected. VEIL toggle **не** подключать
(транспорта ещё нет) — disabled + «soon».

### 4. QR инвайта

Synaps сейчас share/copy текстовой ссылки. Канон iOS — QR на 300 с (v5).
Можно начать с отображения той же ссылки как QR; mint уже v5.

### 5. `values-ja/strings.xml`

Скопировать ключи из `values/strings.xml`, перевести onboarding / main /
Synaps / chat. Не выдумывать жаргон — см. §5.1 канон копирайта.

### 6. Compose-тесты на `ChatRow` / `ConstructNavRow`

`onClick` по тапу; preview не считается тестом.

---

Не first-issue: honeycomb Synaps, queued multi-carrier receive, heal/END_SESSION,
VEIL, foreground delivery, WebRTC, recovery, media. Это протокол / отдельный
план. FCM не является задачей: базовый APK не зависит от GMS.

Канон iOS: `construct-messenger/ConstructMessenger/`.
Протокол: `docs/WIRE_FORMAT_RULES.md`, `docs/GRPC_LAYER.md`.
