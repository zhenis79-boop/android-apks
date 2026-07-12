# android-apks — контекст инфраструктуры

## ⚠️ ГЛАВНОЕ ПРАВИЛО: локальная сборка НЕВОЗМОЖНА

На этом сервере **НЕТ и НЕ ДОЛЖНО БЫТЬ** Android SDK / gradle. Сервер не потянет
локальную компиляцию Android-приложений. **Не устанавливай SDK, не запускай
`gradle`/`./gradlew` локально, не ищи `ANDROID_HOME`.**

Сборка идёт **ТОЛЬКО через GitHub Actions**. Правильный рабочий процесс:

1. Пишешь/правишь код локально.
2. `git commit` + `git push origin main`.
3. GitHub Actions (`.github/workflows/build-apk.yml`) собирает подписанный
   release APK и публикует его в GitHub Releases.
4. Компиляция проверяется на стороне CI — если код не собрался, смотри
   `gh run list` / `gh run view --log-failed`.
5. На телефоне APK прилетает через **Obtainium** (следит за GitHub Releases репо).

То есть push в `main` — это и есть «собрать приложение». Другого способа нет.

## Проверка результата сборки

```
gh run list --limit 3
gh run view <run-id> --log-failed   # если упало
gh release list
```

## Компактный контекст для AI

Чтобы не тратить лишние токены, перед большими задачами можно обновить
Repomix-контекст:

```
npx.cmd repomix@latest
```

Конфиг лежит в `repomix.config.json`. Он включает Kotlin/XML/Gradle/workflow
файлы и исключает build outputs, APK, keystore, логи и сам `repomix-output.xml`.
Файл `repomix-output.xml` — временный локальный артефакт, его не коммитить.

## Структура

- `WAVoiceReader/` — Android-приложение (Kotlin). Расшифровывает голосовые
  WhatsApp через OpenAI Whisper и показывает текст плавающей карточкой поверх экрана.
- `.github/workflows/build-apk.yml` — CI: собирает и публикует релиз.
  Версия проставляется автоматически: `v1.0.<github.run_number>`, имя ассета
  `WAVoiceReader-v1.0.<run_number>.apk` (уникальное имя — иначе Obtainium падает
  с `PathAccessException` при перезаписи одноимённого файла в Download).
  Workflow явно ставит Gradle 8.7 через `gradle/actions/setup-gradle`, потому что
  полноценного `gradlew` wrapper-скрипта в репозитории сейчас нет.

## Секреты CI (в настройках репозитория)

`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD` — для подписи release APK. Подпись стабильна между
релизами (иначе Obtainium/Android не даст обновить приложение поверх старого).
