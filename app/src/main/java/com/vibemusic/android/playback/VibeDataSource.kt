package com.vibemusic.android.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.source.SourceRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Источник-диспетчер. Ссылки вида vibe://<source>/<id> (стриминговые треки
 * в очереди плеера) лениво резолвит в прямой поток в момент открытия —
 * в потоке загрузки ExoPlayer, поэтому блокировка потока тут допустима.
 *
 * После резолва сжимает Range до границ файла (GVS отвечает 403
 * на открытые Range-запросы). Локальные треки (content://) идут насквозь.
 */
@UnstableApi
class VibeDataSource(
    private val registry: SourceRegistry,
    private val upstream: DataSource,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        var spec = dataSpec
        if (spec.uri.scheme.equals("vibe", ignoreCase = true)) {
            val sourceId = spec.uri.host ?: throw IOException("vibe: источник не указан")
            val trackId = spec.uri.pathSegments.firstOrNull() ?: throw IOException("vibe: id не указан")
            val plugin = registry.byId(sourceId) ?: throw IOException("vibe: неизвестный источник $sourceId")
            val resolved = runBlocking {
                withTimeout(45_000) {
                    plugin.resolvePlayable(Track(id = trackId, sourceId = sourceId, title = "", artist = ""))
                }
            } ?: throw IOException("поток не получен ($sourceId)")
            StreamLengths.put(resolved.url, resolved.contentLength)
            spec = spec.buildUpon().setUri(resolved.url).build()
        }

        val cl = StreamLengths.get(spec.uri.toString())
        val adjusted = if (cl != null && cl > spec.position &&
            (spec.length == C.LENGTH_UNSET.toLong() || spec.position + spec.length > cl)
        ) {
            spec.buildUpon().setLength(cl - spec.position).build()
        } else {
            spec
        }
        android.util.Log.d("VibeDataSource", "open ${adjusted.uri.host} pos=${adjusted.position} len=${adjusted.length}")
        return upstream.open(adjusted)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
