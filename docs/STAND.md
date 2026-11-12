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

Быстрая проверка одной стороны без iOS — `scripts/verify.sh --device` (или `--boot`): установит
поверх данных, запустит и прочитает logcat. Ниже — ручные шаги для живого обмена.

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

## Входящий Android share — отдельный UID и настоящий grant

Только тестовое устройство. Нужны разблокированный аккаунт/готовый store; сам тест PIN не
отключает. Оба APK должны быть подписаны сертификатом уже установленного приложения:
обычный debug key не обновит release. Не удалять приложение ради подписи. На эмуляторе
достаточно стандартных debug APK. `connectedAndroidTest` на живом телефоне не применять:
Gradle может удалить приложение после теста; устанавливать и запускать явно.

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$ADB shell am instrument -w -r \
  -e class com.construct.messenger.share.IncomingShareFilesInstrumentedTest \
  com.construct.messenger.test/androidx.test.runner.AndroidJUnitRunner
# Opt-in: создаёт self-chat при необходимости и оставляет canary сообщения на сервере.
$ADB shell am instrument -w -r -e shareStand true \
  -e class com.construct.messenger.share.IncomingShareFlowInstrumentedTest \
  com.construct.messenger.test/androidx.test.runner.AndroidJUnitRunner
```

Fixture Activity/provider работают под другим UID test APK, реально выдают/отзывают read grant.
Первый класс проверяет actual byte cap, seek, own-provider rejection и cleanup. Второй ждёт
picker, отзывает grants, проверяет отсутствие отправки, подтверждает обычной кнопкой composer;
затем ждёт две строки, скачивает и открывает три blob, проверяет PDF байтами и декодирование
фото/видео, проверяет удаление snapshots. Тест не выбирает чужой чат. У `am instrument` смотреть
`OK (...)`/failure details, не один shell exit code. Это **не Android↔iOS stand**.
Смерть процесса, rotation, PIN и исходные browser/gallery/Files проверять дополнительно;
полная матрица — vault TODO 304. Simple `am start` с чужим URI без grant этого не заменяет.

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
