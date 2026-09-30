package com.vibemusic.android.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibemusic.android.core.model.Album
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.core.audioPermissions
import com.vibemusic.android.core.hasAudioPermission
import com.vibemusic.android.core.startupPermissions
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.Artwork
import com.vibemusic.android.ui.components.TrackRow
import com.vibemusic.android.ui.theme.Accent

@Composable
fun HomeScreen(
    viewModel: PlayerViewModel,
    onLongPressTrack: (Track) -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenMood: (params: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAudioPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Гейт только по аудио: отказ от уведомлений не должен прятать музыку.
        granted = hasAudioPermission(context)
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(startupPermissions())
    }

    val localTracks by viewModel.homeTracks.collectAsState()
    LaunchedEffect(granted) {
        if (granted) viewModel.loadHome()
    }

    // Витрина собирается из того, что уже лежит в библиотеке.
    val history by viewModel.history.collectAsState(initial = emptyList())
    val favorites by viewModel.favoriteTracks.collectAsState(initial = emptyList())
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val downloads by viewModel.downloadedTracks.collectAsState(initial = emptyList())

    // Обзор с YT Music: настроения, хит-парады, новинки.
    val discover by viewModel.discover.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadDiscover() }

    val recent = history.distinctBy { it.sourceId + it.id }.take(12)
    val favCards = favorites.take(12)
    val downloadCards = downloads.take(12)

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "title") {
            Text(
                "Главная",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
            )
        }
        if (recent.isEmpty() && favCards.isEmpty() && playlists.isEmpty() && downloadCards.isEmpty() && localTracks.isEmpty()) {
            item(key = "hint") {
                Text(
                    "Найди музыку в «Поиске» — недавнее, избранное и плейлисты появятся здесь.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (recent.isNotEmpty()) {
            item(key = "recent_h") { SectionTitle("Недавно игравшие") }
            item(key = "recent_r") { TrackStrip(recent, viewModel, onLongPressTrack) }
        }
        if (discover.moods.isNotEmpty()) {
            item(key = "moods_h") { SectionTitle("По настроению") }
            item(key = "moods_r") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    item(key = "radio_likes") {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Accent)
                                .clickable { viewModel.startRadio(null) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                "Радио по лайкам",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFF12141A),
                                maxLines = 1,
                            )
                        }
                    }
                    items(discover.moods.take(14), key = { it.params }) { mood ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(mood.color))
                                .clickable { onOpenMood(mood.params, mood.title) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                mood.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFF12141A),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        if (discover.charts.isNotEmpty()) {
            item(key = "charts_h") { SectionTitle("Сегодняшние хиты") }
            item(key = "charts_r") { PlaylistCardStrip(discover.charts, viewModel) }
        }
        if (discover.releases.isNotEmpty()) {
            item(key = "rel_h") { SectionTitle("Свежие релизы") }
            item(key = "rel_r") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(discover.releases, key = { it.id }) { album ->
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
        if (favCards.isNotEmpty()) {
            item(key = "fav_h") { SectionTitle("Избранное") }
            item(key = "fav_r") { TrackStrip(favCards, viewModel, onLongPressTrack) }
        }
        if (playlists.isNotEmpty()) {
            item(key = "pl_h") { SectionTitle("Плейлисты") }
            item(key = "pl_r") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(playlists, key = { it.id }) { pl ->
                        Column(
                            Modifier
                                .width(140.dp)
                                .clickable { onOpenPlaylist(pl.id, pl.name) },
                        ) {
                            Box(
                                Modifier
                                    .width(140.dp)
                                    .height(140.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.LibraryMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.height(44.dp).width(44.dp),
                                )
                            }
                            Text(
                                pl.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Text(
                                "Плейлист",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (downloadCards.isNotEmpty()) {
            item(key = "dl_h") { SectionTitle("Загрузки") }
            item(key = "dl_r") { TrackStrip(downloadCards, viewModel, onLongPressTrack) }
        }

        item(key = "local_h") { SectionTitle("На устройстве") }
        if (!granted) {
            item(key = "local_gate") {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Нужен доступ к аудио, чтобы показать музыку на устройстве.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { launcher.launch(audioPermissions()) }) {
                        Text("Дать доступ")
                    }
                }
            }
        } else if (localTracks.isEmpty()) {
            item(key = "local_empty") {
                Text(
                    "Треков на устройстве не нашлось.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(localTracks, key = { "local_" + it.sourceId + it.id }) { track ->
                TrackRow(track, onClick = { viewModel.play(track, localTracks) }, onLongClick = { onLongPressTrack(track) })
            }
        }
    }
}

@Composable
private fun PlaylistCardStrip(cards: List<com.vibemusic.android.source.ytm.PlaylistCard>, viewModel: PlayerViewModel) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(cards, key = { it.playlistId }) { card ->
            Column(
                Modifier
                    .width(140.dp)
                    .clickable { viewModel.playPlaylistCard(card) },
            ) {
                Artwork(uri = card.artworkUri, size = 140.dp)
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    card.subtitle.ifBlank { "Плейлист" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackCard(track: Track, viewModel: PlayerViewModel, queue: List<Track>, onLongClick: () -> Unit) {
    Column(
        Modifier
            .width(140.dp)
            .combinedClickable(onClick = { viewModel.play(track, queue) }, onLongClick = onLongClick),
    ) {
        Artwork(uri = track.artworkUri, size = 140.dp)
        Text(
            track.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TrackStrip(tracks: List<Track>, viewModel: PlayerViewModel, onLongPress: (Track) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(tracks, key = { it.sourceId + it.id }) { track ->
            TrackCard(track, viewModel, tracks, onLongClick = { onLongPress(track) })
        }
    }
}
