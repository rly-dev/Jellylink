package dev.jellylink.jellyfin.client

import dev.jellylink.jellyfin.config.JellyfinConfig
import dev.jellylink.jellyfin.model.JellyfinMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * Handles all HTTP communication with the Jellyfin server.
 *
 * Responsibilities:
 * - Authentication (login, token refresh, invalidation)
 * - Sending search requests (with automatic 401 retry)
 * - Building audio playback URLs
 */
@Component
class JellyfinApiClient(
    private val config: JellyfinConfig,
    private val responseParser: JellyfinResponseParser = JellyfinResponseParser(),
) {
    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    private var userId: String? = null

    @Volatile
    private var tokenObtainedAt: Instant? = null

    // -----------------------------------------------------------------------
    // Authentication
    // -----------------------------------------------------------------------

    /**
     * Ensure a valid access token is available, authenticating if necessary.
     *
     * @return `true` when a valid token is ready for use
     */
    fun ensureAuthenticated(): Boolean {
        if (accessToken != null && userId != null && !isTokenExpired()) {
            return true
        }

        if (accessToken != null && isTokenExpired()) {
            log.info("Jellyfin access token expired after {} minutes, re-authenticating", config.tokenRefreshMinutes)
            invalidateToken()
        }

        if (config.baseUrl.isBlank() || config.username.isBlank() || config.password.isBlank()) {
            return false
        }

        return authenticate()
    }

    /**
     * Invalidate the current token so the next call will re-authenticate.
     */
    fun invalidateToken() {
        log.info("Invalidating Jellyfin access token")
        accessToken = null
        userId = null
        tokenObtainedAt = null
    }

    private fun isTokenExpired(): Boolean {
        val refreshMinutes = config.tokenRefreshMinutes

        if (refreshMinutes <= 0) {
            return false
        }

        val obtainedAt = tokenObtainedAt ?: return true

        return Instant.now().isAfter(obtainedAt.plusSeconds(refreshMinutes * SECONDS_PER_MINUTE))
    }

    private fun authenticate(): Boolean {
        val url = config.baseUrl.trimEnd('/') + "/Users/AuthenticateByName"
        val body = """{"Username":"${escape(config.username)}","Pw":"${escape(config.password)}"}"""
        val authHeader = "MediaBrowser Client=\"Jellylink\", Device=\"Lavalink\", DeviceId=\"${UUID.randomUUID()}\", Version=\"0.1.0\""

        val request = HttpRequest
            .newBuilder()
            .uri(java.net.URI.create(url))
            .header("Content-Type", "application/json")
            .header("X-Emby-Authorization", authHeader)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in HTTP_OK_RANGE) {
            log.error("Jellyfin auth failed with status {}: {}", response.statusCode(), response.body().take(ERROR_BODY_PREVIEW_LENGTH))
            return false
        }

        log.info("Successfully authenticated with Jellyfin")
        val result = responseParser.parseAuthResponse(response.body()) ?: return false

        accessToken = result.accessToken
        userId = result.userId
        tokenObtainedAt = Instant.now()

        return true
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    /**
     * Search Jellyfin for the first audio item matching [query].
     *
     * Handles 401 retry transparently.
     *
     * @return parsed [JellyfinMetadata], or `null` if no result / error
     */
    fun searchFirstAudioItem(query: String): JellyfinMetadata? {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = StringBuilder()
            .append(config.baseUrl.trimEnd('/'))
            .append("/Items?SearchTerm=")
            .append(encodedQuery)
            .append("&IncludeItemTypes=Audio&Recursive=true&Limit=")
            .append(config.searchLimit)
            .append("&Fields=Artists,AlbumArtist,MediaSources,ImageTags")
            .toString()

        val response = executeGetWithRetry(url) ?: return null

        if (response.statusCode() !in HTTP_OK_RANGE) {
            log.error("Jellyfin search failed with status {}: {}", response.statusCode(), response.body().take(ERROR_BODY_PREVIEW_LENGTH))
            return null
        }

        val body = response.body()
        log.debug("Jellyfin search response: {}", body.take(DEBUG_BODY_PREVIEW_LENGTH))

        return responseParser.parseFirstAudioItem(body, config.baseUrl)
    }

    /**
     * Execute a GET request, retrying once on 401 (server-side token revocation).
     */
    private fun executeGetWithRetry(url: String): HttpResponse<String>? {
        val request = buildGetRequest(url) ?: return null

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == HTTP_UNAUTHORIZED) {
            log.warn("Jellyfin returned 401 — token may have been revoked, re-authenticating")
            invalidateToken()

            if (!ensureAuthenticated()) {
                log.error("Jellyfin re-authentication failed after 401")
                return null
            }

            val retryRequest = buildGetRequest(url) ?: return null
            response = httpClient.send(retryRequest, HttpResponse.BodyHandlers.ofString())
        }

        return response
    }

    private fun buildGetRequest(url: String): HttpRequest? {
        val token = accessToken ?: return null

        return HttpRequest
            .newBuilder()
            .uri(java.net.URI.create(url))
            .header("X-Emby-Token", token)
            .GET()
            .build()
    }

    // -----------------------------------------------------------------------
    // Playback URL
    // -----------------------------------------------------------------------

    /**
     * Build a streaming URL for the given Jellyfin item, respecting audio quality settings.
     */
    fun buildPlaybackUrl(itemId: String): String {
        val base = config.baseUrl.trimEnd('/')
        val token = accessToken ?: ""
        val quality = config.audioQuality.trim().uppercase()

        if (quality == "ORIGINAL") {
            return "$base/Audio/$itemId/stream?static=true&api_key=$token"
        }

        val bitrate =
            when (quality) {
                "HIGH" -> BITRATE_HIGH
                "MEDIUM" -> BITRATE_MEDIUM
                "LOW" -> BITRATE_LOW
                else -> {
                    val custom = config.audioQuality.trim().toIntOrNull()

                    if (custom != null) {
                        custom * KBPS_TO_BPS
                    } else {
                        BITRATE_HIGH
                    }
                }
            }
        val codec = config.audioCodec.trim().ifEmpty { "mp3" }

        return "$base/Audio/$itemId/stream?audioBitRate=$bitrate&audioCodec=$codec&api_key=$token"
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private val log = LoggerFactory.getLogger(JellyfinApiClient::class.java)
        private val httpClient: HttpClient = HttpClient.newHttpClient()

        private val HTTP_OK_RANGE = 200..299
        private const val HTTP_UNAUTHORIZED = 401
        private const val ERROR_BODY_PREVIEW_LENGTH = 500
        private const val DEBUG_BODY_PREVIEW_LENGTH = 2000

        private const val SECONDS_PER_MINUTE = 60L
        private const val BITRATE_HIGH = 320_000
        private const val BITRATE_MEDIUM = 192_000
        private const val BITRATE_LOW = 128_000
        private const val KBPS_TO_BPS = 1000
    }
}
