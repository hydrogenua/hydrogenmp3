package com.vibemusic.android.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.Artwork
import com.vibemusic.android.ui.theme.Accent

/**
 * Статистика прослушиваний: за неделю, топ исполнителей и топ треков
 * с полосами относительной популярности.
 */
@Composable
fun StatsScreen(viewModel: PlayerViewModel) {
    val stats by viewModel.stats.collectAsState(initial = null)
    val s = stats ?: return

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "title") {
            Text(
                "Статистика",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
            )
        }
        item(key = "week") {
            Text(
                "За последние 7 дней — ${s.weekPlays} ${plural(s.weekPlays)}, всего ${s.totalPlays}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item(key = "artists_h") {
            Text(
                "Топ исполнителей",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
            )
        }
        if (s.topArtists.isEmpty()) {
            item(key = "artists_empty") {
                Text(
                    "Пока пусто — включи что-нибудь из Поиска.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            items(s.topArtists, key = { "a_" + it.first }) { (artist, count) ->
                StatBar(
                    rank = s.topArtists.indexOfFirst { it.first == artist } + 1,
                    title = artist,
                    subtitle = "$count ${plural(count)}",
                    fraction = count.toFloat() / s.topArtists.first().second,
                )
            }
        }
        item(key = "tracks_h") {
            Text(
                "Топ треков",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
            )
        }
        items(s.topTracks, key = { "t_" + it.first.sourceId + it.first.id }) { (track, count) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Artwork(uri = track.artworkUri, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "$count×",
                    style = MaterialTheme.typography.titleMedium,
                    color = Accent,
                )
            }
        }
        item(key = "spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatBar(rank: Int, title: String, subtitle: String, fraction: Float) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$rank",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp)
                .height(5.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f), RoundedCornerShape(3.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                    .height(5.dp)
                    .background(Accent, RoundedCornerShape(3.dp)),
            )
        }
    }
}

private fun plural(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "прослушивание"
    n % 10 in 2..4 && (n % 100 < 10 || n % 100 >= 20) -> "прослушивания"
    else -> "прослушиваний"
}
