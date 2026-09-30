package com.vibemusic.android.ui.player

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.Artwork
import com.vibemusic.android.ui.components.QualityBadge
import com.vibemusic.android.ui.components.formatDuration
import com.vibemusic.android.ui.theme.Accent
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun NowPlayingScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val track by viewModel.nowPlaying.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val repeatOne by viewModel.repeatOne.collectAsState()
    val isShuffle by viewModel.isShuffle.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val canNext by viewModel.canNext.collectAsState()
    val canPrevious by viewModel.canPrevious.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current

    // Пока тащим ползунок — не даём опросу позиции дёргать значение под пальцем.
    var dragPosition by remember { mutableStateOf<Float?>(null) }

    // Плеер тянется пальцем вниз; отпустил далеко — улетел за экран и свернулся,
    // близко — вернулся пружинкой.
    val dragOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffset.value.roundToInt()) }
            .graphicsLayer { alpha = 1f - (dragOffset.value / 1400f).coerceIn(0f, 0.55f) }
            // Фон ПОСЛЕ offset/graphicsLayer: иначе он рисуется до сдвига и
            // остаётся висеть на весь экран, пока контент уезжает — та самая «чернота».
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { dragOffset.snapTo((dragOffset.value + dragAmount).coerceAtLeast(0f)) }
                    },
                    onDragEnd = {
                        if (dragOffset.value > 480f) {
                            scope.launch {
                                dragOffset.animateTo(2400f, tween(230))
                                onBack()
                            }
                        } else {
                            scope.launch {
                                dragOffset.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                        }
                    },
                )
            },
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
        }
        Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
            val t = track
            if (t == null) {
                Text(
                    "Ничего не играет",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Artwork(uri = t.artworkUri, size = 280.dp)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        t.title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        t.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val favoriteFlow = remember(t) { viewModel.isFavorite(t) }
                    val isFavorite by favoriteFlow.collectAsState(initial = false)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        QualityBadge(quality = t.quality, format = t.format)
                        IconButton(onClick = { viewModel.toggleFavorite(t) }) {
                            Icon(
                                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "В избранное",
                                tint = if (isFavorite) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    error?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }

                    val totalMs = if (durationMs > 0) durationMs else t.durationMs
                    val currentMs = positionMs.coerceIn(0L, if (totalMs > 0) totalMs else 0L)
                    if (totalMs > 0) {
                        Slider(
                            value = dragPosition ?: currentMs.toFloat(),
                            onValueChange = { dragPosition = it },
                            onValueChangeFinished = {
                                dragPosition?.let { viewModel.seekTo(it.toLong()) }
                                dragPosition = null
                            },
                            valueRange = 0f..totalMs.toFloat(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 18.dp),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                formatDuration(dragPosition?.toLong() ?: currentMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatDuration(totalMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 22.dp),
                    ) {
                        IconButton(onClick = viewModel::toggleShuffle) {
                            Icon(
                                Icons.Filled.Shuffle,
                                contentDescription = "Перемешать",
                                tint = if (isShuffle) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = viewModel::toggleRepeat) {
                            Icon(
                                if (repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                contentDescription = "Повтор",
                                tint = if (repeatOne) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = viewModel::playPrevious, enabled = canPrevious) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "Предыдущий трек",
                                tint = if (canPrevious) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                },
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(
                            onClick = viewModel::togglePlayback,
                            modifier = Modifier
                                .size(76.dp)
                                .background(Accent, CircleShape),
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(42.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = viewModel::playNext, enabled = canNext) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = "Следующий трек",
                                tint = if (canNext) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                },
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(onClick = { shareTrack(context, t) }) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = "Поделиться",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    VolumeSlider(
                        volume = volume,
                        onVolumeChange = viewModel::setVolume,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 26.dp),
                    )
                }
            }
        }
    }
}

/** Громкость применяется сразу при перетаскивании, а не только после отпускания. */
@Composable
private fun VolumeSlider(volume: Float, onVolumeChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    var dragVolume by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "Громкость",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = dragVolume ?: volume,
            onValueChange = {
                dragVolume = it
                onVolumeChange(it)
            },
            onValueChangeFinished = { dragVolume = null },
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
    }
}

private fun shareTrack(context: android.content.Context, track: Track) {
    val text = track.shareUrl ?: "${track.title} — ${track.artist}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться треком"))
}
