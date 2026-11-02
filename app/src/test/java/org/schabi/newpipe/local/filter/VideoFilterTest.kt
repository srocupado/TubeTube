package org.schabi.newpipe.local.filter

import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFilterTest {
    private val now = OffsetDateTime.of(2026, 11, 2, 12, 0, 0, 0, ZoneOffset.UTC)

    private fun video(
        title: String,
        daysAgo: Long? = 1,
        minutes: Long = 10,
        views: Long = 100,
        live: Boolean = false,
        short: Boolean = false,
        channel: String = "ch"
    ) = VideoFacts(
        title = title,
        uploaderUrl = channel,
        uploadDate = daysAgo?.let { now.minusDays(it) },
        durationSeconds = minutes * 60,
        viewCount = views,
        isLiveOrUpcoming = live,
        isShort = short
    )

    private fun VideoFilter.run(vararg items: VideoFacts, subs: Set<String> = emptySet()) = apply(items.toList(), subs, now) { it }.map { it.title }

    @Test
    fun defaultFilterKeepsEverythingInOrder() {
        assertEquals(listOf("a", "b"), VideoFilter().run(video("a"), video("b")))
    }

    @Test
    fun periodHidesOlderVideosButKeepsUnknownDates() {
        val filter = VideoFilter(period = VideoFilter.Period.WEEK)
        assertEquals(
            listOf("new", "unknown"),
            filter.run(video("new", daysAgo = 2), video("old", daysAgo = 30), video("unknown", daysAgo = null))
        )
    }

    @Test
    fun lengthRanges() {
        val items = arrayOf(video("2min", minutes = 2), video("10min", minutes = 10), video("1h", minutes = 60))
        assertEquals(listOf("2min"), VideoFilter(length = VideoFilter.Length.SHORT).run(*items))
        assertEquals(listOf("10min"), VideoFilter(length = VideoFilter.Length.MEDIUM).run(*items))
        assertEquals(listOf("1h"), VideoFilter(length = VideoFilter.Length.LONG).run(*items))
    }

    @Test
    fun hidesShortsLiveWordsAndOtherChannels() {
        val items = arrayOf(
            video("Short", short = true),
            video("Live", live = true),
            video("REACT to stuff"),
            video("Other channel", channel = "other"),
            video("Keep")
        )
        val filter = VideoFilter(hideShorts = true, hideLive = true, onlySubscribed = true, hiddenWords = listOf("react"))
        assertEquals(listOf("Keep"), filter.run(*items, subs = setOf("ch")))
    }

    @Test
    fun sorting() {
        val items = arrayOf(
            video("old", daysAgo = 10, minutes = 5, views = 1000),
            video("new", daysAgo = 1, minutes = 30, views = 10),
            video("undated", daysAgo = null, minutes = 1, views = 50)
        )
        assertEquals(listOf("new", "old", "undated"), VideoFilter(sort = VideoFilter.Sort.NEWEST).run(*items))
        assertEquals(listOf("old", "undated", "new"), VideoFilter(sort = VideoFilter.Sort.MOST_VIEWED).run(*items))
        assertEquals(listOf("undated", "old", "new"), VideoFilter(sort = VideoFilter.Sort.SHORTEST).run(*items))
        assertEquals(listOf("new", "old", "undated"), VideoFilter(sort = VideoFilter.Sort.LONGEST).run(*items))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val filter = VideoFilter(
            VideoFilter.Period.MONTH,
            VideoFilter.Length.LONG,
            VideoFilter.Sort.NEWEST,
            hideShorts = true,
            hideLive = false,
            onlySubscribed = true,
            hiddenWords = listOf("react", "unboxing")
        )
        assertEquals(filter, VideoFilter.decode(filter.encode()))
        assertEquals(6, filter.activeCount)
    }

    @Test
    fun decodeToleratesGarbage() {
        assertTrue(VideoFilter.decode("period=NOPE;foo").isDefault)
        assertTrue(VideoFilter.decode(null).isDefault)
        assertEquals(listOf("a", "b"), VideoFilter.parseWords(" a, ,b,a "))
    }
}
