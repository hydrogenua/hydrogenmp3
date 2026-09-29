package com.vibemusic.android.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.TrackRow

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    name: String,
    viewModel: PlayerViewModel,
    onLongPressTrack: (Track) -> Unit,
    onBack: () -> Unit,
) {
    val tracks by viewModel.playlistTracks(playlistId).collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text(
                name,
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            )
        }
        if (tracks.isEmpty()) {
            Text(
                "Пусто. Найди треки в поиске и долгим тапом добавь сюда.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn {
                items(tracks, key = { it.sourceId + it.id }) { track ->
                    TrackRow(track, onClick = { viewModel.play(track, tracks) }, onLongClick = { onLongPressTrack(track) })
                }
            }
        }
    }
}
