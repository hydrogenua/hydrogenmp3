package com.vibemusic.android.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.vibemusic.android.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Мост UI → фоновый плеер. UI знает только про этот класс, не про ExoPlayer.
 *
 * Очередь — нативный плейлист ExoPlayer: стриминговые треки стоят в нём под
 * служебными ссылками vibe://<source>/<id>, которые VibeDataSource резолвит
 * в момент их очереди. След./пред. и автопереход работают и в уведомлении.
 */
class PlayerConnection(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _nowPlaying = MutableStateFlow<Track?>(null)
    val nowPlaying: StateFlow<Track?> = _nowPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _repeatOne = MutableStateFlow(false)
    val repeatOne: StateFlow<Boolean> = _repeatOne.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Уведомляет UI о смене текущего трека в очереди (mediaId вида «source:id»). */
    var onMediaItemChanged: ((String) -> Unit)? = null

    /** Финальная ошибка воспроизведения трека (после всех ретраев) — для фолбэка на другие источники. */
    var onPlaybackFailed: ((Track, String) -> Unit)? = null

    private var controller: MediaController? = null
    private var pendingQueue: Pair<List<Track>, Int>? = null

    /** Зеркало очереди плеера для UI: треки в текущем порядке + индекс играющего. */
    private val _queueTracks = MutableStateFlow<List<Track>>(emptyList())
    val queueTracks: StateFlow<List<Track>> = _queueTracks.asStateFlow()
    private val _queuePosition = MutableStateFlow(-1)
    val queuePosition: StateFlow<Int> = _queuePosition.asStateFlow()
    private val mediaIdToTrack = mutableMapOf<String, Track>()
    private var streamRetryCount = 0

    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
    ).buildAsync()

    init {
        controllerFuture.addListener(
            {
                val c = controllerFuture.get()
                controller = c
                _volume.value = c.volume
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        if (isPlaying) _error.value = null
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            val d = c.duration
                            if (d > 0) _durationMs.value = d
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val mediaId = mediaItem?.mediaId ?: return
                        mediaIdToTrack[mediaId]?.let { track ->
                            _nowPlaying.value = track
                            _durationMs.value = track.durationMs
                            _positionMs.value = 0L
                        }
                        streamRetryCount = 0
                        onMediaItemChanged?.invoke(mediaId)
                    }

                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        refreshQueue()
                    }

                    override fun onRepeatModeChanged(repeatMode: Int) {
                        _repeatOne.value = repeatMode == Player.REPEAT_MODE_ONE
                    }

                    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                        _isShuffle.value = enabled
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // Ссылки GVS привязаны к выходному IP и живут ограниченное время:
                        // при сетевой ошибке просто пере-открываем текущий элемент —
                        // vibe:// перерезолвится свежей ссылкой.
                        val retriable = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        if (retriable && streamRetryCount < MAX_STREAM_RETRIES) {
                            streamRetryCount++
                            Log.w(TAG, "Источник ${error.errorCodeName}, ретрай #$streamRetryCount")
                            scope.launch {
                                withContext(Dispatchers.Main) {
                                    c.prepare()
                                    c.play()
                                }
                            }
                            return
                        }
                        val failedTrack = mediaIdToTrack[c.currentMediaItem?.mediaId]
                        _error.value = "${error.errorCodeName}: ${error.message ?: "ошибка воспроизведения"}"
                        _isPlaying.value = false
                        failedTrack?.let { onPlaybackFailed?.invoke(it, _error.value ?: "") }
                    }
                })
                pendingQueue?.let { (tracks, index) -> playQueue(tracks, index) }
                pendingQueue = null
            },
            ContextCompat.getMainExecutor(appContext),
        )

        // Позицию плеер не пушит — опрашиваем, пока соединение живо.
        // ВАЖНО: MediaController можно вызывать только с main-потока.
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                val c = controller
                if (c != null) {
                    if (c.isPlaying) _positionMs.value = c.currentPosition
                    val d = c.duration
                    if (d > 0) _durationMs.value = d
                }
                delay(500)
            }
        }
    }

    /** Ставит очередь целиком; startIndex — с какого трека начать. */
    fun playQueue(tracks: List<Track>, startIndex: Int) {
        val c = controller ?: run {
            pendingQueue = tracks to startIndex
            return
        }
        mediaIdToTrack.clear()
        tracks.forEach { mediaIdToTrack["${it.sourceId}:${it.id}"] = it }
        _nowPlaying.value = tracks.getOrNull(startIndex)
        _durationMs.value = tracks.getOrNull(startIndex)?.durationMs ?: 0L
        _positionMs.value = 0L
        _error.value = null
        streamRetryCount = 0
        scope.launch(Dispatchers.Main) {
            val items = tracks.mapNotNull { it.toMediaItem() }
            if (items.isEmpty()) return@launch
            c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
            c.prepare()
            c.play()
            refreshQueue()
        }
    }

    /** Перечитывает очередь контроллера в UI-флоу (вызывать на изменениях таймлайна). */
    private fun refreshQueue() {
        val c = controller ?: return
        _queueTracks.value = (0 until c.mediaItemCount).mapNotNull { mediaIdToTrack[c.getMediaItemAt(it).mediaId] }
        _queuePosition.value = c.currentMediaItemIndex
    }

    /** Прыгает на трек очереди по индексу. */
    fun playQueueIndex(index: Int) {
        val c = controller ?: return
        scope.launch(Dispatchers.Main) {
            if (index in 0 until c.mediaItemCount) {
                c.seekToDefaultPosition(index)
                c.play()
            }
        }
    }

    /** Переставляет трек очереди (drag-сортировка). */
    fun moveInQueue(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to || from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount) return
        scope.launch(Dispatchers.Main) {
            c.moveMediaItem(from, to)
            refreshQueue()
        }
    }

    fun nextMedia() {
        val c = controller ?: return
        scope.launch(Dispatchers.Main) {
            if (c.hasNextMediaItem()) c.seekToNextMediaItem()
        }
    }

    fun previousMedia() {
        val c = controller ?: return
        scope.launch(Dispatchers.Main) {
            if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem()
        }
    }

    fun toggleShuffle() {
        val c = controller ?: return
        scope.launch(Dispatchers.Main) { c.shuffleModeEnabled = !c.shuffleModeEnabled }
    }

    fun togglePlayback() {
        val c = controller ?: return
        scope.launch(Dispatchers.Main) {
            if (c.isPlaying) c.pause() else c.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        scope.launch(Dispatchers.Main) { c.seekTo(positionMs.coerceAtLeast(0L)) }
    }

    fun toggleRepeat() {
        val c = controller ?: return
        scope.launch(Dispatchers.Main) {
            c.repeatMode =
                if (c.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF
                else Player.REPEAT_MODE_ONE
        }
    }

    fun setVolume(value: Float) {
        val c = controller ?: return
        val clamped = value.coerceIn(0f, 1f)
        _volume.value = clamped
        scope.launch(Dispatchers.Main) { c.setVolume(clamped) }
    }

    fun release() {
        scope.cancel()
        MediaController.releaseFuture(controllerFuture)
    }

    /** Показывает ошибку воспроизведения в UI (для фолбэка из VM). */
    fun showError(message: String) {
        _error.value = message
        _isPlaying.value = false
    }

    private fun Track.toMediaItem(): MediaItem {
        // Стриминговые треки без прямого uri встают в очередь под vibe://-ссылкой —
        // VibeDataSource резолвит её, когда до трека дойдёт очередь.
        val uri: Uri = playableUri?.let { Uri.parse(it) } ?: Uri.parse("vibe://$sourceId/$id")
        return MediaItem.Builder()
            .setMediaId("$sourceId:$id")
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri?.let { Uri.parse(it) })
                    .build(),
            )
            .build()
    }

    private companion object {
        const val TAG = "PlayerConnection"
        const val MAX_STREAM_RETRIES = 2
    }
}
