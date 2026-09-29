package com.vibemusic.android.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibemusic.android.core.model.Album
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.Artwork
import com.vibemusic.android.ui.components.TrackRow

@Composable
fun SearchScreen(
    viewModel: PlayerViewModel,
    onLongPressTrack: (Track) -> Unit,
    onOpenAlbum: (Album) -> Unit,
) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val statuses by viewModel.searchStatus.collectAsState()
    val albums by viewModel.searchAlbums.collectAsState()
    var filter by remember { mutableStateOf("all") }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Поиск",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::updateQuery,
            placeholder = { Text("Трек, исполнитель…") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            statuses.forEach { status ->
                Text(
                    text = if (status.error != null) {
                        "• ${status.sourceTitle}: ошибка — ${status.error}"
                    } else {
                        "• ${status.sourceTitle}: найдено ${status.count}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            filters.forEach { (id, label) ->
                FilterChip(
                    selected = filter == id,
                    onClick = { filter = id },
                    label = { Text(label) },
                )
            }
        }
        val visible = if (filter == "all") results else results.filter { it.sourceId == filter }
        LazyColumn {
            // Альбомная лента — внутри общего скролла: листается вместе с треками.
            if (albums.isNotEmpty()) {
                item(key = "albums_header") {
                    Text(
                        "Альбомы",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                item(key = "albums_row") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        items(albums, key = { it.id }) { album ->
                            Column(
                                Modifier
                                    .width(140.dp)
                                    .clickable { onOpenAlbum(album) },
                            ) {
                                Artwork(uri = album.artworkUri, size = 140.dp)
                                Text(
                                    album.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                Text(
                                    album.subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            items(visible, key = { it.sourceId + it.id }) { track ->
                TrackRow(track, onClick = { viewModel.play(track, visible) }, onLongClick = { onLongPressTrack(track) })
            }
        }
    }
}

private val filters = listOf(
    "all" to "Все",
    "local" to "Устройство",
    "ytm" to "YT Music",
    "sc" to "SoundCloud",
)
