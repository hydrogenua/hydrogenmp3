package com.vibemusic.android.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.core.audioPermissions
import com.vibemusic.android.core.hasAudioPermission
import com.vibemusic.android.core.startupPermissions
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.TrackRow

@Composable
fun HomeScreen(viewModel: PlayerViewModel, onLongPressTrack: (Track) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAudioPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Гейт только по аудио: отказ от уведомлений не должен прятать музыку.
        granted = hasAudioPermission(context)
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(startupPermissions())
    }

    val tracks by viewModel.homeTracks.collectAsState()
    LaunchedEffect(granted) {
        if (granted) viewModel.loadHome()
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Главная",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        )
        when {
            !granted -> Column(Modifier.padding(16.dp)) {
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
            tracks.isEmpty() -> Text(
                "Пока пусто — треков на устройстве не нашлось.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn {
                items(tracks, key = { it.sourceId + it.id }) { track ->
                    TrackRow(track, onClick = { viewModel.play(track, tracks) }, onLongClick = { onLongPressTrack(track) })
                }
            }
        }
    }
}
