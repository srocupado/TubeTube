package org.schabi.newpipe.local.filter

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class InfoItemsFilterTest {
    private fun video(name: String, seconds: Long, views: Long = 0) = StreamInfoItem(0, "https://youtu.be/$name", name, StreamType.VIDEO_STREAM).apply {
        duration = seconds
        viewCount = views
    }

    private val channel = ChannelInfoItem(0, "https://www.youtube.com/channel/x", "A channel")

    private fun List<InfoItem>.names() = map { it.name }

    @Test
    fun keepsChannelsAndOrderWhenOnlyFiltering() {
        val items = listOf(video("short", 60), channel, video("long", 3600))
        val result = InfoItemsFilter.apply(VideoFilter(length = VideoFilter.Length.LONG), items)
        assertEquals(listOf("A channel", "long"), result.names())
    }

    @Test
    fun sortingPutsOtherItemsFirst() {
        val items = listOf(video("few", 600, 10), channel, video("many", 600, 1000))
        val result = InfoItemsFilter.apply(VideoFilter(sort = VideoFilter.Sort.MOST_VIEWED), items)
        assertEquals(listOf("A channel", "many", "few"), result.names())
    }

    @Test
    fun defaultFilterChangesNothing() {
        val items = listOf(video("a", 60), channel)
        assertEquals(items, InfoItemsFilter.apply(VideoFilter(), items))
    }
}
