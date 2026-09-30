package com.vibemusic.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.ui.PlayerViewModel

/**
 * Меню трека по долгому нажатию: избранное, добавление в плейлист,
 * а внутри плейлиста — удаление из него.
 */
@Composable
fun TrackMenuDialog(
    track: Track,
    playlistId: Long?,
    viewModel: PlayerViewModel,
    onOpenArtist: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var pickingPlaylist by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val downloaded by remember(track) { viewModel.isDownloaded(track) }.collectAsState(initial = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    pickingPlaylist -> "Добавить в плейлист"
                    else -> track.title
                },
                maxLines = 1,
            )
        },
        text = {
            when {
                pickingPlaylist -> Column {
                    playlists.forEach { playlist ->
                        TextButton(
                            onClick = {
                                viewModel.addToPlaylist(playlist.id, track)
                                onDismiss()
                            },
                        ) { Text(playlist.name) }
                    }
                    if (playlists.isEmpty()) {
                        Text(
                            "Пока нет плейлистов — создай первый:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("Новый плейлист") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                if (newName.isNotBlank()) {
                                    viewModel.addToPlaylist(newName.trim(), track)
                                    onDismiss()
                                }
                            },
                        ) { Icon(Icons.Filled.Add, contentDescription = "Создать и добавить") }
                    }
                }
                playlistId != null -> Column {
                    TextButton(onClick = {
                        viewModel.startRadio(track)
                        onDismiss()
                    }) { Text("Запустить радио от трека") }
                    TextButton(onClick = {
                        viewModel.removeFromPlaylist(playlistId, track)
                        onDismiss()
                    }) { Text("Убрать из плейлиста") }
                    TextButton(onClick = {
                        viewModel.toggleFavorite(track)
                        onDismiss()
                    }) { Text("В избранное / убрать из избранного") }
                    if (track.sourceId != "local") {
                        TextButton(onClick = {
                            viewModel.download(track)
                            onDismiss()
                        }) { Text("Скачать оффлайн") }
                    }
                }
                else -> Column {
                    TextButton(onClick = {
                        viewModel.startRadio(track)
                        onDismiss()
                    }) { Text("Запустить радио от трека") }
                    TextButton(onClick = {
                        onOpenArtist(track.artist)
                    }) { Text("Открыть исполнителя") }
                    TextButton(onClick = {
                        viewModel.toggleFavorite(track)
                        onDismiss()
                    }) { Text("В избранное / убрать из избранного") }
                    TextButton(onClick = { pickingPlaylist = true }) {
                        Text("Добавить в плейлист…")
                    }
                    if (track.sourceId != "local" && !downloaded) {
                        TextButton(onClick = {
                            viewModel.download(track)
                            onDismiss()
                        }) { Text("Скачать оффлайн") }
                    }
                    if (downloaded) {
                        TextButton(onClick = {
                            viewModel.deleteDownload(track)
                            onDismiss()
                        }) { Text("Удалить загрузку") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
