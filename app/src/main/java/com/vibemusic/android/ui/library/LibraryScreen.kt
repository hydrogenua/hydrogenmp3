package com.vibemusic.android.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibemusic.android.R
import com.vibemusic.android.ui.PlayerViewModel

/** Библиотека в духе SoundCloud: разделы списком, шестерёнка настроек справа сверху. */
@Composable
fun LibraryScreen(
    viewModel: PlayerViewModel,
    onOpenFavorites: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
            ) {
                Text(
                    stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.weight(1f).padding(top = 24.dp, bottom = 8.dp),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                }
            }
        }
        item(key = "favorites") { MenuRow(stringResource(R.string.menu_favorites), onOpenFavorites) }
        item(key = "playlists") { MenuRow(stringResource(R.string.menu_playlists), onOpenPlaylists) }
        item(key = "downloads") { MenuRow(stringResource(R.string.menu_downloads), onOpenDownloads) }
        item(key = "history") { MenuRow(stringResource(R.string.menu_history), onOpenHistory) }
        item(key = "stats") { MenuRow(stringResource(R.string.hist_stats), onOpenStats) }
        item(key = "spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
