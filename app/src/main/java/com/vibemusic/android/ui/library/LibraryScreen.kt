package com.vibemusic.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.unit.dp
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.TrackRow

@Composable
fun LibraryScreen(
    viewModel: PlayerViewModel,
    onLongPressTrack: (Track) -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenStats: () -> Unit,
) {
    val favorites by viewModel.favoriteTracks.collectAsState(initial = emptyList())
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val downloaded by viewModel.downloadedTracks.collectAsState(initial = emptyList())
    val active by viewModel.activeDownloads.collectAsState()
    val history by viewModel.history.collectAsState(initial = emptyList())
    val deezerArl by viewModel.deezerArl.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showDeezer by remember { mutableStateOf(false) }
    var deezerArlInput by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Библиотека",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Deezer", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (deezerArl.isBlank()) "Не подключён — поиск работает, полные треки нет"
                    else "Подключён (ARL сохранён)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { showDeezer = true }) {
                Text(if (deezerArl.isBlank()) "Подключить" else "Сменить ARL")
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Плейлисты", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Создать")
            }
        }
        playlists.forEach { playlist ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPlaylist(playlist.id, playlist.name) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.deletePlaylist(playlist.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Удалить плейлист",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (playlists.isEmpty()) {
            Text(
                "Плейлистов пока нет. Долгий тап на треке — «Добавить в плейлист».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Text(
            "Загрузки",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        if (active.isEmpty() && downloaded.isEmpty()) {
            Text(
                "Пусто. Долгий тап на треке — «Скачать оффлайн».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        active.forEach { download ->
            Text(
                "${download.title} — ${download.percent}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        downloaded.forEach { track ->
            TrackRow(track, onClick = { viewModel.play(track, downloaded) }, onLongClick = { onLongPressTrack(track) })
        }

        Text(
            "Избранное",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        if (favorites.isEmpty()) {
            Text(
                "Пусто. Долгий тап на треке — «В избранное».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            favorites.forEach { track ->
                TrackRow(track, onClick = { viewModel.play(track, favorites) }, onLongClick = { onLongPressTrack(track) })
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("История", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenStats) { Text("Статистика") }
            if (history.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearHistory() }) { Text("Очистить") }
            }
        }
        if (history.isEmpty()) {
            Text(
                "Здесь появятся прослушанные треки.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            history.take(20).forEach { track ->
                TrackRow(track, onClick = { viewModel.play(track, history) }, onLongClick = { onLongPressTrack(track) })
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Новый плейлист") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.createPlaylist(newName.trim())
                            newName = ""
                            showCreate = false
                        }
                    },
                ) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Отмена") } },
        )
    }

    if (showDeezer) {
        AlertDialog(
            onDismissRequest = { showDeezer = false },
            title = { Text("Подключить Deezer") },
            text = {
                Column {
                    Text(
                        "Зайди на deezer.com в браузере → F12 → Application → Cookies → скопируй значение cookie «arl» целиком (192 символа) и вставь сюда. ARL хранится только на этом устройстве.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = deezerArlInput,
                        onValueChange = { deezerArlInput = it },
                        placeholder = { Text("ARL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (deezerArlInput.isNotBlank()) {
                            viewModel.setDeezerArl(deezerArlInput)
                            deezerArlInput = ""
                            showDeezer = false
                        }
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showDeezer = false }) { Text("Отмена") } },
        )
    }
}
