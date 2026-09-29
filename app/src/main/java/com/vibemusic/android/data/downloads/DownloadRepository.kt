package com.vibemusic.android.data.downloads

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.data.db.DownloadEntity
import com.vibemusic.android.data.db.LibraryDao
import com.vibemusic.android.data.db.toTrack
import com.vibemusic.android.playback.PlaybackService
import com.vibemusic.android.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Активная загрузка для UI. */
data class ActiveDownload(val key: String, val title: String, val percent: Int)

/**
 * Оффлайн-загрузки: резолвим поток через плагин, качаем файл в папку приложения,
 * храним запись в Room. При воспроизведении [withDownloadedFile] подменяет стрим
 * на локальный файл — такие треки играют без интернета.
 */
@UnstableApi
class DownloadRepository(
    context: Context,
    private val dao: LibraryDao,
    private val registry: SourceRegistry,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _active = MutableStateFlow<List<ActiveDownload>>(emptyList())
    val activeDownloads: StateFlow<List<ActiveDownload>> = _active

    /** Скачанные (DONE) треки; playableUri указывает на локальный файл. */
    val downloadedTracks: Flow<List<Track>> = dao.downloads().map { list ->
        list.map { it.toTrack(Uri.fromFile(File(it.filePath)).toString()) }
    }

    fun download(track: Track) {
        val key = "${track.sourceId}:${track.id}"
        if (_active.value.any { it.key == key }) return
        scope.launch {
            setProgress(key, track.title, 0)
            runCatching {
                val plugin = registry.byId(track.sourceId) ?: error("Источник ${track.sourceId} недоступен")
                val resolved = plugin.resolvePlayable(track) ?: error("Поток не получен")
                val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "downloads")
                    .apply { mkdirs() }
                val ext = extensionOf(resolved.url, track.sourceId)
                val file = File(dir, "${key.replace(':', '_')}.$ext")

                dao.upsertDownload(
                    track.toDownloadEntity(file.absolutePath, state = STATE_DOWNLOADING),
                )

                // GVS отвечает 403 на полный GET без Range — качаем строго до длины файла.
                val builder = Request.Builder().url(resolved.url)
                    .header("User-Agent", PlaybackService.STREAM_USER_AGENT)
                if (resolved.contentLength > 0) {
                    builder.header("Range", "bytes=0-${resolved.contentLength - 1}")
                }
                http.newCall(builder.build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body ?: error("Пустой ответ")
                    val total = if (resolved.contentLength > 0) resolved.contentLength else body.contentLength()
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var done = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                done += read
                                if (total > 0) setProgress(key, track.title, ((done * 100) / total).toInt())
                            }
                        }
                    }
                }
                dao.updateDownloadState(key, STATE_DONE)
            }.onFailure { e ->
                dao.updateDownloadState(key, STATE_FAILED)
                android.util.Log.e("Downloads", "Скачивание не удалось: ${track.title}", e)
            }
            _active.value = _active.value.filterNot { it.key == key }
        }
    }

    fun delete(track: Track) {
        scope.launch {
            val key = "${track.sourceId}:${track.id}"
            dao.getDownload(key)?.let { entity ->
                File(entity.filePath).delete()
            }
            dao.removeDownload(key)
        }
    }

    /** Подменяет стрим на локальный файл для треков, которые уже скачаны. */
    suspend fun withDownloadedFiles(tracks: List<Track>): List<Track> = tracks.map { track ->
        val entity = dao.getDownload("${track.sourceId}:${track.id}")
        if (entity?.state == STATE_DONE) {
            track.copy(playableUri = Uri.fromFile(File(entity.filePath)).toString())
        } else {
            track
        }
    }

    private fun setProgress(key: String, title: String, percent: Int) {
        _active.value = _active.value.filterNot { it.key == key } + ActiveDownload(key, title, percent)
    }

    private fun extensionOf(url: String, sourceId: String): String = when {
        url.contains("webm") -> "webm"
        sourceId == "sc" -> "mp3"
        else -> "m4a"
    }

    private fun Track.toDownloadEntity(filePath: String, state: String): DownloadEntity = DownloadEntity(
        trackKey = "$sourceId:$id",
        sourceId = sourceId,
        trackId = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        artworkUri = artworkUri,
        quality = quality.name,
        format = format,
        shareUrl = shareUrl,
        filePath = filePath,
        state = state,
    )

    companion object {
        const val STATE_DOWNLOADING = "DOWNLOADING"
        const val STATE_DONE = "DONE"
        const val STATE_FAILED = "FAILED"
    }
}
