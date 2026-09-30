package com.vibemusic.android.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.ui.PlayerViewModel
import com.vibemusic.android.ui.components.Artwork
import kotlinx.coroutines.launch

/**
 * Экран очереди: играющий трек закреплён, будущие — тап прыгает на трек,
 * долгий тап + палец переставляет (данные едут сразу, плееру уезжает один move).
 */
@Composable
fun QueueScreen(viewModel: PlayerViewModel, onJump: () -> Unit) {
    val tracks by viewModel.queueTracks.collectAsState()
    val position by viewModel.queuePosition.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val order = remember { mutableStateListOf<Track>() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    val dragOffset = remember { Animatable(0f) }

    LaunchedEffect(tracks) {
        if (draggingIndex == null) {
            order.clear()
            order.addAll(tracks)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(order, key = { _, t -> t.sourceId + t.id }) { index, track ->
            val isDragging = draggingIndex == index
            val isCurrent = index == position
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset.value else 0f }
                    .clickable(enabled = !isCurrent) {
                        viewModel.playQueueIndex(index)
                        onJump()
                    }
                    .pointerInput(position, isCurrent) {
                        if (isCurrent) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragStartIndex = index
                                draggingIndex = index
                                scope.launch { dragOffset.snapTo(0f) }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val dragging = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                scope.launch { dragOffset.snapTo(dragOffset.value + amount.y) }
                                val info = listState.layoutInfo
                                val dragged = info.visibleItemsInfo.firstOrNull { it.index == dragging }
                                    ?: return@detectDragGesturesAfterLongPress
                                val center = dragged.offset + dragOffset.value + dragged.size / 2f
                                val target = info.visibleItemsInfo.firstOrNull { item ->
                                    item.index != dragging &&
                                        item.index > position &&
                                        center >= item.offset && center < item.offset + item.size
                                }?.index ?: return@detectDragGesturesAfterLongPress
                                val moved = order.removeAt(dragging)
                                order.add(target, moved)
                                // Держим палец на карточке: слот уехал на (target-dragging)*size.
                                scope.launch { dragOffset.snapTo(dragOffset.value + (dragging - target) * dragged.size) }
                                draggingIndex = target
                            },
                            onDragEnd = {
                                val from = dragStartIndex
                                val to = draggingIndex
                                draggingIndex = null
                                scope.launch { dragOffset.animateTo(0f) }
                                if (to != null && from != to) viewModel.moveInQueue(from, to)
                            },
                            onDragCancel = {
                                draggingIndex = null
                                scope.launch { dragOffset.animateTo(0f) }
                            },
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Artwork(uri = track.artworkUri, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isCurrent) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = "Играет",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = "Переставить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
