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

    /** Трек-лист альбома по его playlistId (OLAK5uy_...) или browseId альбома (MPREb_...). */
    suspend fun albumTracks(playlistId: String, limit: Int = 50): List<Track> =
        withContext(Dispatchers.IO) {
            if (playlistId.startsWith("MPREb")) parseBrowseAlbumTracks(client.browse(playlistId), limit)
            else parseAlbumTracks(client.albumNext(playlistId), limit)
        }

    // ---------- Обзор: настроения, хит-парады, новинки ----------

    suspend fun moods(): List<MoodCard> =
        withContext(Dispatchers.IO) { parseMoods(client.browse(BROWSE_MOODS)) }

    /** Плейлисты категории настроения (params — из чипа moods()). */
    suspend fun moodPlaylists(params: String): List<PlaylistCard> =
        withContext(Dispatchers.IO) { parsePlaylistCards(client.browse(BROWSE_MOODS_CATEGORY, params)) }

    /** Хит-парады: карточки «Хит-парады видео» (тренды/топы дня). */
    suspend fun chartPlaylists(): List<PlaylistCard> =
        withContext(Dispatchers.IO) { parsePlaylistCards(client.browse(BROWSE_CHARTS)) }

    /** Свежие релизы: альбомы MPREb_… (смешанные «Микс»-карточки отфильтрованы). */
    suspend fun newReleases(limit: Int = 12): List<Album> =
        withContext(Dispatchers.IO) { parseBrowseAlbums(client.browse(BROWSE_NEW_RELEASES), limit) }

    /** Ищет исполнителя: browseId (UC…) первой карточки исполнителей. */
    suspend fun searchArtistId(name: String): String? = withContext(Dispatchers.IO) {
        val response = client.search(name, InnerTubeClient.SEARCH_ARTISTS_PARAMS)
        var id: String? = null
        walkRenderers(response, "musicResponsiveListItemRenderer") { r ->
            if (id == null) {
                val bid = r.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                if (bid.startsWith("UC")) id = bid
            }
        }
        id
    }

    /** Страница исполнителя: шапка + популярные треки + альбомы + синглы. */
    suspend fun artistPage(browseId: String): ArtistPage = withContext(Dispatchers.IO) {
        parseArtistPage(client.browse(browseId))
    }

    /** Радио вокруг трека: сид идёт первым, дальше похожие (состоят в RDAMVM-очереди). */
    suspend fun radioTracks(seedVideoId: String, limit: Int = 50): List<Track> =
        withContext(Dispatchers.IO) { parseAlbumTracks(client.radio(seedVideoId), limit) }

    /** Треки карточки-плейлиста (RDCLAK5uy…/OLAK5uy… + params из play-кнопки карточки). */
    suspend fun playlistTracks(playlistId: String, params: String?, limit: Int = 50): List<Track> =
        withContext(Dispatchers.IO) { parseAlbumTracks(client.playlistNext(playlistId, params), limit) }

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

    /** Парсит чипы настроений: подпись, endpoint категории, фирменный цвет. */
    internal fun parseMoods(response: JSONObject): List<MoodCard> {
        val cards = mutableListOf<MoodCard>()
        val seen = mutableSetOf<String>()
        walkRenderers(response, "musicNavigationButtonRenderer") { btn ->
            val title = btn.optJSONObject("buttonText")?.let { text ->
                when (val runs = text.optJSONArray("runs")) {
                    null -> text.optString("simpleText")
                    else -> (0 until runs.length()).joinToString("") { i -> runs.optJSONObject(i)?.optString("text").orEmpty() }
                }
            }.orEmpty()
            val params = btn.optJSONObject("clickCommand")
                ?.optJSONObject("browseEndpoint")?.optString("params").orEmpty()
            if (title.isEmpty() || params.isEmpty() || !seen.add(params)) return@walkRenderers
            val color = btn.optJSONObject("solid")?.optLong("leftStripeColor") ?: 0L
            cards += MoodCard(title, params, color)
        }
        return cards
    }

    /** Карточки плейлистов (настроения, хит-парады): обложка + play-endpoint с RD…/OLAK5uy… и params. */
    internal fun parsePlaylistCards(response: JSONObject): List<PlaylistCard> {
        val cards = mutableListOf<PlaylistCard>()
        val seen = mutableSetOf<String>()
        walkRenderers(response, "musicTwoRowItemRenderer") { r ->
            val title = r.optJSONObject("title")?.optJSONArray("runs")
                ?.let { runs -> (0 until runs.length()).joinToString("") { i -> runs.optJSONObject(i)?.optString("text").orEmpty() } }
                .orEmpty()
            val subtitle = r.optJSONObject("subtitle")?.optJSONArray("runs")
                ?.let { runs -> (0 until runs.length()).joinToString("") { i -> runs.optJSONObject(i)?.optString("text").orEmpty() } }
                .orEmpty()
            var playlistId = ""
            var playParams: String? = null
            walkRenderers(r, "watchPlaylistEndpoint") { ep ->
                val pid = ep.optString("playlistId")
                if (playlistId.isEmpty() && (pid.startsWith("RD") || pid.startsWith("OLAK5uy"))) {
                    playlistId = pid
                    playParams = ep.optString("params").takeIf { it.isNotEmpty() }
                }
            }
            if (title.isEmpty() || playlistId.isEmpty() || !seen.add(playlistId)) return@walkRenderers
            val thumb = r.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?.let { thumbs -> (0 until thumbs.length()).maxOfOrNull { thumbs.optJSONObject(it)?.optString("url").orEmpty() }.orEmpty() }
                .orEmpty()
            cards += PlaylistCard(title, subtitle, playlistId, playParams, thumb.takeIf { it.isNotEmpty() })
        }
        return cards
    }

    /** Альбомы со страницы новинок: двухрядные карточки с browseId MPREb_…. */
    internal fun parseBrowseAlbums(response: JSONObject, limit: Int): List<Album> {
        val albums = mutableListOf<Album>()
        walkRenderers(response, "musicTwoRowItemRenderer") { r ->
            if (albums.size >= limit) return@walkRenderers
            val id = r.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
            if (!id.startsWith("MPREb")) return@walkRenderers
            val title = r.optJSONObject("title")?.optJSONArray("runs")
                ?.let { runs -> (0 until runs.length()).joinToString("") { i -> runs.optJSONObject(i)?.optString("text").orEmpty() } }
                .orEmpty()
            if (title.isEmpty()) return@walkRenderers
            val subtitle = r.optJSONObject("subtitle")?.optJSONArray("runs")
                ?.let { runs -> (0 until runs.length()).joinToString("") { i -> runs.optJSONObject(i)?.optString("text").orEmpty() } }
                .orEmpty()
            // У двухрядных карточек новинок обложка лежит в thumbnailRenderer (в поиске — в thumbnail).
            val thumb = r.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?.let { thumbs -> (0 until thumbs.length()).maxOfOrNull { thumbs.optJSONObject(it)?.optString("url").orEmpty() }.orEmpty() }
                .orEmpty()
            albums += Album(
                id = id,
                title = title,
                subtitle = subtitle,
                artworkUri = thumb.takeIf { it.isNotEmpty() }?.let { u ->
                    val eq = u.indexOf('=')
                    if (eq > 0) u.substring(0, eq) + ARTWORK_SIZE_SUFFIX else u
                },
            )
        }
        return albums
    }

    /**
     * Трек-лист страницы альбома (MPREb_…). Полка бывает разных типов
     * (musicPlaylistShelfRenderer / musicShelfRenderer), поэтому собираем
     * трек-строки musicResponsiveListItemRenderer со всей страницы.
     */
    internal fun parseBrowseAlbumTracks(response: JSONObject, limit: Int): List<Track> {
        val rows = mutableListOf<JSONObject>()
        walkRenderers(response, "musicResponsiveListItemRenderer") { r -> rows += r }
        val tracks = mutableListOf<Track>()
        for (r in rows) {
            if (tracks.size >= limit) break
            val videoId = r.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotEmpty() }
                ?: r.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")
                    ?.takeIf { it.isNotEmpty() }
                ?: continue
            val columns = r.optJSONArray("flexColumns") ?: continue
            val title = columnText(columns.optJSONObject(0))
            if (videoId.isEmpty() || title.isEmpty()) continue
            val byline = columnText(columns.optJSONObject(1))
            val durationText = r.optJSONArray("fixedColumns")?.let { cols ->
                (0 until cols.length()).firstNotNullOfOrNull { k ->
                    cols.optJSONObject(k)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                        ?.let { runs -> (0 until runs.length()).joinToString("") { j -> runs.optJSONObject(j)?.optString("text").orEmpty() } }
                }
            }.orEmpty()
            val durationMs = (durationText.ifBlank { byline.split(" • ").lastOrNull().orEmpty() })
                .takeIf { DURATION_REGEX.matches(it) }?.let(::parseDuration) ?: 0L
            val thumb = r.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?.let { thumbs -> (0 until thumbs.length()).maxOfOrNull { thumbs.optJSONObject(it)?.optString("url").orEmpty() }.orEmpty() }
                .orEmpty()
            tracks += Track(
                id = videoId,
                sourceId = id,
                title = title,
                artist = byline.substringBefore(" • ").ifBlank { "Неизвестный исполнитель" },
                durationMs = durationMs,
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

    /** Разбор страницы исполнителя: immersive-шапка, безымянная полка топ-треков, карусели. */
    internal fun parseArtistPage(response: JSONObject): ArtistPage {
        val header = response.optJSONObject("header")
            ?.optJSONObject("musicImmersiveHeaderRenderer")
        val name = header?.optJSONObject("title")?.optJSONArray("runs")
            ?.let { runs -> (0 until runs.length()).joinToString("") { i -> runs.optJSONObject(i)?.optString("text").orEmpty() } }
            .orEmpty()
        val art = header?.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { thumbs -> (0 until thumbs.length()).maxOfOrNull { thumbs.optJSONObject(it)?.optString("url").orEmpty() }.orEmpty() }
            .orEmpty()
        val subscribers = header?.optJSONObject("subscriptionButton")
            ?.optJSONObject("subscriberCountText")?.optJSONArray("runs")
            ?.let { runs -> (0 until runs.length()).joinToString("") { i -> runs.optJSONObject(i)?.optString("text").orEmpty() } }
            .orEmpty()

        val tracks = mutableListOf<Track>()
        val albums = mutableListOf<Album>()
        val singles = mutableListOf<Album>()
        var tracksShelfDone = false
        walkRenderers(response, "musicShelfRenderer") { shelf ->
            if (!tracksShelfDone) {
                // Первая полка страницы исполнителя — популярные треки (без заголовка).
                val items = shelf.optJSONArray("contents") ?: return@walkRenderers
                for (i in 0 until items.length()) {
                    if (tracks.size >= 10) break
                    items.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer")
                        ?.let { r -> parseSong(r)?.let { t -> if (tracks.none { it.id == t.id }) tracks += t } }
                }
                tracksShelfDone = true
            }
        }
        walkRenderers(response, "musicCarouselShelfRenderer") { carousel ->
            val title = carousel.optJSONObject("header")
                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                ?.optJSONObject("title")?.optJSONArray("runs")
                ?.let { runs -> (0 until runs.length()).joinToString("") { i -> runs.optJSONObject(i)?.optString("text").orEmpty() } }
                .orEmpty()
            val items = carousel.optJSONArray("contents") ?: return@walkRenderers
            val dest = when {
                title.startsWith("Альбом") -> albums
                title.startsWith("Сингл") -> singles
                else -> return@walkRenderers
            }
            for (i in 0 until items.length()) {
                if (dest.size >= 12) break
                items.optJSONObject(i)?.optJSONObject("musicTwoRowItemRenderer")?.let { r ->
                    val id = r.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                    if (id.startsWith("MPREb")) {
                        val t = r.optJSONObject("title")?.optJSONArray("runs")
                            ?.let { runs -> (0 until runs.length()).joinToString("") { j -> runs.optJSONObject(j)?.optString("text").orEmpty() } }
                            .orEmpty()
                        if (t.isNotEmpty()) {
                            val thumb = r.optJSONObject("thumbnailRenderer")
                                ?.optJSONObject("musicThumbnailRenderer")
                                ?.optJSONObject("thumbnail")
                                ?.optJSONArray("thumbnails")
                                ?.let { thumbs -> (0 until thumbs.length()).maxOfOrNull { thumbs.optJSONObject(it)?.optString("url").orEmpty() }.orEmpty() }
                                .orEmpty()
                            dest += Album(
                                id = id,
                                title = t,
                                subtitle = name,
                                artworkUri = thumb.takeIf { it.isNotEmpty() }?.let { u ->
                                    val eq = u.indexOf('=')
                                    if (eq > 0) u.substring(0, eq) + ARTWORK_SIZE_SUFFIX else u
                                },
                            )
                        }
                    }
                }
            }
        }
        val similar = mutableListOf<ArtistCard>()
        val seenArtists = mutableSetOf<String>()
        walkRenderers(response, "musicCarouselShelfRenderer") { carousel ->
            val title = carousel.optJSONObject("header")
                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                ?.optJSONObject("title")?.optJSONArray("runs")
                ?.let { runs -> (0 until runs.length()).joinToString("") { i -> runs.optJSONObject(i)?.optString("text").orEmpty() } }
                .orEmpty()
            if (!title.startsWith("Похожие")) return@walkRenderers
            val items = carousel.optJSONArray("contents") ?: return@walkRenderers
            for (i in 0 until items.length()) {
                if (similar.size >= 12) break
                val r = items.optJSONObject(i)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                val bid = r.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                if (!bid.startsWith("UC") || !seenArtists.add(bid)) continue
                val t = r.optJSONObject("title")?.optJSONArray("runs")
                    ?.let { runs -> (0 until runs.length()).joinToString("") { j -> runs.optJSONObject(j)?.optString("text").orEmpty() } }
                    .orEmpty()
                if (t.isEmpty()) continue
                val thumb = r.optJSONObject("thumbnailRenderer")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                    ?.let { thumbs -> (0 until thumbs.length()).maxOfOrNull { thumbs.optJSONObject(it)?.optString("url").orEmpty() }.orEmpty() }
                    .orEmpty()
                similar += ArtistCard(t, bid, thumb.takeIf { it.isNotEmpty() })
            }
        }
        // Плейлисты артиста: карточки с watchPlaylistEndpoint (RD…/OLAK5uy…) —
        // в них попадают «Плейлисты исполнителя» и «Где встречается».
        val playlists = parsePlaylistCards(response)

        return ArtistPage(
            name = name,
            artworkUri = art.takeIf { it.isNotEmpty() },
            subscriberText = subscribers.takeIf { it.isNotEmpty() },
            tracks = tracks,
            albums = albums,
            singles = singles,
            similar = similar,
            playlists = playlists,
        )
    }

    /** Рекурсивно обходит JSON и вызывает fn на каждом объекте-рендерере с данным именем. */
    private fun walkRenderers(json: JSONObject, key: String, fn: (JSONObject) -> Unit) {
        val stack = ArrayDeque<Any?>()
        stack.add(json)
        while (stack.isNotEmpty()) {
            when (val node = stack.removeFirst()) {
                is JSONObject -> {
                    node.optJSONObject(key)?.let(fn)
                    node.keys().forEachRemaining { k -> stack.addLast(node.opt(k)) }
                }
                is org.json.JSONArray -> for (i in 0 until node.length()) stack.addLast(node.opt(i))
            }
        }
    }

    private companion object {
        const val TAG = "VibeYtm"
        val DURATION_REGEX = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
        const val ARTWORK_SIZE_SUFFIX = "=w544-h544-l90-rj"

        const val BROWSE_MOODS = "FEmusic_moods_and_genres"
        const val BROWSE_MOODS_CATEGORY = "FEmusic_moods_and_genres_category"
        const val BROWSE_CHARTS = "FEmusic_charts"
        const val BROWSE_NEW_RELEASES = "FEmusic_new_releases"
    }
}

/** Похожий исполнитель со страницы артиста: имя + его UC-страница. */
data class ArtistCard(val name: String, val browseId: String, val artworkUri: String?)

/** Страница исполнителя (YT Music): шапка + популярные треки + релизы + соседи. */
data class ArtistPage(
    val name: String,
    val artworkUri: String?,
    val subscriberText: String?,
    val tracks: List<Track>,
    val albums: List<Album>,
    val singles: List<Album>,
    val similar: List<ArtistCard> = emptyList(),
    val playlists: List<PlaylistCard> = emptyList(),
)

/** Чип настроения на Главной: подпись, endpoint категории и фирменный цвет YouTube Music. */
data class MoodCard(val title: String, val params: String, val color: Long)

/** Карточка плейлиста (настроение / хит-парад): играет через /next по play-endpoint карточки. */
data class PlaylistCard(
    val title: String,
    val subtitle: String,
    val playlistId: String,
    val playParams: String?,
    val artworkUri: String?,
)
