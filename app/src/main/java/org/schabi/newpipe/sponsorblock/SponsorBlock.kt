package org.schabi.newpipe.sponsorblock

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** A part of a video to skip, in milliseconds. */
data class Segment(val category: String, val startMs: Long, val endMs: Long, val key: String)

data class VideoSegments(
    /** Parts to skip, sorted by start. */
    val skip: List<Segment>,
    /** Set when the whole video is labelled as promotional (e.g. "sponsor"), else null. */
    val fullVideoCategory: String?
) {
    companion object {
        val NONE = VideoSegments(emptyList(), null)
    }
}

/**
 * Client for the public, crowd-sourced SponsorBlock database (https://sponsor.ajay.app).
 *
 * Privacy: requests go through [DownloaderImpl], i.e. through the privacy tunnel, and use the
 * k-anonymity endpoint: only the first [HASH_PREFIX_LENGTH] hex chars of SHA-256(videoId) are
 * sent, and the server answers with the segments of every video sharing that prefix.
 */
object SponsorBlock {
    private const val API_URL = "https://sponsor.ajay.app/api/skipSegments/"
    const val HASH_PREFIX_LENGTH = 4

    /** Whole-video labels SponsorBlock uses for promotional videos. */
    private val FULL_VIDEO_CATEGORIES = listOf("sponsor", "selfpromo", "exclusive_access")

    /** Blocking: call off the main thread. Returns [VideoSegments.NONE] if none or on error. */
    fun fetchSegments(videoId: String, categories: Collection<String>): VideoSegments {
        val requested = (categories + FULL_VIDEO_CATEGORIES).distinct()
        val url = API_URL + hashPrefix(videoId) +
            "?categories=" + URLEncoder.encode(toJsonArray(requested), "UTF-8") +
            "&actionTypes=" + URLEncoder.encode("[\"skip\",\"full\"]", "UTF-8")
        val response = try {
            DownloaderImpl.getInstance().get(url)
        } catch (e: IOException) {
            return VideoSegments.NONE
        } catch (e: ReCaptchaException) {
            // HTTP 429: rate limited (can happen on shared Tor exits)
            return VideoSegments.NONE
        }
        if (response.responseCode() != 200) {
            return VideoSegments.NONE // 404: no segments for any video with this prefix
        }
        return parseSegments(response.responseBody(), videoId, categories)
    }

    fun hashPrefix(videoId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_PREFIX_LENGTH)
    }

    /**
     * Picks the segments of [videoId] out of a hash-prefix response: skippable parts of the
     * wanted [categories], and whether the whole video is labelled promotional.
     */
    fun parseSegments(json: String, videoId: String, categories: Collection<String>): VideoSegments {
        val videos: JsonArray = try {
            JsonParser.array().from(json)
        } catch (e: JsonParserException) {
            return VideoSegments.NONE
        }
        val video = videos.filterIsInstance<JsonObject>()
            .firstOrNull { it.getString("videoID") == videoId } ?: return VideoSegments.NONE
        val segments = video.getArray("segments")?.filterIsInstance<JsonObject>()
            ?: return VideoSegments.NONE

        val fullVideo = segments
            .firstOrNull { it.getString("actionType") == "full" }
            ?.getString("category")

        val skip = segments
            .filter { it.getString("actionType", "skip") == "skip" }
            .filter { it.getString("category") in categories }
            .mapNotNull(::toSegment)
            .sortedBy { it.startMs }
        return VideoSegments(skip, fullVideo)
    }

    private fun toSegment(seg: JsonObject): Segment? {
        val bounds = seg.getArray("segment")
        if (bounds == null || bounds.size < 2) {
            return null
        }
        val start = (bounds.getDouble(0) * 1000).toLong()
        val end = (bounds.getDouble(1) * 1000).toLong()
        if (end <= start) {
            return null
        }
        val key = seg.getString("UUID", "").ifEmpty { "$start-$end" }
        return Segment(seg.getString("category"), start, end, key)
    }

    private fun toJsonArray(values: Collection<String>) = values.joinToString(",", "[", "]") { "\"" + it.replace("\"", "") + "\"" }
}
