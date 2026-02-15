package dev.jellylink.jellyfin.model

data class JellyfinMetadata(
    val id: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val lengthMs: Long?,
    val artworkUrl: String?,
)
