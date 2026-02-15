package dev.jellylink.jellyfin

import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class JellyfinAudioSourceManager(
    private val config: JellyfinConfig,
    private val metadataStore: JellyfinMetadataStore
) : AudioSourceManager {

    private val log = LoggerFactory.getLogger(JellyfinAudioSourceManager::class.java)
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    val containerRegistry: MediaContainerRegistry = MediaContainerRegistry.DEFAULT_REGISTRY
    private val httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager()
    @Volatile
    private var accessToken: String? = null
    @Volatile
    private var userId: String? = null
    @Volatile
    private var tokenObtainedAt: Instant? = null

    fun getHttpInterface(): HttpInterface = httpInterfaceManager.`interface`

    override fun getSourceName(): String = "jellyfin"

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        val identifier = reference.identifier ?: return null
        val prefix = "jfsearch:"
        if (!identifier.startsWith(prefix, ignoreCase = true)) {
            return null
        }
        log.info("Jellyfin source handling identifier: {}", identifier)

        if (!ensureAuthenticated()) {
            log.error("Jellyfin authentication failed. Check baseUrl, username, and password in jellylink config.")
            return null
        }

        val query = identifier.substring(prefix.length).trim()
        if (query.isEmpty()) return null

        val item = searchFirstAudioItem(query)
        if (item == null) {
            log.warn("No Jellyfin results found for query: {}", query)
            return null
        }
        log.info("Jellyfin found: {} - {} [{}]", item.artist ?: "Unknown", item.title ?: "Unknown", item.id)

        val playbackUrl = buildPlaybackUrl(item.id)
        log.info("Jellyfin playback URL: {}", playbackUrl)

        metadataStore.put(playbackUrl, item)

        val trackInfo = AudioTrackInfo(
            item.title ?: "Unknown",
            item.artist ?: "Unknown",
            item.lengthMs ?: Long.MAX_VALUE,
            item.id,
            false,
            playbackUrl,
            item.artworkUrl,
            null
        )
        return JellyfinAudioTrack(trackInfo, this)
    }

    /**
     * Invalidate the current token so the next call to [ensureAuthenticated] will re-authenticate.
     */
    private fun invalidateToken() {
        log.info("Invalidating Jellyfin access token")
        accessToken = null
        userId = null
        tokenObtainedAt = null
    }

    private fun isTokenExpired(): Boolean {
        val refreshMinutes = config.tokenRefreshMinutes
        if (refreshMinutes <= 0) return false // automatic refresh disabled
        val obtainedAt = tokenObtainedAt ?: return true
        return Instant.now().isAfter(obtainedAt.plusSeconds(refreshMinutes * 60L))
    }

    private fun ensureAuthenticated(): Boolean {
        if (accessToken != null && userId != null && !isTokenExpired()) return true
        // Token missing or expired — (re-)authenticate
        if (accessToken != null && isTokenExpired()) {
            log.info("Jellyfin access token expired after {} minutes, re-authenticating", config.tokenRefreshMinutes)
            invalidateToken()
        }
        if (config.baseUrl.isBlank() || config.username.isBlank() || config.password.isBlank()) return false

        val url = config.baseUrl.trimEnd('/') + "/Users/AuthenticateByName"
        val body = """{"Username":"${escape(config.username)}","Pw":"${escape(config.password)}"}"""

        val authHeader = "MediaBrowser Client=\"Jellylink\", Device=\"Lavalink\", DeviceId=\"${UUID.randomUUID()}\", Version=\"0.1.0\""

        val request = HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .header("Content-Type", "application/json")
            .header("X-Emby-Authorization", authHeader)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            log.error("Jellyfin auth failed with status {}: {}", response.statusCode(), response.body().take(500))
            return false
        }

        log.info("Successfully authenticated with Jellyfin")
        val responseJson = json.parseToJsonElement(response.body()).jsonObject
        val token = responseJson["AccessToken"]?.jsonPrimitive?.contentOrNull
        val uid = responseJson["User"]?.jsonObject?.get("Id")?.jsonPrimitive?.contentOrNull

        if (token == null || uid == null) {
            log.error("Jellyfin auth response missing AccessToken or User.Id")
            return false
        }

        accessToken = token
        userId = uid
        tokenObtainedAt = Instant.now()
        return true
    }

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun searchFirstAudioItem(query: String): JellyfinMetadata? {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = StringBuilder()
            .append(config.baseUrl.trimEnd('/'))
            .append("/Items?SearchTerm=")
            .append(encodedQuery)
            .append("&IncludeItemTypes=Audio&Recursive=true&Limit=")
            .append(config.searchLimit)
            .append("&Fields=Artists,AlbumArtist,MediaSources,ImageTags")
            .toString()

        val request = HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .header("X-Emby-Token", accessToken ?: return null)
            .GET()
            .build()

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        // If 401, the token may have been revoked server-side — re-authenticate and retry once
        if (response.statusCode() == 401) {
            log.warn("Jellyfin search returned 401 — token may have been revoked, re-authenticating")
            invalidateToken()
            if (!ensureAuthenticated()) {
                log.error("Jellyfin re-authentication failed after 401")
                return null
            }
            val retryRequest = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("X-Emby-Token", accessToken ?: return null)
                .GET()
                .build()
            response = httpClient.send(retryRequest, HttpResponse.BodyHandlers.ofString())
        }

        if (response.statusCode() !in 200..299) {
            log.error("Jellyfin search failed with status {}: {}", response.statusCode(), response.body().take(500))
            return null
        }

        val body = response.body()
        log.debug("Jellyfin search response: {}", body.take(2000))

        val responseJson = json.parseToJsonElement(body).jsonObject
        val items = responseJson["Items"]?.jsonArray
        if (items.isNullOrEmpty()) return null

        val item = items[0].jsonObject
        val id = item["Id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = item["Name"]?.jsonPrimitive?.contentOrNull
        val artist = item["AlbumArtist"]?.jsonPrimitive?.contentOrNull
            ?: item["Artists"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
        val album = item["Album"]?.jsonPrimitive?.contentOrNull

        val runTimeTicks = item["RunTimeTicks"]?.jsonPrimitive?.longOrNull
        val lengthMs = runTimeTicks?.let { it / 10_000 }

        val imageTag = item["ImageTags"]?.jsonObject?.get("Primary")?.jsonPrimitive?.contentOrNull
        val baseUrl = config.baseUrl.trimEnd('/')
        val artUrl = if (imageTag != null) {
            "$baseUrl/Items/$id/Images/Primary?tag=$imageTag"
        } else {
            "$baseUrl/Items/$id/Images/Primary"
        }
        log.info("Jellyfin artwork URL: {} (tag={})", artUrl, imageTag)

        return JellyfinMetadata(
            id = id,
            title = title,
            artist = artist,
            album = album,
            lengthMs = lengthMs,
            artworkUrl = artUrl
        )
    }

    private fun buildPlaybackUrl(itemId: String): String {
        val base = config.baseUrl.trimEnd('/')
        val token = accessToken ?: ""
        val quality = config.audioQuality.trim().uppercase()

        if (quality == "ORIGINAL") {
            return "$base/Audio/$itemId/stream?static=true&api_key=$token"
        }

        val bitrate = when (quality) {
            "HIGH"   -> 320000
            "MEDIUM" -> 192000
            "LOW"    -> 128000
            else     -> {
                val custom = config.audioQuality.trim().toIntOrNull()
                if (custom != null) custom * 1000 else 320000
            }
        }
        val codec = config.audioCodec.trim().ifEmpty { "mp3" }

        return "$base/Audio/$itemId/stream?audioBitRate=$bitrate&audioCodec=$codec&api_key=$token"
    }

    override fun isTrackEncodable(track: AudioTrack): Boolean = true

    @Throws(IOException::class)
    override fun encodeTrack(track: AudioTrack, output: DataOutput) {
        // No additional data to encode beyond AudioTrackInfo.
    }

    @Throws(IOException::class)
    override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack {
        return JellyfinAudioTrack(trackInfo, this)
    }

    override fun shutdown() {
        httpInterfaceManager.close()
    }
}
