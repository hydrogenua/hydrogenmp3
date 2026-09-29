package com.vibemusic.android.source

import com.vibemusic.android.core.model.Track

/** Результат резолва: прямой URL и длина файла (для bounded Range у GVS). */
data class ResolvedStream(val url: String, val contentLength: Long)

/**
 * Единый контракт источника музыки. Вся мультиисточниковость приложения
 * строится на нём: YT Music, SoundCloud, Deezer, VK — по реализации на каждый.
 *
 * Плагин не знает про плеер и UI: он только ищет и отдаёт треки.
 */
interface SourcePlugin {
    val id: String
    val title: String

    /** Поиск по каталогу источника. */
    suspend fun search(query: String, limit: Int = 30): List<Track>

    /** Стартовая лента (новинки/рекомендации). Источник может вернуть пусто. */
    suspend fun home(limit: Int = 30): List<Track> = emptyList()

    /**
     * Резолвит прямой поток для трека. По умолчанию — uri, уже записанный
     * в треке (локальные файлы); стриминговые источники ходят в сеть на лету.
     */
    suspend fun resolvePlayable(track: Track): ResolvedStream? =
        track.playableUri?.let { ResolvedStream(it, 0L) }
}
