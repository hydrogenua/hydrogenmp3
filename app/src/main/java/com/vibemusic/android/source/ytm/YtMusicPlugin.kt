package com.vibemusic.android.source.ytm

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vibemusic.android.core.model.Album
import com.vibemusic.android.core.model.Quality
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.playback.PlaybackService
import com.vibemusic.android.playback.StreamLengths
import com.vibemusic.android.source.ResolvedStream
import com.vibemusic.android.source.SourcePlugin
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Источник YT Music через InnerTube.
 *
 * Поиск и обложки — клиент WEB_REMIX; стрим резолвится в момент воспроизведения.
 * С 2026-09 YouTube требует PoToken для анонимных стримов, поэтому основной путь —
 * токен из WebView-BotGuard ([PoTokenProvider]) + старая цепочка клиентов как запас.
 * Качество — максимум lossy (opus/AAC до ~256 kbps).
 */
class YtMusicPlugin(context: Context) : SourcePlugin {

    override val id = "ytm"
    override val title = "YT Music"

    private val client = InnerTubeClient()
    private val potProvider = PoTokenProvider.get(context)
    private val probeHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun search(query: String, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val response = client.search(query)
            val tracks = parseSearch(response, limit)
            if (tracks.isEmpty() && !response.toString().contains("musicShelfRenderer")) {
                // HTTP 200, но полки песен нет — типично для антиспам-заглушки YouTube.
                throw IllegalStateException("YTM ответил без результатов (похоже на бот-проверку)")
            }
            tracks
        }

    /** Поиск альбомов (полки «Альбомы» и «Синглы»). */
    suspend fun searchAlbums(query: String, limit: Int = 12): List<Album> =
        withContext(Dispatchers.IO) { parseAlbums(client.search(query, InnerTubeClient.SEARCH_ALBUMS_PARAMS), limit) }

    /** Трек-лист альбома по его playlistId (OLAK5uy_...). */
    suspend fun albumTracks(playlistId: String, limit: Int = 50): List<Track> =
        withContext(Dispatchers.IO) { parseAlbumTracks(client.albumNext(playlistId), limit) }

    override suspend fun resolvePlayable(track: Track): ResolvedStream? = withContext(Dispatchers.IO) {
        // Путь 1: PoToken из WebView-BotGuard — рабочий путь для VPN/флагованных IP.
        val potAttempt = runCatching {
            val visitorData = client.ensureVisitorData()
            val pots = potProvider.pots(visitorData, track.id)
            val response = client.player(track.id, pots.visitorPot)
            val (url, contentLength) = client.pickAudioFormat(response)
                ?: throw IllegalStateException("в ответе нет аудио-потоков")
            val finalUrl = appendGvsPot(url, pots.visitorPot)
            StreamLengths.put(finalUrl, contentLength)
            Log.d(TAG, "resolve host=${Uri.parse(url).host} cl=$contentLength (visitorPot на URL)")
            ResolvedStream(finalUrl, contentLength)
        }
        potAttempt.getOrNull()?.let { return@withContext it }
        Log.w(TAG, "PoToken-путь упал: ${potAttempt.exceptionOrNull()?.message}")

        // Путь 2 (запас): старая цепочка клиентов без токена.
        val plainAttempt = runCatching {
            val (url, contentLength) = client.pickAudioFormat(client.player(track.id))
                ?: throw IllegalStateException("в ответе нет аудио-потоков")
            StreamLengths.put(url, contentLength)
            Log.d(TAG, "resolve (plain) host=${Uri.parse(url).host} cl=$contentLength")
            ResolvedStream(url, contentLength)
        }
        plainAttempt.getOrNull()?.let { return@withContext it }
        Log.w(TAG, "Обычный путь упал: ${plainAttempt.exceptionOrNull()?.message}")
        null
    }

    /** GVS-ссылкам подписываем visitor-привязанный токен. */
    private fun appendGvsPot(url: String, pot: String): String {
        val separator = if (url.contains('?')) "&" else "?"
        return url + separator + "pot=" + URLEncoder.encode(pot, "UTF-8")
    }

    /** Парсит все полки альбомного поиска (Альбомы + Синглы). */
    internal fun parseAlbums(response: JSONObject, limit: Int): List<Album> {
        val sections = response.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?: return emptyList()

        val albums = mutableListOf<Album>()
        val seen = mutableSetOf<String>()
        for (s in 0 until sections.length()) {
            val shelf = sections.optJSONObject(s)?.optJSONObject("musicShelfRenderer") ?: continue
            val items = shelf.optJSONArray("contents") ?: continue
            for (i in 0 until items.length()) {
                if (albums.size >= limit) return albums
                val renderer = items.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                parseAlbum(renderer)?.let { album ->
                    if (seen.add(album.id)) albums += album
                }
            }
        }
        return albums
    }

    private fun parseAlbum(renderer: JSONObject): Album? {
        // playlistId альбома лежит в кнопке play поверх обложки или в меню shuffle.
        val playlistId = renderer.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchPlaylistEndpoint")
            ?.optString("playlistId")
            ?.takeIf { it.isNotEmpty() }
            ?: renderer.optJSONObject("menu")
                ?.optJSONObject("menuRenderer")
                ?.optJSONArray("items")
                ?.let { items ->
                    (0 until items.length()).firstNotNullOfOrNull { k ->
                        items.optJSONObject(k)
                            ?.optJSONObject("menuNavigationItemRenderer")
                            ?.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("watchPlaylistEndpoint")
                            ?.optString("playlistId")
                            ?.takeIf { it.startsWith("OLAK5uy_") }
                    }
                }
            ?: return null

        val columns = renderer.optJSONArray("flexColumns") ?: return null
        val title = columnText(columns.optJSONObject(0))
        if (title.isEmpty()) return null
        val subtitle = columnText(columns.optJSONObject(1))

        return Album(
            id = playlistId,
            title = title,
            subtitle = subtitle,
            artworkUri = bigArtwork(renderer),
        )
    }

