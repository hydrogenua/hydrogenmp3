package com.vibemusic.android.ui.artist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.Artwork
import com.vibemusic.android.ui.components.TrackRow

/**
 * Экран исполнителя: шапка с обложкой и подписчиками, популярные треки
 * (тап играет очередью), альбомы и синглы каруселями.
 */
@Composable
fun ArtistScreen(
    name: String,
    viewModel: PlayerViewModel,
    onOpenAlbum: (String) -> Unit,
) {
    val artist by viewModel.artistUi.collectAsState()
    LaunchedEffect(name) { viewModel.openArtist(name) }

    val page = artist?.takeIf { it.name == name }?.page
    val loading = artist?.takeIf { it.name == name }?.loading ?: true

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(uri = page?.artworkUri, size = 180.dp)
                Text(
                    name,
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (!page?.subscriberText.isNullOrBlank()) {
                    Text(
                        page!!.subscriberText!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        when {
            loading -> item(key = "loading") {
                CircularProgressIndicator(Modifier.padding(32.dp))
            }
            page == null -> item(key = "error") {
                Text(
                    "Не удалось загрузить исполнителя — похоже на бот-проверку YouTube или нет сети.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            else -> {
                if (page.tracks.isNotEmpty()) {
                    item(key = "top_h") {
                        Text(
                            "Популярные треки",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(page.tracks, key = { "tr_" + it.id }) { track ->
                        TrackRow(track, onClick = { viewModel.play(track, page.tracks) })
                    }
                }
                if (page.albums.isNotEmpty()) {
                    item(key = "alb_h") { SectionTitle("Альбомы") }
                    item(key = "alb_r") { AlbumStrip(page.albums, onOpenAlbum) }
                }
                if (page.singles.isNotEmpty()) {
                    item(key = "sng_h") { SectionTitle("Синглы") }
                    item(key = "sng_r") { AlbumStrip(page.singles, onOpenAlbum) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AlbumStrip(albums: List<com.vibemusic.android.core.model.Album>, onOpenAlbum: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            Column(
                Modifier
                    .width(140.dp)
                    .clickable { onOpenAlbum(album.id) },
            ) {
                Artwork(uri = album.artworkUri, size = 140.dp)
                Text(
                    album.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
