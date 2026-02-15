package dev.jellylink.jellyfin.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "plugins.jellylink.jellyfin")
class JellyfinConfig {
    var baseUrl: String = ""
    var username: String = ""
    var password: String = ""
    var searchLimit: Int = 5

    /**
     * Audio quality preset. Controls transcoding behavior.
     * - "ORIGINAL" (default) — serves the raw file (FLAC, MP3, etc.) without transcoding
     * - "HIGH"     — transcodes to 320 kbps
     * - "MEDIUM"   — transcodes to 192 kbps
     * - "LOW"      — transcodes to 128 kbps
     * - Any integer — custom bitrate in kbps (e.g. "256")
     */
    var audioQuality: String = "ORIGINAL"

    /**
     * Audio codec to transcode to when audioQuality is not ORIGINAL.
     * Common values: "mp3", "aac", "opus", "vorbis", "flac"
     * Default: "mp3"
     */
    var audioCodec: String = "mp3"

    /**
     * How often (in minutes) the Jellyfin access token should be refreshed.
     * The plugin will re-authenticate automatically before the token expires.
     * Set to 0 to disable automatic refresh (token is obtained once and reused until a 401 occurs).
     * Default: 30
     */
    var tokenRefreshMinutes: Int = 30
}
