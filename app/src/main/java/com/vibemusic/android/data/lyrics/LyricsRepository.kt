package com.vibemusic.android.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Тексты песен из lrclib.org — открытый API без ключей, ноль бэкенда.
 * Сначала точный запрос (артист+трек+длительность), затем поиск как запас.
 */
object LyricsRepository {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cache = HashMap<String, String?>()

    suspend fun fetch(artist: String, title: String, durationSec: Long): String? =
        withContext(Dispatchers.IO) {
            val key = "$artist|$title"
            synchronized(cache) { cache[key] }?.let { return@withContext it }
            val result = runCatching {
                fetchExact(artist, title, durationSec)
                    ?: fetchSearch("$artist $title")
            }.getOrNull()
            synchronized(cache) { cache[key] = result }
            result
        }

    private fun fetchExact(artist: String, title: String, durationSec: Long): String? {
        val url = buildString {
            append("https://lrclib.net/api/get?artist_name="); append(enc(artist))
            append("&track_name="); append(enc(title))
            if (durationSec > 0) { append("&duration="); append(durationSec) }
        }
        return (getJson(url) as? JSONObject)?.let { plainOf(it) }
    }

    private fun fetchSearch(query: String): String? {
        val url = "https://lrclib.net/api/search?q=" + enc(query)
        val arr = getJson(url) as? JSONArray ?: return null
        for (i in 0 until arr.length()) {
            plainOf(arr.optJSONObject(i) ?: continue)?.let { return it }
        }
        return null
    }

    /** plainLyrics как строка; JSONObject.NULL и пустоту трактуем как «нет». */
    private fun plainOf(obj: JSONObject): String? {
        val plain = obj.opt("plainLyrics")
        return (plain as? String)?.takeIf { it.isNotBlank() }
    }

    private fun getJson(url: String): Any? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "hydrogen-app/0.1")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return runCatching {
                val trimmed = body.trimStart()
                if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
            }.getOrNull()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
