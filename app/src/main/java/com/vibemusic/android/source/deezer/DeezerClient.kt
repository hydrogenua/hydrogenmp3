package com.vibemusic.android.source.deezer

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.source.ResolvedStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.engines.BlowfishEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val Context.deezerDataStore by preferencesDataStore(name = "deezer_settings")
private val ARL_KEY = stringPreferencesKey("deezer_arl")

/**
 * Клиент Deezer. Поиск — анонимный публичный API; полные потоки — через
 * gw-light сессию по ARL пользователя (cookie аккаунта) и расшифровку
 * stripe-схемы Blowfish CBC (каждый третий 2048-байтный блок).
 */
class DeezerClient(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gwBase = "https://www.deezer.com/ajax/gw-light.php"

    fun arlFlow(): Flow<String> = context.deezerDataStore.data.map { it[ARL_KEY] ?: "" }

    suspend fun setArl(arl: String) {
        context.deezerDataStore.edit { it[ARL_KEY] = arl.trim() }
    }

    suspend fun arl(): String = arlFlow().first()

    suspend fun search(query: String, limit: Int = 30): List<Track> = withContext(Dispatchers.IO) {
        val url = "https://api.deezer.com/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=$limit"
        val data = JSONObject(httpGet(url)).optJSONArray("data") ?: return@withContext emptyList()
        val tracks = mutableListOf<Track>()
        for (i in 0 until data.length()) {
            val t = data.optJSONObject(i) ?: continue
            tracks += Track(
                id = t.optString("id"),
                sourceId = "deezer",
                title = t.optString("title"),
                artist = t.optJSONObject("artist")?.optString("name").orEmpty().ifBlank { "Неизвестный исполнитель" },
                album = t.optJSONObject("album")?.optString("title"),
                durationMs = t.optLong("duration", 0L) * 1000,
                artworkUri = t.optJSONObject("album")?.optString("cover_big")
                    ?: t.optJSONObject("album")?.optString("cover_medium"),
                shareUrl = t.optString("link").takeIf { it.isNotEmpty() },
            )
        }
        tracks
    }

    /** Резолв полного потока: требует подключённого ARL. Результат — расшифрованный mp3-файл. */
    suspend fun resolveStream(track: Track): ResolvedStream = withContext(Dispatchers.IO) {
        val arl = arl()
        check(arl.isNotBlank()) { "Deezer не подключён: введи ARL в библиотеке" }

        val checkForm = session(arl)
        val song = gw(
            "song.getData",
            checkForm,
            arl,
            JSONObject().put("sng_id", track.id),
        ).optJSONObject("results") ?: error("song.getData пуст")

        val licenseToken = song.optString("TRACK_TOKEN")
        check(licenseToken.isNotBlank()) { "Deezer: трек недоступен для этого аккаунта" }

        val mediaReq = Request.Builder()
            .url("https://media.deezer.com/v1/get_url")
            .header("User-Agent", UA)
            .post(
                JSONObject()
                    .put("license_token", licenseToken)
                    .put(
                        "media",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("type", "FULL")
                                .put(
                                    "formats",
                                    org.json.JSONArray()
                                        .put(JSONObject().put("cipher", "BF_CBC_STRIPE").put("format", "MP3_128"))
                                        .put(JSONObject().put("cipher", "BF_CBC_STRIPE").put("format", "MP3_64"))
                                        .put(JSONObject().put("cipher", "BF_CBC_STRIPE").put("format", "MP3_32")),
                                ),
                        ),
                    )
                    .toString()
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()
        http.newCall(mediaReq).execute().use { resp ->
            val body = resp.body?.string() ?: error("media: пустой ответ")
            check(resp.isSuccessful) { "media: HTTP ${resp.code} — $body" }
            val cdnUrl = JSONObject(body)
                .optJSONArray("data")?.optJSONObject(0)
                ?.optJSONArray("media")?.optJSONObject(0)
                ?.optJSONArray("sources")?.optJSONObject(0)
                ?.optString("url")
                .takeIf { !it.isNullOrEmpty() } ?: error("media: url не получен")

            val raw = httpGetBytes(cdnUrl)
            val decrypted = stripeDecrypt(raw, track.id)

            val dir = File(context.cacheDir, "deezer").apply { mkdirs() }
            val file = File(dir, "${track.id}.mp3")
            file.writeBytes(decrypted)
            ResolvedStream(Uri.fromFile(file).toString(), 0L)
        }
    }

    private fun session(arl: String): String {
        val body = gwCall("deezer.getUserData", "", arl, "{}")
        val results = JSONObject(body).optJSONObject("results") ?: error("Deezer: нет сессии")
        val userId = results.optJSONObject("USER")?.optLong("USER_ID", 0L) ?: 0L
        check(userId != 0L) { "Deezer: ARL не подошёл — перекопируй cookie целиком" }
        return results.optString("checkForm")
    }

    private fun gw(method: String, apiToken: String, arl: String, payload: JSONObject): JSONObject {
        val body = gwCall(method, apiToken, arl, payload.toString())
        return JSONObject(body)
    }

    private fun gwCall(method: String, apiToken: String, arl: String, payload: String): String {
        val request = Request.Builder()
            .url("$gwBase?method=$method&input=3&api_version=1.0&api_token=${URLEncoder.encode(apiToken, "UTF-8")}")
            .header("User-Agent", UA)
            .header("Cookie", "arl=$arl")
            .header("Origin", "https://www.deezer.com")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "Deezer gw: HTTP ${resp.code}" }
            return resp.body?.string() ?: error("Deezer gw: пустой ответ")
        }
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        return http.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "Deezer HTTP ${resp.code}" }
            resp.body?.string() ?: error("Deezer: пустой ответ")
        }
    }

    private fun httpGetBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        return http.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "Deezer CDN HTTP ${resp.code}" }
            resp.body?.bytes() ?: error("Deezer CDN: пустой ответ")
        }
    }

    private fun stripeDecrypt(raw: ByteArray, trackId: String): ByteArray {
        val md5hex = java.security.MessageDigest.getInstance("MD5")
            .digest(trackId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val secret = "g4el58wc0zvf9na1"
        val key = ByteArray(16) { i ->
            (md5hex[i].code xor md5hex[i + 16].code xor secret[i].code).toByte()
        }

        val cipher = CBCBlockCipher(BlowfishEngine())
        cipher.init(false, ParametersWithIV(KeyParameter(key), ByteArray(8) { it.toByte() }))

        val out = ByteArray(raw.size)
        var offset = 0
        var blockIndex = 0
        while (offset < raw.size) {
            val len = minOf(2048, raw.size - offset)
            val chunk = raw.copyOfRange(offset, offset + len)
            if (blockIndex % 3 == 0 && len == 2048) {
                val decrypted = ByteArray(2048)
                cipher.processBlock(chunk, 0, decrypted, 0)
                decrypted.copyInto(out, offset)
            } else {
                chunk.copyInto(out, offset)
            }
            offset += len
            blockIndex++
        }
        return out
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
