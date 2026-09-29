package com.vibemusic.android.core.model

/** Альбом YT Music (идентифицируется плейлистом OLAK5uy_...). */
data class Album(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
)
