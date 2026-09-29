package com.vibemusic.android.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vibemusic.android.core.model.Quality
import com.vibemusic.android.core.model.Track
import kotlinx.coroutines.flow.Flow

// Избранное: треки храним денормализованно — стриминговые ссылки живут недолго,
// а метаданные (что играть и как это выглядит) стабильны.
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val trackKey: String, // "$sourceId:$id"
    val sourceId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUri: String?,
    val quality: String,
    val format: String?,
    val shareUrl: String?,
    val addedAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/** Скачанный трек: лежит файлом в app-папке, играет без интернета. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackKey: String,
    val sourceId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUri: String?,
    val quality: String,
    val format: String?,
    val shareUrl: String?,
    val filePath: String,
    val state: String, // DOWNLOADING / DONE / FAILED
)

/** История прослушиваний. */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val autoId: Long = 0,
    val sourceId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUri: String?,
    val quality: String,
    val format: String?,
    val shareUrl: String?,
    val playedAt: Long,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackKey"],
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackKey: String,
    val position: Int,
    val sourceId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUri: String?,
    val quality: String,
    val format: String?,
    val shareUrl: String?,
)

fun FavoriteEntity.toTrack(): Track = Track(
    id = trackId,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    artworkUri = artworkUri,
    quality = runCatching { Quality.valueOf(quality) }.getOrDefault(Quality.LOSSY),
    format = format,
    shareUrl = shareUrl,
)

fun PlaylistTrackEntity.toTrack(): Track = Track(
    id = trackId,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    artworkUri = artworkUri,
    quality = runCatching { Quality.valueOf(quality) }.getOrDefault(Quality.LOSSY),
    format = format,
    shareUrl = shareUrl,
)

fun Track.toFavoriteEntity(addedAt: Long): FavoriteEntity = FavoriteEntity(
    trackKey = "$sourceId:$id",
    sourceId = sourceId,
    trackId = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    artworkUri = artworkUri,
    quality = quality.name,
    format = format,
    shareUrl = shareUrl,
    addedAt = addedAt,
)

fun Track.toPlaylistTrackEntity(playlistId: Long, position: Int): PlaylistTrackEntity =
    PlaylistTrackEntity(
        playlistId = playlistId,
        trackKey = "$sourceId:$id",
        position = position,
        sourceId = sourceId,
        trackId = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        artworkUri = artworkUri,
        quality = quality.name,
        format = format,
        shareUrl = shareUrl,
    )

fun DownloadEntity.toTrack(playableUri: String): Track = Track(
    id = trackId,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    artworkUri = artworkUri,
    quality = runCatching { Quality.valueOf(quality) }.getOrDefault(Quality.LOSSY),
    format = format,
    shareUrl = shareUrl,
    playableUri = playableUri,
)

fun HistoryEntity.toTrack(): Track = Track(
    id = trackId,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    artworkUri = artworkUri,
    quality = runCatching { Quality.valueOf(quality) }.getOrDefault(Quality.LOSSY),
    format = format,
    shareUrl = shareUrl,
)

fun Track.toHistoryEntity(playedAt: Long): HistoryEntity = HistoryEntity(
    sourceId = sourceId,
    trackId = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    artworkUri = artworkUri,
    quality = quality.name,
    format = format,
    shareUrl = shareUrl,
    playedAt = playedAt,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun favorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackKey = :trackKey)")
    fun isFavorite(trackKey: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackKey = :trackKey)")
    suspend fun isFavoriteOnce(trackKey: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE trackKey = :trackKey")
    suspend fun removeFavorite(trackKey: String)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Insert
    suspend fun insertPlaylist(entity: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    fun playlistTracks(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun playlistSize(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPlaylistTrack(entity: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackKey = :trackKey")
    suspend fun removePlaylistTrack(playlistId: Long, trackKey: String)

    @Transaction
    suspend fun deletePlaylistWithTracks(playlistId: Long) {
        removePlaylistTracks(playlistId)
        deletePlaylist(playlistId)
    }

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun removePlaylistTracks(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(entity: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE state = 'DONE'")
    fun downloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE trackKey = :trackKey")
    suspend fun getDownload(trackKey: String): DownloadEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE trackKey = :trackKey AND state = 'DONE')")
    fun isDownloaded(trackKey: String): Flow<Boolean>

    @Query("UPDATE downloads SET state = :state WHERE trackKey = :trackKey")
    suspend fun updateDownloadState(trackKey: String, state: String)

    @Query("DELETE FROM downloads WHERE trackKey = :trackKey")
    suspend fun removeDownload(trackKey: String)

    @Insert
    suspend fun insertHistory(entity: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 100")
    fun history(): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history")
    suspend fun clearHistory()
}

@Database(
    entities = [
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        DownloadEntity::class,
        HistoryEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

object LibraryDb {
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `downloads` (" +
                    "`trackKey` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `trackId` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, " +
                    "`durationMs` INTEGER NOT NULL, `artworkUri` TEXT, `quality` TEXT NOT NULL, " +
                    "`format` TEXT, `shareUrl` TEXT, `filePath` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                    "PRIMARY KEY(`trackKey`))",
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `history` (" +
                    "`autoId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sourceId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`artist` TEXT NOT NULL, `album` TEXT, `durationMs` INTEGER NOT NULL, " +
                    "`artworkUri` TEXT, `quality` TEXT NOT NULL, `format` TEXT, `shareUrl` TEXT, " +
                    "`playedAt` INTEGER NOT NULL)",
            )
        }
    }

    @Volatile
    private var instance: LibraryDatabase? = null

    fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context, LibraryDatabase::class.java, "vibe_library.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
            .also { instance = it }
    }
}
