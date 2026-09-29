package com.vibemusic.android.source.sc

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Клиент публичного API SoundCloud (api-v2) — того же, каким пользуется веб-плеер.
 *
 * client_id разработчикам напрямую не выдают: достаём его из JS-бандлов
 * soundcloud.com (стандартная практика open-source клиентов вроде NewPipe).
 * Устаревший ключ лечится переполучением.
 */
class SoundCloudClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedClientId: String? = null

    private val clientId: String
        get() = cachedClientId ?: fetchClientId().also { cachedClientId = it }

    /** Поиск треков. При 401/403 (устаревший client_id) обновляем ключ и пробуем снова. */
    fun search(query: String, limit: Int): JSONArray =
        recoverStaleId {
            api("search/tracks?q=${urlEncode(query)}&limit=$limit")
        }.optJSONArray("collection") ?: JSONArray()

    /** Прямой URL аудио: предпочитаем progressive mp3, иначе HLS-плейлист. */
    fun resolveStream(trackId: String): String? {
        val track = recoverStaleId { api("tracks/$trackId") }
        val transcodings = track.optJSONObject("media")?.optJSONArray("transcodings") ?: return null
        var hlsMediaUrl: String? = null
        for (i in 0 until transcodings.length()) {
            val transcoding = transcodings.optJSONObject(i) ?: continue
            val mediaUrl = transcoding.optString("url").takeIf { it.isNotEmpty() } ?: continue
            when (transcoding.optJSONObject("format")?.optString("protocol")) {
                "progressive" -> return resolveMedia(mediaUrl)
                "hls" -> if (hlsMediaUrl == null) hlsMediaUrl = mediaUrl
            }
        }
        return hlsMediaUrl?.let(::resolveMedia)
    }

    private fun resolveMedia(mediaUrl: String): String? {
        val path = mediaUrl.removePrefix("$API/")
        return api(path).optString("url").takeIf { it.isNotEmpty() }
    }

    private inline fun <T> recoverStaleId(block: () -> T): T =
        try {
            block()
        } catch (e: StaleClientIdException) {
            // Пауза: 403 бывает и от rate-limit VPN-выхода, свежий ключ сразу могут снова не отдать.
            cachedClientId = null
            Thread.sleep(1_500)
            block()
        }

    private fun api(path: String): JSONObject {
        val separator = if (path.contains('?')) "&" else "?"
        return getJson("$API/$path${separator}client_id=$clientId")
    }

    /** Ищет client_id в JS-бандлах веб-плеера soundcloud.com. */
    private fun fetchClientId(): String {
        val html = getString("https://soundcloud.com/")
        val scripts = SCRIPT_REGEX.findAll(html).map { it.value }.distinct().toList()
        for (script in scripts) {
            val js = runCatching { getString(script) }.getOrNull() ?: continue
            val match = CLIENT_ID_REGEX.find(js) ?: continue
            return match.groupValues[1]
        }
        error("SoundCloud: client_id не найден")
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw StaleClientIdException("SoundCloud HTTP ${response.code}")
            }
            check(response.isSuccessful) { "SoundCloud HTTP ${response.code}" }
            JSONObject(response.body?.string() ?: error("SoundCloud: пустой ответ"))
        }
    }

    private fun getString(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "SoundCloud HTTP ${response.code}" }
            response.body?.string() ?: error("SoundCloud: пустой ответ")
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private class StaleClientIdException(message: String) : IOException(message)

    private companion object {
        const val API = "https://api-v2.soundcloud.com"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        val SCRIPT_REGEX = Regex("""https://a-v2\.sndcdn\.com/assets/[^"'\\]+\.js""")
        val CLIENT_ID_REGEX = Regex("""client_id\s*[:=]\s*"([a-zA-Z0-9]{20,})"""")
    }
}
