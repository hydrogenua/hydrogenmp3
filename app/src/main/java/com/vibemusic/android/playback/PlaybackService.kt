package com.vibemusic.android.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vibemusic.android.source.SourceRegistry
import com.vibemusic.android.widget.WidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Состояние для виджета на рабочем столе. */
data class WidgetState(val title: String, val artist: String, val isPlaying: Boolean)

/**
 * Фоновый плеер. Живёт в отдельном сервисе: система его не убивает во время
 * воспроизведения, а управление идёт через MediaController из любого места приложения.
 *
 * Стримы качаем через OkHttp (тот же стек, которым резолвим потоки) —
 * HttpURLConnection-стек Google флагает и отвечает 403.
 * Виджет на рабочем столе управляет плеером кастомными интентами (onStartCommand).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor(STREAM_USER_AGENT))
            .build()

        // DefaultDataSource раздаёт схемы: content:// (локальные файлы) сам,
        // а http(s) — через OkHttp-базу. VibeDataSource резолвит vibe://-очередь
        // и сжимает Range до границ файла для GVS.
        val registry = SourceRegistry(this)
        val dataSourceFactory = DataSource.Factory {
            VibeDataSource(
                registry,
                DefaultDataSource(this, OkHttpDataSource.Factory(okHttp).createDataSource()),
            )
        }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        this.player = player

        // Тап по островку/плееру на экране блокировки открывает приложение.
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.vibemusic.android.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()

        // Виджет на рабочем столе — обновляем при каждом изменении трека/состояния.
        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                publishWidgetState(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishWidgetState(player)
            }
        })
        publishWidgetState(player)
    }

    private fun publishWidgetState(player: ExoPlayer) {
        val metadata = player.currentMediaItem?.mediaMetadata
        widgetState.value = WidgetState(
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            isPlaying = player.isPlaying,
        )
        WidgetUpdater.update(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WIDGET_PREV -> player?.seekToPreviousMediaItem()
            ACTION_WIDGET_TOGGLE -> player?.let { if (it.isPlaying) it.pause() else it.play() }
            ACTION_WIDGET_NEXT -> player?.seekToNextMediaItem()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }

    private class UserAgentInterceptor(private val userAgent: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
            return chain.proceed(request)
        }
    }

    companion object {
        const val STREAM_USER_AGENT = "com.google.android.youtube/20.10.38 (Linux; U; Android 15) gzip"
        const val ACTION_WIDGET_PREV = "com.vibemusic.android.widget.PREV"
        const val ACTION_WIDGET_TOGGLE = "com.vibemusic.android.widget.TOGGLE"
        const val ACTION_WIDGET_NEXT = "com.vibemusic.android.widget.NEXT"

        /** Последнее известное состояние для отрисовки виджета. */
        val widgetState = MutableStateFlow<WidgetState?>(null)
    }
}
