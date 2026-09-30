package com.vibemusic.android.source.ytm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Минимальный клиент InnerTube (внутреннего API YouTube).
 *
 * Проверено живыми запросами с резидентного IP (2026-09): без входа в аккаунт
 * рабочие URL отдают клиенты ANDROID и IOS; WEB_REMIX/TVHTML5/ANDROID_VR/ANDROID_MUSIC
 * требуют логин или PoToken. Поэтому player-запросы идут по цепочке —
 * пока какой-то клиент не ответит статусом OK и потоками.
 */
class InnerTubeClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Тот же клиент, но принудительно HTTP/1.1 — запас на случай, когда сеть душит HTTP/2. */
    private val httpFallback: OkHttpClient by lazy {
        http.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Выдаётся YouTube в ответах; подписываем им player-запросы как «свою» сессию. */
    @Volatile
    private var visitorData: String? = null

    val currentVisitorData: String?
        get() = visitorData

    /** Гарантирует сессию: если visitorData ещё нет — делает фиктивный поиск. */
    fun ensureVisitorData(): String {
        visitorData?.let { return it }
        runCatching { search("music") }
        return visitorData ?: error("YTM: не удалось получить visitorData")
    }

    /** Поиск по каталогу YT Music: клиент WEB_REMIX + фильтр (песни/альбомы). */
    fun search(query: String, params: String = SEARCH_SONGS_PARAMS): JSONObject {
        val payload = JSONObject()
            .put("query", query)
            .put("params", params)
            .put("context", clientContext(WEB_REMIX, WEB_REMIX_VERSION, null, null))
        val request = Request.Builder()
            .url("$BASE/search?prettyPrint=false")
            .header("User-Agent", DESKTOP_UA)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", WEB_REMIX_VERSION)
            .header("Origin", "https://music.youtube.com")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        val response = execute(request)
        // visitorData выдаётся в каждом ответе: запоминаем и подписываем им player-запросы.
        response.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotEmpty() }?.let {
            visitorData = it
        }
        return response
    }

    /** Трек-лист альбома/плейлиста через next-эндпоинт (очередь watch-страницы). */
    fun albumNext(playlistId: String): JSONObject = playlistNext(playlistId, null)

    /**
     * Очередь плейлиста для /next. Плейлист обязан быть в «play»-формате (RDCLAK5uy…/OLAK5uy…,
     * как в watchPlaylistEndpoint карточек) — browse-формат с префиксом VL… отдаёт пустую очередь.
     * params из карточки задаёт старт/шафл; без него плейлист играет с начала.
     */
    fun playlistNext(playlistId: String, params: String?): JSONObject {
        val payload = JSONObject()
            .put("playlistId", playlistId)
            .put("context", clientContext(WEB_REMIX, WEB_REMIX_VERSION, null, null))
        if (params != null) payload.put("params", params)
        val request = Request.Builder()
            .url("$BASE/next?prettyPrint=false")
            .header("User-Agent", DESKTOP_UA)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", WEB_REMIX_VERSION)
            .header("Origin", "https://music.youtube.com")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return execute(request)
    }

    /** Радио вокруг трека: очередь «похожего» из YT Music (RDAMVM-плейлист сида). */
    fun radio(seedVideoId: String): JSONObject {
        val payload = JSONObject()
            .put("videoId", seedVideoId)
            .put("playlistId", "RDAMVM$seedVideoId")
            .put("context", clientContext(WEB_REMIX, WEB_REMIX_VERSION, null, null))
        val request = Request.Builder()
            .url("$BASE/next?prettyPrint=false")
            .header("User-Agent", DESKTOP_UA)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", WEB_REMIX_VERSION)
            .header("Origin", "https://music.youtube.com")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return execute(request)
    }

    /** browse-страница (настроения, хит-парады, новинки, альбом MPREb_…). */
    fun browse(browseId: String, params: String? = null): JSONObject {
        val payload = JSONObject()
            .put("browseId", browseId)
            .put("context", clientContext(WEB_REMIX, WEB_REMIX_VERSION, null, null))
        if (params != null) payload.put("params", params)
        val request = Request.Builder()
            .url("$BASE/browse?prettyPrint=false")
            .header("User-Agent", DESKTOP_UA)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", WEB_REMIX_VERSION)
            .header("Origin", "https://music.youtube.com")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        val response = execute(request)
        response.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotEmpty() }?.let {
            visitorData = it
        }
        return response
    }

    /** player-эндпоинт: перебирает клиентов из цепочки, возвращает первый ответ с потоками. */
    fun player(videoId: String, poToken: String? = null): JSONObject {
        val attempts = mutableListOf<String>()
        for (client in PLAYER_CLIENTS) {
            try {
                val context = clientContext(client.clientName, client.clientVersion, client.androidSdkVersion, visitorData)
                if (poToken != null) {
                    context.put("serviceIntegrityDimensions", JSONObject().put("poToken", poToken))
                }
                val payload = JSONObject()
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true)
                    .put("context", context)
                val request = Request.Builder()
                    .url("$BASE/player?prettyPrint=false")
                    .header("User-Agent", client.userAgent)
                    .header("X-YouTube-Client-Name", client.id)
                    .header("X-YouTube-Client-Version", client.clientVersion)
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
                val response = execute(request)
                val status = response.optJSONObject("playabilityStatus")?.optString("status")
                if (status == "OK" && pickAudioFormat(response) != null) return response
                attempts += "${client.clientName}=$status"
            } catch (e: Exception) {
                attempts += "${client.clientName}: ${e.message}"
            }
        }
        throw IllegalStateException("все клиенты отказали [${attempts.joinToString("; ")}]")
    }

    /** Лучший аудиопоток: URL + contentLength (нужен плееру для bounded Range). */
    fun pickAudioFormat(playerResponse: JSONObject): Pair<String, Long>? {
        val streamingData = playerResponse.optJSONObject("streamingData") ?: return null
        var best: Pair<Long, JSONObject>? = null
        for (key in listOf("adaptiveFormats", "formats")) {
            val formats = streamingData.optJSONArray(key) ?: continue
            for (i in 0 until formats.length()) {
                val format = formats.optJSONObject(i) ?: continue
                val url = format.optString("url", "")
                if (url.isEmpty() || !format.optString("mimeType").startsWith("audio/")) continue
                val bitrate = format.optLong("bitrate", 0L)
                if (best == null || bitrate > best.first) best = bitrate to format
            }
        }
        val format = best?.second ?: return null
        return format.optString("url") to format.optLong("contentLength", 0L)
    }

    /**
     * Сначала обычный запрос (HTTP/2). При сетевом сбое (таймаут, обрыв) — повтор
     * по HTTP/1.1. Ошибки HTTP (4xx/5xx) не ретраим: это ответ сервера, а не сети.
     */
    private fun execute(request: Request): JSONObject =
        try {
            doExecute(http, request)
        } catch (e: IOException) {
            doExecute(httpFallback, request)
        }

    private fun doExecute(client: OkHttpClient, request: Request): JSONObject =
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "YTM HTTP ${response.code}" }
            val body = response.body?.string() ?: error("YTM: пустой ответ")
            JSONObject(body)
        }

    private fun clientContext(
        clientName: String,
        clientVersion: String,
        androidSdkVersion: Int?,
        visitorData: String?,
    ): JSONObject {
        val client = JSONObject()
            .put("clientName", clientName)
            .put("clientVersion", clientVersion)
            // Русская локализация: имена настроений/жанров на Главной и в поиске.
            .put("hl", "ru")
            .put("gl", "US")
        if (androidSdkVersion != null) client.put("androidSdkVersion", androidSdkVersion)
        if (visitorData != null) client.put("visitorData", visitorData)
        return JSONObject().put("client", client)
    }

    private data class PlayerClient(
        val id: String,
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val androidSdkVersion: Int? = null,
    )

    companion object {
        const val BASE = "https://music.youtube.com/youtubei/v1"
        const val WEB_REMIX = "WEB_REMIX"
        const val WEB_REMIX_VERSION = "1.20250901.01.00"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // base64-protobuf фильтра «Песни» в поиске YT Music (значение в URL-кодировке).
        const val SEARCH_SONGS_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        // Фильтр «Альбомы».
        const val SEARCH_ALBUMS_PARAMS = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
        // Фильтр «Исполнители».
        const val SEARCH_ARTISTS_PARAMS = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"

        private val PLAYER_CLIENTS = listOf(
            PlayerClient(
                id = "3",
                clientName = "ANDROID",
                clientVersion = "20.10.38",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 15) gzip",
                androidSdkVersion = 35,
            ),
            PlayerClient(
                id = "5",
                clientName = "IOS",
                clientVersion = "20.10.4",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            ),
        )
    }
}
