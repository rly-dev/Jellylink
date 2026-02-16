package dev.jellylink.jellyfin.audio

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection
import com.sedmelluq.discord.lavaplayer.container.MediaContainerHints
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import org.slf4j.LoggerFactory
import java.net.URI

class JellyfinAudioTrack(
    trackInfo: AudioTrackInfo,
    val jellyfinId: String,
    val jellyfinArtist: String?,
    val jellyfinAlbum: String?,
    val artworkUrl: String?,
    private val sourceManager: JellyfinAudioSourceManager,
) : DelegatedAudioTrack(trackInfo) {
    @Throws(Exception::class)
    override fun process(executor: LocalAudioTrackExecutor) {
        log.info("Processing Jellyfin track: {} ({})", trackInfo.title, trackInfo.uri)

        sourceManager.getHttpInterface().use { httpInterface ->
            PersistentHttpStream(httpInterface, URI(trackInfo.uri), trackInfo.length).use { stream ->
                val result =
                    MediaContainerDetection(
                        sourceManager.containerRegistry,
                        AudioReference(trackInfo.uri, trackInfo.title),
                        stream,
                        MediaContainerHints.from(null, null),
                    ).detectContainer()

                if (result == null || !result.isContainerDetected) {
                    log.error("Could not detect audio container for Jellyfin track: {}", trackInfo.title)

                    throw FriendlyException(
                        "Could not detect audio format from Jellyfin stream",
                        FriendlyException.Severity.COMMON,
                        null,
                    )
                }

                log.info("Detected container '{}' for track: {}", result.containerDescriptor.probe.name, trackInfo.title)

                stream.seek(0)

                processDelegate(
                    result.containerDescriptor.probe.createTrack(
                        result.containerDescriptor.parameters,
                        trackInfo,
                        stream,
                    ) as InternalAudioTrack,
                    executor,
                )
            }
        }
    }

    override fun makeShallowClone(): AudioTrack = JellyfinAudioTrack(trackInfo, jellyfinId, jellyfinArtist, jellyfinAlbum, artworkUrl, sourceManager)

    override fun getSourceManager(): AudioSourceManager = sourceManager

    companion object {
        private val log = LoggerFactory.getLogger(JellyfinAudioTrack::class.java)
    }
}
