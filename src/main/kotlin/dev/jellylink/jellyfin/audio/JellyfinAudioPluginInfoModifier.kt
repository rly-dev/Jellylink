package dev.jellylink.jellyfin.audio

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.lavalink.api.AudioPluginInfoModifier
import dev.jellylink.jellyfin.config.JellyfinConfig
import dev.jellylink.jellyfin.model.JellyfinMetadataStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.stereotype.Component

@Component
class JellyfinAudioPluginInfoModifier(
    private val metadataStore: JellyfinMetadataStore,
    private val config: JellyfinConfig,
) : AudioPluginInfoModifier {
    override fun modifyAudioTrackPluginInfo(track: AudioTrack): JsonObject? {
        val uri = track.info.uri ?: return null

        if (!uri.startsWith(config.baseUrl.trimEnd('/'))) {
            return null
        }

        val meta = metadataStore.get(uri) ?: return null

        val map = buildMap<String, JsonPrimitive> {
            meta.id.let { put("jellyfinId", JsonPrimitive(it)) }
            meta.title?.let { put("jellyfinTitle", JsonPrimitive(it)) }
            meta.artist?.let { put("jellyfinArtist", JsonPrimitive(it)) }
            meta.album?.let { put("jellyfinAlbum", JsonPrimitive(it)) }
            meta.lengthMs?.let { put("jellyfinLengthMs", JsonPrimitive(it)) }
            meta.artworkUrl?.let { put("jellyfinArtworkUrl", JsonPrimitive(it)) }
        }

        return if (map.isEmpty()) {
            null
        } else {
            JsonObject(map)
        }
    }
}