    /** Трек-лист альбома из ответа next-эндпоинта. */
    internal fun parseAlbumTracks(response: JSONObject, limit: Int): List<Track> {
        val contents = response.optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicQueueRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("playlistPanelRenderer")
            ?.optJSONArray("contents")
            ?: return emptyList()

        val tracks = mutableListOf<Track>()
        for (i in 0 until contents.length()) {
            if (tracks.size >= limit) break
            val panel = contents.optJSONObject(i)?.optJSONObject("playlistPanelVideoRenderer") ?: continue
            val videoId = panel.optString("videoId").takeIf { it.isNotEmpty() } ?: continue
            val title = panel.optJSONObject("title")
                ?.optJSONArray("runs")
                ?.let { runs -> (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" } }
                .orEmpty()
            if (title.isEmpty()) continue
            val byline = panel.optJSONObject("longBylineText")
                ?.optJSONArray("runs")
                ?.let { runs -> (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" } }
                .orEmpty()
            val artist = byline.substringBefore(" • ").ifBlank { "Неизвестный исполнитель" }
            val lengthText = panel.optJSONObject("lengthText")
                ?.optJSONArray("runs")
                ?.let { runs -> (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" } }
                .orEmpty()
            val thumb = (panel.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                ?.let { thumbs ->
                    (0 until thumbs.length()).maxOfOrNull { thumbs.optJSONObject(it)?.optString("url").orEmpty() }
                }.orEmpty()

            tracks += Track(
                id = videoId,
                sourceId = id,
                title = title,
                artist = artist,
                durationMs = parseDuration(lengthText),
                artworkUri = thumb.takeIf { it.isNotEmpty() }?.let { u ->
                    val eq = u.indexOf('=')
                    if (eq > 0) u.substring(0, eq) + ARTWORK_SIZE_SUFFIX else u
                },
                quality = Quality.LOSSY,
                shareUrl = "https://music.youtube.com/watch?v=$videoId",
            )
        }
        return tracks
    }

    internal fun parseSearch(response: JSONObject, limit: Int): List<Track> {
        val sections = response.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?: return emptyList()

        val tracks = mutableListOf<Track>()
        for (s in 0 until sections.length()) {
            val shelf = sections.optJSONObject(s)?.optJSONObject("musicShelfRenderer") ?: continue
            val items = shelf.optJSONArray("contents") ?: continue
            for (i in 0 until items.length()) {
                if (tracks.size >= limit) return tracks
                val renderer = items.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                parseSong(renderer)?.let { tracks += it }
            }
        }
        return tracks
    }

    private fun parseSong(renderer: JSONObject): Track? {
        val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotEmpty() }
            ?: renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?.takeIf { it.isNotEmpty() }
            ?: return null

        val columns = renderer.optJSONArray("flexColumns") ?: return null
        val title = columnText(columns.optJSONObject(0))
        if (title.isEmpty()) return null

        // Подзаголовок песни: «Исполнитель • Альбом • 6:29»
        val parts = columnText(columns.optJSONObject(1)).split(" • ")
        val durationMs = parts.lastOrNull()?.takeIf { DURATION_REGEX.matches(it) }?.let(::parseDuration) ?: 0L
        val artist = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: "Неизвестный исполнитель"
        val album = parts.getOrNull(1)?.takeIf { parts.size > 2 && !DURATION_REGEX.matches(it) }

        return Track(
            id = videoId,
            sourceId = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkUri = bigArtwork(renderer),
            playableUri = null,
            quality = Quality.LOSSY,
            shareUrl = "https://music.youtube.com/watch?v=$videoId",
        )
    }

    private fun columnText(column: JSONObject?): String =
        column?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.let { text ->
                text.optString("simpleText").takeIf { it.isNotEmpty() }
                    ?: text.optJSONArray("runs")?.let { runs ->
                        (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }
                    }
            }
            .orEmpty()

    /** У googleusercontent-обложек подменяем размер на максимально доступный. */
    private fun bigArtwork(renderer: JSONObject): String? {
        val thumbs = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: return null
        var best: JSONObject? = null
        for (i in 0 until thumbs.length()) {
            val candidate = thumbs.optJSONObject(i) ?: continue
            val bestWidth = best?.optInt("width", 0) ?: Int.MIN_VALUE
            if (candidate.optInt("width", 0) > bestWidth) best = candidate
        }
        val url = best?.optString("url").orEmpty()
        if (url.isEmpty()) return null
        val eq = url.indexOf('=')
        return if (eq > 0) url.substring(0, eq) + ARTWORK_SIZE_SUFFIX else url
    }

    private fun parseDuration(text: String): Long {
        val parts = text.split(":").map { it.toLongOrNull() ?: 0L }
        val seconds = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
        return seconds * 1000
    }

    private companion object {
        const val TAG = "VibeYtm"
        val DURATION_REGEX = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
        const val ARTWORK_SIZE_SUFFIX = "=w544-h544-l90-rj"
    }
}
