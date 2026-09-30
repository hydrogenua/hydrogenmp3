package com.vibemusic.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibemusic.android.core.model.Album
import com.vibemusic.android.core.model.Track
import com.vibemusic.android.data.db.FavoriteEntity
import com.vibemusic.android.data.db.HistoryEntity
import com.vibemusic.android.data.db.LibraryDb
import com.vibemusic.android.data.db.PlaylistEntity
import com.vibemusic.android.data.db.toFavoriteEntity
import com.vibemusic.android.data.db.toHistoryEntity
import com.vibemusic.android.data.db.toPlaylistTrackEntity
import com.vibemusic.android.data.db.toTrack
import com.vibemusic.android.data.downloads.ActiveDownload
import com.vibemusic.android.data.downloads.DownloadRepository
import com.vibemusic.android.playback.PlayerConnection
import com.vibemusic.android.source.deezer.DeezerClient
import com.vibemusic.android.source.SourceRegistry
import com.vibemusic.android.source.ytm.YtMusicPlugin
import com.vibemusic.android.update.UpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Диагностика поиска: сколько нашёл каждый источник и что у него случилось, если не нашёл. */
data class SearchStatus(
    val sourceTitle: String,
    val count: Int,
    val error: String?,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val registry = SourceRegistry(application)
    private val connection = PlayerConnection(application)
    private val dao = LibraryDb.get(application).libraryDao()
    private val downloads = DownloadRepository(application, dao, registry)
    private val deezerClient = DeezerClient(application)
    private val updates = UpdateManager(application)

    /** ARL-токен Deezer: пусто — источник даёт только поиск. */
    val deezerArl: StateFlow<String> = deezerClient.arlFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setDeezerArl(arl: String) {
        viewModelScope.launch { deezerClient.setArl(arl) }
    }

    // Обновления.

    val updateInfo: StateFlow<UpdateManager.UpdateInfo?> = updates.available
    val updateDismissed: StateFlow<String> = updates.dismissedTag

    fun checkForUpdates() {
        viewModelScope.launch { updates.check() }
    }

    fun installUpdate() {
        updateInfo.value?.let { updates.downloadAndInstall(it) }
    }

    fun dismissUpdate() {
        updateInfo.value?.let { viewModelScope.launch { updates.dismiss(it.tag) } }
    }

    /** Избранное как Flow — экран библиотеки обновляется сам. */
    val favoriteTracks: Flow<List<Track>> = dao.favorites().map { list -> list.map { it.toTrack() } }

    val playlists: Flow<List<PlaylistEntity>> = dao.playlists()

    fun isFavorite(track: Track): Flow<Boolean> = dao.isFavorite("${track.sourceId}:${track.id}")

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            val key = "${track.sourceId}:${track.id}"
            if (dao.isFavoriteOnce(key)) dao.removeFavorite(key) else dao.addFavorite(track.toFavoriteEntity(System.currentTimeMillis()))
        }
    }

    fun addToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch {
            dao.addPlaylistTrack(track.toPlaylistTrackEntity(playlistId, dao.playlistSize(playlistId)))
        }
    }

    /** Создаёт плейлист с именем [name] и сразу добавляет трек. */
    fun addToPlaylist(name: String, track: Track) {
        viewModelScope.launch {
            val id = dao.insertPlaylist(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))
            addToPlaylist(id, track)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { dao.deletePlaylistWithTracks(playlistId) }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            dao.insertPlaylist(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))
        }
    }

    fun playlistTracks(playlistId: Long): Flow<List<Track>> =
        dao.playlistTracks(playlistId).map { list -> list.map { it.toTrack() } }

    fun removeFromPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch { dao.removePlaylistTrack(playlistId, "${track.sourceId}:${track.id}") }
    }

    // Оффлайн-загрузки.

    val downloadedTracks: Flow<List<Track>> = downloads.downloadedTracks

    val activeDownloads: StateFlow<List<ActiveDownload>> = downloads.activeDownloads

    fun download(track: Track) = downloads.download(track)

    fun deleteDownload(track: Track) = downloads.delete(track)

    fun isDownloaded(track: Track): Flow<Boolean> = dao.isDownloaded("${track.sourceId}:${track.id}")

    /** Играет трек; [queueSource] — список, из которого нажали (для след./пред.). */
    fun play(track: Track, queueSource: List<Track>? = null) {
        fallbackActive = false
        triedSources.clear()
        viewModelScope.launch {
            if (queueSource != null) {
                queue = queueSource
                queueIndex = queue.indexOfFirst { it.sourceId == track.sourceId && it.id == track.id }
                    .coerceAtLeast(0)
            } else {
                queue = listOf(track)
                queueIndex = 0
            }
            updateQueueFlags()
            // Скачанные треки играем с локального файла — без интернета.
            val playableQueue = downloads.withDownloadedFiles(queue)
            connection.playQueue(playableQueue, queueIndex)
        }
    }

    // Очередь живёт на уровне приложения: список, из которого нажали play.
    private var queue: List<Track> = emptyList()
    private var queueIndex = -1

    private val _canNext = MutableStateFlow(false)
    val canNext: StateFlow<Boolean> = _canNext.asStateFlow()

    private val _canPrevious = MutableStateFlow(false)
    val canPrevious: StateFlow<Boolean> = _canPrevious.asStateFlow()

    val isShuffle: StateFlow<Boolean> = connection.isShuffle

    init {
        // Плеер сам переходит по очереди (и в уведомлении) — синхронизируем индекс и кнопки,
        // заодно пишем историю прослушиваний.
        connection.onMediaItemChanged = { mediaId ->
            val track = queue.firstOrNull { "${it.sourceId}:${it.id}" == mediaId }
            if (track != null) {
                val index = queue.indexOf(track)
                if (index >= 0) queueIndex = index
                viewModelScope.launch { dao.insertHistory(track.toHistoryEntity(System.currentTimeMillis())) }
            }
            updateQueueFlags()
        }
        // Если трек с источника не заиграл (права/сеть) — автоматически ищем
        // ту же песню на других источниках и играем оттуда.
        connection.onPlaybackFailed = { failed, error ->
            triedSources.add(failed.sourceId)
            if (failed.sourceId != "local" && !fallbackActive) {
                fallbackActive = true
                viewModelScope.launch {
                    val alternative = findAlternative(failed)
                    if (alternative != null && alternative.sourceId !in triedSources) {
                        triedSources.add(alternative.sourceId)
                        Log.d("Fallback", "${failed.sourceId} недоступен → играем с ${alternative.sourceId}")
                        connection.playQueue(listOf(alternative), 0)
                        fallbackActive = false
                    } else {
                        Log.d("Fallback", "альтернатив больше нет для ${failed.title}")
                        connection.showError(error)
                        fallbackActive = false
                    }
                }
            } else {
                connection.showError(error)
                fallbackActive = false
            }
        }
    }

    /** Ищет тот же трек на других источниках (для кросс-source фолбэка). */
    private suspend fun findAlternative(failed: Track): Track? {
        val query = "${failed.artist} ${failed.title}"
        for (plugin in registry.all) {
            if (plugin.id == failed.sourceId || plugin.id == "local") continue
            val results = runCatching { plugin.search(query, 5) }.getOrDefault(emptyList())
            val match = results.firstOrNull { candidate ->
                candidate.title.contains(failed.title.take(15), ignoreCase = true)
            }
            if (match != null) return match
        }
        return null
    }

    /** Последние прослушанные треки. */
    val history: Flow<List<Track>> = dao.history().map { list -> list.map { it.toTrack() } }

    fun clearHistory() {
        viewModelScope.launch { dao.clearHistory() }
    }

    val isPlaying: StateFlow<Boolean> = connection.isPlaying
    val nowPlaying: StateFlow<Track?> = connection.nowPlaying
    val positionMs: StateFlow<Long> = connection.positionMs
    val durationMs: StateFlow<Long> = connection.durationMs
    val repeatOne: StateFlow<Boolean> = connection.repeatOne
    val volume: StateFlow<Float> = connection.volume
    val error: StateFlow<String?> = connection.error

    private val _homeTracks = MutableStateFlow<List<Track>>(emptyList())
    val homeTracks: StateFlow<List<Track>> = _homeTracks.asStateFlow()

    // Запрос живёт во ViewModel: переживает переход на экран плеера и обратно.
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _searchStatus = MutableStateFlow<List<SearchStatus>>(emptyList())
    val searchStatus: StateFlow<List<SearchStatus>> = _searchStatus.asStateFlow()

    private val _searchAlbums = MutableStateFlow<List<Album>>(emptyList())
    val searchAlbums: StateFlow<List<Album>> = _searchAlbums.asStateFlow()

    /** Экран альбома: метаданные + трек-лист + индикатор загрузки. */
    data class AlbumUi(val album: Album, val tracks: List<Track>, val loading: Boolean)

    private val _albumUi = MutableStateFlow<AlbumUi?>(null)
    val albumUi: StateFlow<AlbumUi?> = _albumUi.asStateFlow()

    private var searchJob: Job? = null

    /** Флаг активного фолбэка: пока ищем замену на другом источнике — не зацикливаемся. */
    private var fallbackActive = false

    /** Источники, которые уже пытались играть текущий трек. */
    private val triedSources = mutableSetOf<String>()

    fun loadHome() {
        viewModelScope.launch {
            _homeTracks.value = registry.all.first().home()
        }
    }

    fun updateQuery(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _searchStatus.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // дебаунс набора
            val perSource = registry.all
                .map { plugin ->
                    async {
                        val result = runCatching { plugin.search(query) }
                        val tracks = result.getOrDefault(emptyList())
                        val error = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                        Triple(plugin.title, tracks, error)
                    }
                }
                .awaitAll()
            _searchResults.value = perSource.flatMap { it.second }
            _searchStatus.value = perSource.map { (title, tracks, error) ->
                SearchStatus(title, tracks.size, error)
            }
            // Альбомы YT Music — отдельной лентой над результатами.
            _searchAlbums.value = runCatching {
                (registry.byId("ytm") as? YtMusicPlugin)?.searchAlbums(query) ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }

    /** Открывает экран альбома: грузит трек-лист по playlistId. */
    fun openAlbum(album: Album) {
        _albumUi.value = AlbumUi(album, emptyList(), loading = true)
        viewModelScope.launch {
            val tracks = runCatching {
                (registry.byId("ytm") as? YtMusicPlugin)?.albumTracks(album.id) ?: emptyList()
            }.onFailure { Log.e("AlbumLoad", "album ${album.id}", it) }.getOrElse { emptyList() }
            // Перезаписываем только если пользователь всё ещё на этом альбоме.
            if (_albumUi.value?.album?.id == album.id) {
                _albumUi.value = AlbumUi(album, tracks, loading = false)
            }
        }
    }

    fun openAlbumById(albumId: String) {
        val known = _searchAlbums.value.firstOrNull { it.id == albumId }
            ?: _discover.value.releases.firstOrNull { it.id == albumId }
        openAlbum(known ?: Album(albumId, "Альбом", "", null))
    }

    fun playAlbum() {
        val ui = _albumUi.value
        if (ui != null && ui.tracks.isNotEmpty()) play(ui.tracks.first(), ui.tracks)
    }

    // ---------- Обзор на Главной: настроения, хит-парады, новинки ----------

    /** Витрина «обзора»: грузится один раз параллельно, секции скрываются если пусто. */
    data class Discover(
        val moods: List<com.vibemusic.android.source.ytm.MoodCard> = emptyList(),
        val charts: List<com.vibemusic.android.source.ytm.PlaylistCard> = emptyList(),
        val releases: List<Album> = emptyList(),
    )

    private val _discover = MutableStateFlow(Discover())
    val discover: StateFlow<Discover> = _discover.asStateFlow()

    private var discoverLoaded = false

    fun loadDiscover() {
        if (discoverLoaded) return
        discoverLoaded = true
        val ytm = registry.byId("ytm") as? YtMusicPlugin ?: return
        viewModelScope.launch {
            val moods = async { runCatching { ytm.moods() }.getOrElse { emptyList() } }
            val charts = async { runCatching { ytm.chartPlaylists() }.getOrElse { emptyList() } }
            val releases = async { runCatching { ytm.newReleases() }.getOrElse { emptyList() } }
            _discover.value = Discover(moods.await(), charts.await(), releases.await())
        }
    }

    /** Экран настроения: плейлисты выбранной категории. */
    data class MoodUi(
        val title: String,
        val params: String,
        val cards: List<com.vibemusic.android.source.ytm.PlaylistCard> = emptyList(),
        val loading: Boolean = true,
    )

    private val _moodUi = MutableStateFlow<MoodUi?>(null)
    val moodUi: StateFlow<MoodUi?> = _moodUi.asStateFlow()

    fun openMood(params: String, title: String) {
        _moodUi.value = MoodUi(title, params, loading = true)
        viewModelScope.launch {
            val cards = runCatching {
                (registry.byId("ytm") as? YtMusicPlugin)?.moodPlaylists(params) ?: emptyList()
            }.getOrElse { emptyList() }
            // Показываем только если пользователь ещё на этом настроении.
            if (_moodUi.value?.params == params) {
                _moodUi.value = MoodUi(title, params, cards, loading = false)
            }
        }
    }

    /** Играет карточку плейлиста (настроение или хит-парад) очередью. */
    fun playPlaylistCard(card: com.vibemusic.android.source.ytm.PlaylistCard) {
        viewModelScope.launch {
            val tracks = runCatching {
                (registry.byId("ytm") as? YtMusicPlugin)?.playlistTracks(card.playlistId, card.playParams) ?: emptyList()
            }.getOrElse { emptyList() }
            if (tracks.isNotEmpty()) play(tracks.first(), tracks)
        }
    }

    fun playNext() = connection.nextMedia()

    fun playPrevious() = connection.previousMedia()

    fun toggleShuffle() = connection.toggleShuffle()

    private fun updateQueueFlags() {
        _canNext.value = queueIndex + 1 < queue.size
        _canPrevious.value = queueIndex > 0
    }

    fun togglePlayback() = connection.togglePlayback()

    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)

    fun toggleRepeat() = connection.toggleRepeat()

    fun setVolume(value: Float) = connection.setVolume(value)

    override fun onCleared() {
        connection.release()
        super.onCleared()
    }
}
