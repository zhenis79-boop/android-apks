# Z-Code — инструкция для продолжения проекта

Привет, Z-Code. Этот репозиторий — рабочий проект `android-apks`, внутри него
основное Android-приложение находится в папке `WAVoiceReader/`.

Цель проекта: Android-приложение WA Voice Reader следит за голосовыми WhatsApp,
отправляет новые Ogg/Opus-файлы в OpenAI audio transcription API и показывает
расшифровку поверх экрана.

## Самое важное

- Общайся с пользователем на русском языке.
- Не пытайся собирать Android-проект локально в этой среде.
- Локальная сборка Android здесь не считается источником истины.
- Источник истины для сборки — GitHub Actions.
- Любой push в `main` запускает `.github/workflows/build-apk.yml`.
- Успешный workflow публикует signed release APK в GitHub Releases.
- Пользователь получает новые APK на телефоне через Obtainium.

## Рабочий цикл

1. Прочитай `CLAUDE.md`, `WAVoiceReader/README.md` и при необходимости
   `repomix-output.xml`, если он свежий и есть локально.
2. Делай маленькие изменения в коде.
3. Проверяй diff точечно: `git diff`, `git diff --check`, `rg`.
4. Коммить осмысленно.
5. Push в `main`.
6. Проверяй GitHub Actions.
7. Если Actions зелёный, проверь release asset:
   `WAVoiceReader-v1.0.<github.run_number>.apk`.
8. Пользователь ставит APK через Obtainium и тестирует на телефоне.
9. По логам/симптомам делай следующий маленький шаг.

## Уже настроено

- `WAVoiceReader/README.md` описывает текущий процесс GitHub Actions →
  GitHub Releases → Obtainium.
- `.github/workflows/build-apk.yml` собирает signed release APK.
- Версия APK автоматическая:
  - `versionCode = github.run_number`;
  - `versionName = 1.0.<github.run_number>`;
  - tag/release: `v1.0.<github.run_number>`;
  - asset: `WAVoiceReader-v1.0.<github.run_number>.apk`.
- Workflow явно ставит Gradle 8.7 через `gradle/actions/setup-gradle`.
- Repomix настроен через:
  - `.repomixignore`;
  - `.gitignore`;
  - `repomix.config.json`.
- `repomix-output.xml` — локальный временный AI-контекст, его не коммитить.

## Repomix

Repomix нужен только для экономии токенов и быстрого понимания проекта.

Обычная команда:

```powershell
npx.cmd repomix@latest
```

Если npm cache на Windows даёт `EPERM`, используй локальный cache:

```powershell
$env:npm_config_cache="$PWD\.npm-cache"; npx.cmd repomix@latest
```

Не коммить:

- `repomix-output.xml`;
- `.npm-cache/`;
- APK/AAB;
- keystore/JKS;
- build outputs;
- логи.

## Где смотреть код

- `WAVoiceReader/app/src/main/java/com/genis/wavoicereader/MainActivity.kt`
  главный экран, разрешения, API-ключ, история, логи, тестовая запись.
- `VoiceWatcherService.kt`
  foreground service, слежение за файлами, очередь распознавания.
- `WaNotificationListener.kt`
  чтение уведомлений WhatsApp.
- `SenderTracker.kt`
  сопоставление уведомления и файла по времени.
- `WhisperClient.kt`
  запрос к OpenAI audio transcription API.
- `OverlayController.kt`
  карточки поверх экрана.
- `Logger.kt`
  локальные диагностические логи.
- `HistoryStore.kt`
  история распознанных сообщений.
- `BootReceiver.kt`
  автозапуск после перезагрузки, если слежение включено.

## Как помогать пользователю

Пользователь тестирует на реальном Android-устройстве. Поэтому лучший стиль
работы:

- делать маленькие релизы;
- не рефакторить всё сразу;
- улучшать диагностику;
- просить логи из UI приложения после теста;
- по одному исправлять реальные симптомы;
- после каждого push проверять Actions и GitHub Release.

Если сборка упала, сначала смотри логи GitHub Actions. Не устанавливай Android
SDK и не пытайся чинить локальную среду сборки.

## Последнее известное состояние

На момент создания этого файла последний подтверждённый release был `v1.0.28`.
В нём добавлен улучшенный диагностический статус на главном экране приложения:
показываются разрешения, включено ли слежение, найдены ли папки WhatsApp, путь
тестовой папки, число записей истории и строк логов.
