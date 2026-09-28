# Стенд Android↔iOS

Как прогнать живой обмен между эмулятором Android и симуляторами iOS на одной машине (macOS).
Юнит-тесты не заменяют стенд: два дефекта варианта B (2026-09-28) нашёл только он.

## Что нужно

- Android SDK с эмулятором и AVD (проверено на `Medium_Phone_API_36.1`):
  `~/Library/Android/sdk/emulator/emulator -list-avds`.
- Для iOS-стороны — `construct-ios` рядом (локально `~/Code/construct-messenger`) и его стенд: `docs/TWO_SIM_STAND.md`,
  `scripts/two_sims.sh` в том репозитории.
- Обе стороны ходят в production-сервер; отдельного тестового сервера нет.

## Android

```bash
ADB=~/Library/Android/sdk/platform-tools/adb

# эмулятор (оставить работать в отдельном терминале)
~/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_36.1 -no-snapshot-save -no-boot-anim

# дождаться загрузки
until [ "$($ADB shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 3; done

# установить поверх (-r сохраняет данные — так проверяется обновление)
./gradlew :app:assembleDebug && $ADB install -r app/build/outputs/apk/debug/app-debug.apk

# запустить и читать лог
$ADB logcat -c
$ADB shell monkey -p com.construct.messenger -c android.intent.category.LAUNCHER 1
$ADB logcat -d | grep -E 'MessageProcessor|CfeTimerBridge|MessagingRuntime|SessionManager|SendMessage'
```

Нажатия без рук: `$ADB shell uiautomator dump /sdcard/ui.xml`, найти `bounds` нужного узла,
`$ADB shell input tap X Y`; текст — `$ADB shell input text '...'`.

## Сценарии, которые стоит прогонять после изменений в сессиях

| Сценарий | Ожидаемое |
|---|---|
| Установка поверх старой версии | сессии восстановились, сброса нет |
| Обычный обмен в обе стороны | по одному пузырю, квитанции доходят |
| Получатель удалил чат, автор пишет | получатель шлёт DECRYPTION_ERROR → автор выводит состояние и переотправляет → чат вернулся |
| Ручной сброс + перезапуск, затем отправка | новое состояние открывается сразу, без ошибки |
| Второе устройство того же аккаунта (A2 в плане) | копия исходящего появляется по одному разу |

В логе не должно быть `not acted on` по действиям, которые план считает исполненными, и
повторяющихся DECRYPTION_ERROR по одному сообщению.
