package dev.jellylink.jellyfin.client

import dev.jellylink.jellyfin.model.JellyfinMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

/**
 * Stateless parser for Jellyfin API JSON responses.
 *
 * Converts raw JSON strings into domain objects used by the plugin.
 */
class JellyfinResponseParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Result of parsing an authentication response.
     */
    data class AuthResult(
        val accessToken: String,
        val userId: String,
    )

    /**
     * Extract [AuthResult] from the Jellyfin AuthenticateByName response body.
     *
     * @return parsed result, or `null` if the required fields are missing
     */
    fun parseAuthResponse(body: String): AuthResult? {
        val root = json.parseToJsonElement(body).jsonObject
        val token = root["AccessToken"]?.jsonPrimitive?.contentOrNull
        val userId =
            root["User"]
                ?.jsonObject
                ?.get("Id")
                ?.jsonPrimitive
                ?.contentOrNull

        if (token == null || userId == null) {
            log.error("Jellyfin auth response missing AccessToken or User.Id")
            return null
        }

        return AuthResult(accessToken = token, userId = userId)
    }

    /**
     * Parse the Items array from a Jellyfin search response and return the first audio item.
     *
     * @param body       raw JSON response body
     * @param baseUrl    Jellyfin server base URL (used for artwork URL construction)
     * @return the first [JellyfinMetadata] found, or `null`
     */
    fun parseFirstAudioItem(
        body: String,
        baseUrl: String,
    ): JellyfinMetadata? {
        val root = json.parseToJsonElement(body).jsonObject
        val items = root["Items"]?.jsonArray

        if (items.isNullOrEmpty()) {
            return null
        }

        return parseAudioItem(items[0].jsonObject, baseUrl)
    }

    /**
     * Convert a single Jellyfin item JSON object into [JellyfinMetadata].
     */
    private fun parseAudioItem(
        item: kotlinx.serialization.json.JsonObject,
        baseUrl: String,
    ): JellyfinMetadata? {
        val id = item["Id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = item["Name"]?.jsonPrimitive?.contentOrNull
        val artist = item["AlbumArtist"]?.jsonPrimitive?.contentOrNull
            ?: item["Artists"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonPrimitive
                ?.contentOrNull

        val album = item["Album"]?.jsonPrimitive?.contentOrNull

        val runTimeTicks = item["RunTimeTicks"]?.jsonPrimitive?.longOrNull
        val lengthMs = runTimeTicks?.let { it / TICKS_PER_MILLISECOND }

        val imageTag = item["ImageTags"]
            ?.jsonObject
            ?.get("Primary")
            ?.jsonPrimitive
            ?.contentOrNull

        val normalizedBase = baseUrl.trimEnd('/')
        val artUrl =
            if (imageTag != null) {
                "$normalizedBase/Items/$id/Images/Primary?tag=$imageTag"
            } else {
                "$normalizedBase/Items/$id/Images/Primary"
            }

        log.info("Jellyfin artwork URL: {} (tag={})", artUrl, imageTag)

        return JellyfinMetadata(
            id = id,
            title = title,
            artist = artist,
            album = album,
            lengthMs = lengthMs,
            artworkUrl = artUrl,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(JellyfinResponseParser::class.java)
        private const val TICKS_PER_MILLISECOND = 10_000L
    }
}
