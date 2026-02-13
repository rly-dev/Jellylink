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
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class JellyfinAudioSourceManager(
    private val config: JellyfinConfig,
    private val metadataStore: JellyfinMetadataStore
) : AudioSourceManager {

    private val log = LoggerFactory.getLogger(JellyfinAudioSourceManager::class.java)
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    val containerRegistry: MediaContainerRegistry = MediaContainerRegistry.DEFAULT_REGISTRY
    private val httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager()
    @Volatile
    private var accessToken: String? = null
    @Volatile
    private var userId: String? = null

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

    private fun ensureAuthenticated(): Boolean {
        if (accessToken != null && userId != null) return true
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
        val bodyText = response.body()
        val tokenKey = "\"AccessToken\":\""
        val tokenIndex = bodyText.indexOf(tokenKey)
        if (tokenIndex == -1) return false
        val tokenStart = tokenIndex + tokenKey.length
        val tokenEnd = bodyText.indexOf('"', tokenStart)
        if (tokenEnd <= tokenStart) return false
        val token = bodyText.substring(tokenStart, tokenEnd)

        val userKey = "\"User\":{"
        val userIndex = bodyText.indexOf(userKey)
        if (userIndex == -1) return false
        val idKey = "\"Id\":\""
        val idIndex = bodyText.indexOf(idKey, userIndex)
        if (idIndex == -1) return false
        val idStart = idIndex + idKey.length
        val idEnd = bodyText.indexOf('"', idStart)
        if (idEnd <= idStart) return false
        val uid = bodyText.substring(idStart, idEnd)

        accessToken = token
        userId = uid
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

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            log.error("Jellyfin search failed with status {}: {}", response.statusCode(), response.body().take(500))
            return null
        }

        val body = response.body()
        log.debug("Jellyfin search response: {}", body.take(2000))

        // Find the first item in the Items array
        val itemsIdx = body.indexOf("\"Items\":[")
        if (itemsIdx == -1) return null
        val firstItemStart = body.indexOf("{", itemsIdx + 9)
        if (firstItemStart == -1) return null

        // Take a generous chunk for the first item
        val itemChunk = body.substring(firstItemStart, minOf(body.length, firstItemStart + 5000))

        val id = extractJsonString(itemChunk, "Id") ?: return null
        val title = extractJsonString(itemChunk, "Name")
        val artist = extractJsonString(itemChunk, "AlbumArtist")
            ?: extractFirstArrayElement(itemChunk, "Artists")
        val album = extractJsonString(itemChunk, "Album")

        val runtimeTicks = extractJsonLong(itemChunk, "RunTimeTicks")
        val lengthMs = runtimeTicks?.let { it / 10_000 }

        val imageTag = extractJsonString(itemChunk, "Primary")
        // Always provide an artwork URL — Jellyfin will serve the image even without the tag param
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

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\":\""
        val idx = json.indexOf(pattern)
        if (idx == -1) return null
        val start = idx + pattern.length
        val end = findUnescapedQuote(json, start)
        return if (end > start) unescapeJson(json.substring(start, end)) else null
    }

    private fun extractFirstArrayElement(json: String, key: String): String? {
        val pattern = "\"$key\":[\""
        val idx = json.indexOf(pattern)
        if (idx == -1) return null
        val start = idx + pattern.length
        val end = findUnescapedQuote(json, start)
        return if (end > start) unescapeJson(json.substring(start, end)) else null
    }

    /** Find the next unescaped double-quote starting from [from]. */
    private fun findUnescapedQuote(json: String, from: Int): Int {
        var i = from
        while (i < json.length) {
            when (json[i]) {
                '\\' -> i += 2 // skip escaped character
                '"'  -> return i
                else -> i++
            }
        }
        return -1
    }

    /** Decode JSON string escape sequences: \\uXXXX, \\n, \\t, \\\\, \\", etc. */
    private fun unescapeJson(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val cp = hex.toIntOrNull(16)
                            if (cp != null) {
                                sb.append(cp.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(s[i])
                        i++
                    }
                    'n'  -> { sb.append('\n'); i += 2 }
                    't'  -> { sb.append('\t'); i += 2 }
                    'r'  -> { sb.append('\r'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"'  -> { sb.append('"');  i += 2 }
                    '/'  -> { sb.append('/');  i += 2 }
                    else -> { sb.append(s[i]); i++ }
                }
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val pattern = "\"$key\":"
        val idx = json.indexOf(pattern)
        if (idx == -1) return null
        val start = idx + pattern.length
        var end = start
        while (end < json.length && json[end].isDigit()) end++
        return if (end > start) json.substring(start, end).toLongOrNull() else null
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
