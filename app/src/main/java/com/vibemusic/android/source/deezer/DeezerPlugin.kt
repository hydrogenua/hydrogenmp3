package com.vibemusic.android.source.deezer

import android.content.Context
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.source.ResolvedStream
import com.vibemusic.android.source.SourcePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Источник Deezer. Поиск работает анонимно; полные потоки требуют ARL
 * (cookie аккаунта Deezer) — вводится один раз в библиотеке, хранится локально.
 * Качество — MP3 128 kbps (расшифрованный stripe-поток).
 */
class DeezerPlugin(context: Context) : SourcePlugin {

    private val client = DeezerClient(context)

    override val id = "deezer"
    override val title = "Deezer"

    override suspend fun search(query: String, limit: Int): List<Track> =
        withContext(Dispatchers.IO) { client.search(query, limit) }

    override suspend fun resolvePlayable(track: Track): ResolvedStream =
        withContext(Dispatchers.IO) { client.resolveStream(track) }
}
