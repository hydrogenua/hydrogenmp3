package com.vibemusic.android.source.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.vibemusic.android.core.model.Quality
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.source.SourcePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Источник №0 — локальные аудиофайлы устройства через MediaStore.
 * Работает офлайн и даёт первое работающее воспроизведение, пока не готов стриминг.
 */
class LocalMediaStorePlugin(private val context: Context) : SourcePlugin {

    override val id = "local"
    override val title = "На устройстве"

    override suspend fun search(query: String, limit: Int): List<Track> =
        withContext(Dispatchers.IO) { queryTracks(query, limit) }

    override suspend fun home(limit: Int): List<Track> =
        withContext(Dispatchers.IO) { queryTracks(null, limit) }

    private fun queryTracks(query: String?, limit: Int): List<Track> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
        )
        // Отсекаем короткие звуки (уведомления, рингтоны).
        val selection: String
        val args: Array<String>
        if (query.isNullOrBlank()) {
            selection = "${MediaStore.Audio.Media.DURATION} >= ?"
            args = arrayOf(MIN_DURATION_MS.toString())
        } else {
            selection = "${MediaStore.Audio.Media.DURATION} >= ? AND ${MediaStore.Audio.Media.TITLE} LIKE ?"
            args = arrayOf(MIN_DURATION_MS.toString(), "%$query%")
        }

        val tracks = mutableListOf<Track>()
        context.contentResolver.query(
            collection,
            projection,
            selection,
            args,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext() && tracks.size < limit) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val ext = cursor.getString(dataCol)
                    ?.substringAfterLast('.', "")
                    ?.lowercase()
                    .orEmpty()
                val lossless = ext in LOSSLESS_EXTENSIONS
                tracks += Track(
                    id = id.toString(),
                    sourceId = this@LocalMediaStorePlugin.id,
                    title = cursor.getString(titleCol) ?: "Без названия",
                    artist = cursor.getString(artistCol)
                        ?.takeIf { it != MediaStore.UNKNOWN_STRING }
                        ?: "Неизвестный исполнитель",
                    album = cursor.getString(albumCol),
                    durationMs = cursor.getLong(durationCol),
                    artworkUri = ContentUris.withAppendedId(ALBUM_ART_URI, albumId).toString(),
                    playableUri = ContentUris.withAppendedId(collection, id).toString(),
                    quality = if (lossless) Quality.LOSSLESS else Quality.LOSSY,
                    format = if (lossless) ext.uppercase() else null,
                )
            }
        }
        return tracks
    }

    private companion object {
        const val MIN_DURATION_MS = 30_000L
        val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")
        val LOSSLESS_EXTENSIONS = setOf("flac", "wav", "wave", "alac", "aiff", "aif", "wv")
    }
}
