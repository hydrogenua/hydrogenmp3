package com.vibemusic.android.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibemusic.android.R
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.Artwork
import kotlinx.coroutines.launch

/**
 * Экран плейлистов: список с обложками, создание, долгий тап —
 * переименование / своя обложка / удаление.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(
    viewModel: PlayerViewModel,
    onOpenPlaylist: (Long, String) -> Unit,
) {
    val context = LocalContext.current
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())

    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        menuFor?.let { viewModel.setPlaylistCover(it, uri) }
        menuFor = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.menu_playlists),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f).padding(top = 24.dp, bottom = 8.dp),
            )
            FilledTonalButton(onClick = { newName = ""; showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.playlists_create))
            }
        }

        if (playlists.isEmpty()) {
            Text(
                stringResource(R.string.pl_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn {
                items(playlists, key = { it.id }) { pl ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpenPlaylist(pl.id, pl.name) },
                                onLongClick = { menuFor = pl.id },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Artwork(uri = pl.coverPath, size = 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                pl.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.pl_subtitle),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.IconButton(onClick = { menuFor = pl.id }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = menuFor == pl.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pl_rename)) },
                                onClick = {
                                    menuFor = null
                                    renaming = pl.id
                                    renameText = pl.name
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pl_cover)) },
                                onClick = {
                                    menuFor = null
                                    coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pl_delete)) },
                                onClick = {
                                    menuFor = null
                                    viewModel.deletePlaylist(pl.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Создание плейлиста
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.playlists_create)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.pl_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) viewModel.createPlaylist(newName.trim())
                    showCreate = false
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // Переименование
    renaming?.let { id ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.pl_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.pl_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) viewModel.renamePlaylist(id, renameText.trim())
                    renaming = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
