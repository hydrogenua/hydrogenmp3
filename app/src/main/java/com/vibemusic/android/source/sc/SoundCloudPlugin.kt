package com.vibemusic.android.source.sc

import com.vibemusic.android.core.model.Quality
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.source.ResolvedStream
import com.vibemusic.android.source.SourcePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Источник SoundCloud: поиск и стримы через публичный api-v2.
 * Стрим — progressive mp3 128 kbps, для треков без него — HLS-плейлист.
 * Обложки тянет Coil напрямую (artwork_url, апгрейдим размер до t500x500).
 */
class SoundCloudPlugin : SourcePlugin {

    override val id = "sc"
    override val title = "SoundCloud"

    private val client = SoundCloudClient()

    override suspend fun search(query: String, limit: Int): List<Track> =
        withContext(Dispatchers.IO) { parseTracks(client.search(query, limit)) }

    override suspend fun resolvePlayable(track: Track): ResolvedStream? =
        withContext(Dispatchers.IO) { client.resolveStream(track.id)?.let { ResolvedStream(it, 0L) } }

    internal fun parseTracks(collection: JSONArray): List<Track> {
        val tracks = mutableListOf<Track>()
        for (i in 0 until collection.length()) {
            val item = collection.optJSONObject(i) ?: continue
            if (item.optString("kind") != "track") continue
            parseTrack(item)?.let { tracks += it }
        }
        return tracks
    }

    private fun parseTrack(item: JSONObject): Track? {
        val trackId = item.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val title = item.optString("title").takeIf { it.isNotEmpty() } ?: return null
        val artist = item.optJSONObject("publisher_metadata")?.optString("artist")?.takeIf { it.isNotEmpty() }
            ?: item.optJSONObject("user")?.optString("username")?.takeIf { it.isNotEmpty() }
            ?: "Неизвестный исполнитель"
        val artwork = item.optString("artwork_url").takeIf { it.isNotEmpty() }
            ?.replace("-large.", "-t500x500.")

        return Track(
            id = trackId,
            sourceId = this@SoundCloudPlugin.id,
            title = title,
            artist = artist,
            durationMs = item.optLong("duration", 0L),
            artworkUri = artwork,
            playableUri = null,
            quality = Quality.LOSSY,
            shareUrl = item.optString("permalink_url").takeIf { it.isNotEmpty() },
        )
    }
}
