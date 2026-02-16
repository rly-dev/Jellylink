package dev.jellylink.jellyfin.audio

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.lavalink.api.AudioPluginInfoModifier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.stereotype.Component

@Component
class JellyfinAudioPluginInfoModifier : AudioPluginInfoModifier {
    override fun modifyAudioTrackPluginInfo(track: AudioTrack): JsonObject? {
        if (track !is JellyfinAudioTrack) {
            return null
        }

        val map = buildMap<String, JsonPrimitive> {
            put("jellyfinId", JsonPrimitive(track.jellyfinId))
            track.jellyfinArtist?.let { put("jellyfinArtist", JsonPrimitive(it)) }
            track.jellyfinAlbum?.let { put("jellyfinAlbum", JsonPrimitive(it)) }
            track.artworkUrl?.let { put("jellyfinArtworkUrl", JsonPrimitive(it)) }
        }

        return if (map.isEmpty()) {
            null
        } else {
            JsonObject(map)
        }
    }
}
