package org.schabi.newpipe.local.filter

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Applies a [VideoFilter] to a mixed list (search results): videos are filtered and ordered,
 * channels and playlists are kept, ahead of the videos when the order is changed.
 */
object InfoItemsFilter {
    @JvmStatic
    fun apply(filter: VideoFilter, items: List<InfoItem>): List<InfoItem> {
        if (filter.isDefault) {
            return items
        }
        if (filter.sort == VideoFilter.Sort.DEFAULT) {
            // keep the original order: drop the videos that do not pass
            val kept = filter.apply(items.filterIsInstance<StreamInfoItem>()) { VideoFacts.of(it) }.toHashSet()
            return items.filter { it !is StreamInfoItem || it in kept }
        }
        val others = items.filter { it !is StreamInfoItem }
        val videos = filter.apply(items.filterIsInstance<StreamInfoItem>()) { VideoFacts.of(it) }
        return others + videos
    }
}
