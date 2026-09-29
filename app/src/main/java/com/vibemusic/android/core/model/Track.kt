package com.vibemusic.android.core.model

/**
 * Качество аудио, как его декларирует источник.
 *
 * Реальность по источникам: локальные файлы и подписочные каталоги (Deezer HiFi,
 * Qobuz, Tidal) могут отдать честный FLAC; YT Music и SoundCloud — только lossy.
 */
enum class Quality {
    LOSSY,
    LOSSLESS,
    HI_RES,
}

/**
 * Универсальный трек, не зависящий от источника.
 *
 * Для локальных файлов [playableUri] известен сразу; для стриминговых источников
 * он резолвится плагином в момент воспроизведения.
 */
data class Track(
    val id: String,
    val sourceId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val artworkUri: String? = null,
    val playableUri: String? = null,
    val quality: Quality = Quality.LOSSY,
    /** Короткое имя формата для плашки («FLAC», «WAV», «HI-RES»). null — плашка скрыта. */
    val format: String? = null,
    /** Ссылка для «Поделиться» (страница трека у источника). null — поделимся названием. */
    val shareUrl: String? = null,
)
