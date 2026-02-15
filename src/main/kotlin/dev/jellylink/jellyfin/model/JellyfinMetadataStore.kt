package dev.jellylink.jellyfin.model

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class JellyfinMetadataStore {
    private val data = ConcurrentHashMap<String, JellyfinMetadata>()

    fun put(
        url: String,
        metadata: JellyfinMetadata,
    ) {
        data[url] = metadata
    }

    fun get(url: String): JellyfinMetadata? = data[url]
}
