package com.vibemusic.android.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibemusic.android.R
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.TrackRow

@Composable
private fun ScreenTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun FavoritesScreen(viewModel: PlayerViewModel, onLongPressTrack: (com.vibemusic.android.core.model.Track) -> Unit) {
    val favorites by viewModel.favoriteTracks.collectAsState(initial = emptyList())
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle(stringResource(R.string.menu_favorites)) }
        if (favorites.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.fav_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(favorites, key = { it.sourceId + it.id }) { track ->
                TrackRow(track, onClick = { viewModel.play(track, favorites) }, onLongClick = { onLongPressTrack(track) })
            }
        }
    }
}

@Composable
fun DownloadsScreen(viewModel: PlayerViewModel, onLongPressTrack: (com.vibemusic.android.core.model.Track) -> Unit) {
    val downloads by viewModel.downloadedTracks.collectAsState(initial = emptyList())
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle(stringResource(R.string.menu_downloads)) }
        if (downloads.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.dl_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(downloads, key = { it.sourceId + it.id }) { track ->
                TrackRow(track, onClick = { viewModel.play(track, downloads) }, onLongClick = { onLongPressTrack(track) })
            }
        }
    }
}

@Composable
fun HistoryScreen(
    viewModel: PlayerViewModel,
    onOpenStats: () -> Unit,
    onLongPressTrack: (com.vibemusic.android.core.model.Track) -> Unit,
) {
    val history by viewModel.history.collectAsState(initial = emptyList())
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            ) {
                Text(
                    stringResource(R.string.menu_history),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.weight(1f).padding(top = 24.dp, bottom = 8.dp),
                )
                TextButton(onClick = onOpenStats) { Text(stringResource(R.string.hist_stats)) }
                if (history.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearHistory() }) { Text(stringResource(R.string.hist_clear)) }
                }
            }
        }
        if (history.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.hist_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(history.take(50), key = { "h" + it.sourceId + it.id + it.hashCode() }) { track ->
                TrackRow(track, onClick = { viewModel.play(track, history) }, onLongClick = { onLongPressTrack(track) })
            }
        }
    }
}
