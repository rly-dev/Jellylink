package dev.jellylink.jellyfin

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

data class JellyfinMetadata(
    val id: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val lengthMs: Long?,
    val artworkUrl: String?
)

@Component
class JellyfinMetadataStore {
    private val data = ConcurrentHashMap<String, JellyfinMetadata>()

    fun put(url: String, metadata: JellyfinMetadata) {
        data[url] = metadata
    }

    fun get(url: String): JellyfinMetadata? = data[url]
}
