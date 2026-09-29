package com.vibemusic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.ui.album.AlbumScreen
import com.vibemusic.android.ui.components.Artwork
import com.vibemusic.android.ui.components.TrackMenuDialog
import com.vibemusic.android.ui.home.HomeScreen
import com.vibemusic.android.ui.library.LibraryScreen
import com.vibemusic.android.ui.library.PlaylistDetailScreen
import com.vibemusic.android.ui.player.NowPlayingScreen
import com.vibemusic.android.ui.search.SearchScreen
import java.io.File

@Composable
fun VibeApp(viewModel: PlayerViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Долгий тап на треке открывает меню (глобально, из любого списка).
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var menuPlaylistId by remember { mutableStateOf<Long?>(null) }

    // Плеер — оверлей поверх живого экрана (не маршрут): при сворачивании
    // содержимое под ним видно сразу, без «прогрузки».
    var playerExpanded by remember { mutableStateOf(false) }

    CrashReportDialog()

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    MiniPlayer(viewModel = viewModel, onOpenPlayer = { playerExpanded = true })
                    VibeNavigationBar(navController, currentRoute)
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.Home,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.Home) {
                    HomeScreen(viewModel) { track -> menuPlaylistId = null; menuTrack = track }
                }
                composable(Routes.Search) {
                    SearchScreen(
                        viewModel,
                        onLongPressTrack = { track -> menuPlaylistId = null; menuTrack = track },
                        onOpenAlbum = { album ->
                            navController.navigate("album/${Uri.encode(album.id)}")
                        },
                    )
                }
                composable(
                    route = "album/{albumId}",
                    arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
                ) { entry ->
                    AlbumScreen(
                        albumId = entry.arguments?.getString("albumId") ?: "",
                        viewModel = viewModel,
                        onLongPressTrack = { track -> menuPlaylistId = null; menuTrack = track },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.Library) {
                    LibraryScreen(
                        viewModel,
                        onLongPressTrack = { track -> menuPlaylistId = null; menuTrack = track },
                        onOpenPlaylist = { id, name ->
                            navController.navigate("playlist/$id/${Uri.encode(name)}")
                        },
                    )
                }
                composable(
                    route = "playlist/{playlistId}/{playlistName}",
                    arguments = listOf(
                        navArgument("playlistId") { type = NavType.LongType },
                        navArgument("playlistName") { type = NavType.StringType },
                    ),
                ) { entry ->
                    PlaylistDetailScreen(
                        playlistId = entry.arguments?.getLong("playlistId") ?: 0L,
                        name = entry.arguments?.getString("playlistName") ?: "",
                        viewModel = viewModel,
                        onLongPressTrack = { track ->
                            menuPlaylistId = entry.arguments?.getLong("playlistId")
                            menuTrack = track
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = playerExpanded,
            enter = androidx.compose.animation.slideInVertically(
                initialOffsetY = { it },
                animationSpec = androidx.compose.animation.core.tween(280),
            ),
            exit = androidx.compose.animation.slideOutVertically(
                targetOffsetY = { it },
                animationSpec = androidx.compose.animation.core.tween(240),
            ),
        ) {
            NowPlayingScreen(viewModel) { playerExpanded = false }
        }
    }

    menuTrack?.let { track ->
        TrackMenuDialog(
            track = track,
            playlistId = menuPlaylistId,
            viewModel = viewModel,
            onDismiss = { menuTrack = null },
        )
    }
}

private object Routes {
    const val Home = "home"
    const val Search = "search"
    const val Library = "library"
}

/** Показывает стектрейс прошлого краша (если был) — «чёрный ящик» для sideload-тестов. */
@Composable
private fun CrashReportDialog() {
    val context = LocalContext.current
    var crashText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val file = File(context.filesDir, com.vibemusic.android.VibeApplication.CRASH_FILE)
        if (file.exists()) crashText = file.readText()
    }
    val text = crashText ?: return

    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Приложение падало") },
        text = {
            Text(
                text.lineSequence().take(40).joinToString("\n"),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                file_delete(context)
                crashText = null
            }) { Text("Скопировать и закрыть") }
        },
        dismissButton = {
            TextButton(onClick = {
                file_delete(context)
                crashText = null
            }) { Text("Закрыть") }
        },
    )
}

private fun file_delete(context: android.content.Context) {
    runCatching {
        File(context.filesDir, com.vibemusic.android.VibeApplication.CRASH_FILE).delete()
    }
}

private data class BottomItem(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun VibeNavigationBar(navController: NavHostController, currentRoute: String?) {
    val items = listOf(
        BottomItem(Routes.Home, "Главная", Icons.Filled.Home),
        BottomItem(Routes.Search, "Поиск", Icons.Filled.Search),
        BottomItem(Routes.Library, "Библиотека", Icons.Filled.LibraryMusic),
    )
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(Routes.Home) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun MiniPlayer(viewModel: PlayerViewModel, onOpenPlayer: () -> Unit) {
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val track = nowPlaying ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpenPlayer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(uri = track.artworkUri, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = viewModel::togglePlayback) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
            )
        }
    }
}
