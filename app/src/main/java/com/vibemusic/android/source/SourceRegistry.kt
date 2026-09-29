package com.vibemusic.android.source

import android.content.Context
import com.vibemusic.android.source.deezer.DeezerPlugin
import com.vibemusic.android.source.local.LocalMediaStorePlugin
import com.vibemusic.android.source.sc.SoundCloudPlugin
import com.vibemusic.android.source.ytm.YtMusicPlugin

/**
 * Реестр всех подключённых источников. Новый источник добавляется одной строкой.
 *
 * TODO(P5): VkPlugin.
 */
class SourceRegistry(context: Context) {

    private val plugins: List<SourcePlugin> = listOf(
        LocalMediaStorePlugin(context),
        YtMusicPlugin(context),
        SoundCloudPlugin(),
        DeezerPlugin(context),
    )

    val all: List<SourcePlugin> get() = plugins

    fun byId(id: String): SourcePlugin? = plugins.firstOrNull { it.id == id }
}
