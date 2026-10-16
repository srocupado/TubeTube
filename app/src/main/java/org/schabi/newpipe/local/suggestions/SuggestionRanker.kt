package org.schabi.newpipe.local.suggestions

import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Ranks "similar videos" gathered from several seed videos into one suggestion list.
 * Pure function: everything it needs is passed in, nothing leaves the device.
 */
object SuggestionRanker {
    /** At most this many suggestions from the same channel, so one channel cannot take over. */
    const val MAX_PER_CHANNEL = 3
    const val DEFAULT_LIMIT = 60

    /** Candidates from subscribed channels get this score multiplier. */
    private const val SUBSCRIBED_BONUS = 1.5

    /**
     * @param relatedPerSeed related items of each seed video, most relevant seed first
     *                       (e.g. most recently watched)
     * @param excludedUrls   videos not to suggest (already watched, the seeds themselves)
     * @param subscribedChannelUrls channels the user follows
     */
    fun rank(
        relatedPerSeed: List<List<StreamInfoItem>>,
        excludedUrls: Set<String>,
        subscribedChannelUrls: Set<String>,
        limit: Int = DEFAULT_LIMIT
    ): List<StreamInfoItem> {
        val scores = LinkedHashMap<String, Double>()
        val items = HashMap<String, StreamInfoItem>()

        relatedPerSeed.forEachIndexed { seedIndex, related ->
            // more recent seeds weigh more, and so do the first "similar" items of each seed
            val seedWeight = 1.0 / (1.0 + 0.25 * seedIndex)
            related.forEachIndexed { position, item ->
                val url = item.url
                if (url.isNullOrEmpty() || url in excludedUrls || isLive(item)) {
                    return@forEachIndexed
                }
                val positionWeight = 1.0 / (1.0 + 0.15 * position)
                scores[url] = (scores[url] ?: 0.0) + seedWeight * positionWeight
                items.putIfAbsent(url, item)
            }
        }

        val perChannel = HashMap<String, Int>()
        return scores.entries
            .map { (url, score) ->
                val item = items.getValue(url)
                val bonus = if (item.uploaderUrl in subscribedChannelUrls) SUBSCRIBED_BONUS else 1.0
                item to score * bonus
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .filter { item ->
                val channel = item.uploaderUrl ?: item.uploaderName ?: return@filter true
                val count = perChannel.getOrDefault(channel, 0)
                perChannel[channel] = count + 1
                count < MAX_PER_CHANNEL
            }
            .take(limit)
    }

    private fun isLive(item: StreamInfoItem) = item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM
}
