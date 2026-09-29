package com.vibemusic.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.vibemusic.android.R
import com.vibemusic.android.playback.PlaybackService
import com.vibemusic.android.playback.WidgetState

/**
 * Виджет на рабочий стол: название/исполнитель + пред/play/след.
 * Кнопки шлют кастомные интенты в PlaybackService — он управляет своим плеером
 * напрямую и дергает [WidgetUpdater] при любых изменениях состояния.
 */
class VibeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.update(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (val action = intent.action) {
            PlaybackService.ACTION_WIDGET_PREV,
            PlaybackService.ACTION_WIDGET_TOGGLE,
            PlaybackService.ACTION_WIDGET_NEXT,
            -> runCatching { context.startService(widgetIntent(context, action)) }
        }
    }

    private fun widgetIntent(context: Context, action: String): Intent =
        Intent(context, PlaybackService::class.java).setAction(action)
}

object WidgetUpdater {

    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, VibeWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val state = PlaybackService.widgetState.value
        val title = state?.title?.takeIf { it.isNotBlank() } ?: "hydrogen"
        val artist = state?.artist?.takeIf { it.isNotBlank() } ?: "Ничего не играет"
        val views = RemoteViews(context.packageName, R.layout.widget_vibe).apply {
            setTextViewText(R.id.widget_title, title)
            setTextViewText(R.id.widget_artist, artist)
            setImageViewResource(
                R.id.widget_toggle,
                if (state?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            )
            setOnClickPendingIntent(R.id.widget_prev, pending(context, PlaybackService.ACTION_WIDGET_PREV))
            setOnClickPendingIntent(R.id.widget_toggle, pending(context, PlaybackService.ACTION_WIDGET_TOGGLE))
            setOnClickPendingIntent(R.id.widget_next, pending(context, PlaybackService.ACTION_WIDGET_NEXT))
        }
        manager.updateAppWidget(ids, views)
    }

    private fun pending(context: Context, action: String): PendingIntent = PendingIntent.getService(
        context,
        action.hashCode(),
        Intent(context, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
