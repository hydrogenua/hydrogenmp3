# Vibe Music

Нативный Android-плеер «всё в одном»: вместо YT Music, SoundCloud, Deezer и VK Music.
Полностью автономный (zero-backend): все запросы и извлечение аудиопотоков выполняются
на устройстве, с IP пользователя.

## Стек

- Kotlin 2.0 + Coroutines/Flow
- Jetpack Compose + Material 3 (тёмная editorial-тема в духе Qobuz)
- Media3 / ExoPlayer (`MediaSessionService` — фоновое воспроизведение, системные медиа-контролы)
- Coil (обложки), Navigation Compose
- Версии пиннуты в `gradle/libs.versions.toml`

## Архитектура

Мультиисточниковость построена на интерфейсе `SourcePlugin`
(`app/src/main/java/com/vibemusic/android/source/`):

| Источник | Статус |
|---|---|
| Локальные файлы (MediaStore) | готово |
| YT Music (InnerTube-клиент) | P1 — следующий этап |
| SoundCloud | P3 |
| Deezer / VK | P5 |

Новый источник = одна реализация `SourcePlugin` + одна строка в `SourceRegistry`.

## Сборка и запуск

1. Открыть папку проекта в Android Studio (2024.2+).
2. Дождаться Gradle sync (первый раз скачает зависимости).
3. Запустить на устройстве (Отладка по USB) или эмуляторе.

> SDK и Android Studio лежат на `T:\` (T:\AndroidStudio, T:\AndroidSDK).

## Дорожная карта

См. [docs/ROADMAP.md](docs/ROADMAP.md).

## Дистрибуция

Приложение распространяется вне Google Play (GitHub Releases / F-Droid),
поскольку стримит из сторонних каталогов.
