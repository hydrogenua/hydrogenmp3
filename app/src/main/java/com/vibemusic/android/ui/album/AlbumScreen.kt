package com.vibemusic.android.ui.album

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.TrackRow

@Composable
fun AlbumScreen(
    albumId: String,
    viewModel: PlayerViewModel,
    onLongPressTrack: (Track) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(albumId) { viewModel.openAlbumById(albumId) }

    val albumUi by viewModel.albumUi.collectAsState()
    val album = albumUi?.album
    val tracks = albumUi?.tracks ?: emptyList()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text(
                album?.title ?: "Альбом",
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            )
        }
        album?.subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (albumUi?.loading == true) {
            CircularProgressIndicator(Modifier.padding(24.dp))
        } else if (tracks.isEmpty()) {
            Text(
                "Не удалось загрузить треки альбома.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            TextButton(onClick = { viewModel.playAlbum() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Играть альбом")
            }
            LazyColumn {
                items(tracks, key = { it.sourceId + it.id }) { track ->
                    TrackRow(track, onClick = { viewModel.play(track, tracks) }, onLongClick = { onLongPressTrack(track) })
                }
            }
        }
    }
}
