# Good First Issues

Первые задачи для нового человека (актуализировано 2026-09-28). Все — UI или тесты: протокол
(сессии, отправка, конверты, мультидевайс, доставка) уже в дереве и требует контекста, которого
на первой неделе нет. Полная очередь с приоритетами — `docs/IMPLEMENTATION_PLAN.md` §3; задачи
здесь — её подмножество.

Перед началом:
- `AGENTS.md` — инварианты. UI ходит только в репозитории.
- `docs/ANDROID_ONBOARDING.md` §3–§5 — дизайн-система и канон экранов.
- `README.md` — сборка; ядро (`.so`) после клона нужно получить отдельно.

## Как взять задачу

1. Напиши в чат, что берёшь задачу.
2. Ветка от `develop`, PR в `develop`.
3. Делай по образцу соседних экранов и компонентов; канон — iOS (`construct-messenger`).
4. `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` — CI пока этого не делает.
5. Закрыл задачу — отметь её в `docs/IMPLEMENTATION_PLAN.md` в том же PR.

---

## Задачи

### 1. `AppearanceSettingsScreen` (план B7)

`ui/screens/settings/AppearanceSettingsScreen.kt` — `ANDROID_ONBOARDING.md` §5.7.
`AppTheme`: AUTOMATIC / LIGHT / DARK; выбранное — `Icons.Default.Check`. Строки в `SettingsScreen`
ещё нет — добавить (и убрать «appearance» из списка недостающего в его KDoc). Образец структуры —
`SecurityScreen.kt`.

### 2. `NetworkSettingsScreen` (план B7)

`ui/screens/settings/NetworkSettingsScreen.kt` — §5.8. Сейчас строка Network в `SettingsScreen` —
только статус и никуда не ведёт; сделать её открывающей экран с connected / connecting /
disconnected из состояния потока. Переключатель VEIL показать выключенным с пометкой «скоро» —
транспорта ещё нет, **не** подключать.

### 3. Японская локализация (план B6)

`values-ja/strings.xml`: все переводимые ключи из `values/` (`translatable="false"` пропускать),
по канону копирайта (§5.1), без жаргона. Название продукта — транслитерация (`コンストラクト`), не
перевод.

### 4. Compose-тесты на `ChatRow` и `ConstructNavRow`

`onClick` срабатывает по тапу, строка показывает то, что ей передали. `@Preview` тестом не
считается. Тест должен уметь упасть: проверь это, сломав код на минуту.

---

**Не первая задача:** всё из плана с пометкой A, сессии, отправка, stealth, мультидевайс, VEIL,
звонки, медиа, восстановление, соты Synaps. FCM не задача вовсе: приложение не зависит от GMS.
