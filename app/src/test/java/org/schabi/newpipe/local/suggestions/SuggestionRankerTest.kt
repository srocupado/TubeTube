package org.schabi.newpipe.local.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class SuggestionRankerTest {
    private fun video(id: String, channel: String = "ch-$id", type: StreamType = StreamType.VIDEO_STREAM) = StreamInfoItem(0, "https://www.youtube.com/watch?v=$id", id, type).apply {
        uploaderUrl = "https://www.youtube.com/channel/$channel"
        uploaderName = channel
    }

    private fun List<StreamInfoItem>.names() = map { it.name }

    @Test
    fun itemsSimilarToSeveralSeedsRankFirst() {
        val result = SuggestionRanker.rank(
            listOf(listOf(video("a"), video("b")), listOf(video("c"), video("b"))),
            emptySet(),
            emptySet()
        )
        assertEquals(listOf("b", "a", "c"), result.names())
    }

    @Test
    fun watchedAndLiveVideosAreExcluded() {
        val watched = video("a")
        val result = SuggestionRanker.rank(
            listOf(listOf(watched, video("live", type = StreamType.LIVE_STREAM), video("b"))),
            setOf(watched.url),
            emptySet()
        )
        assertEquals(listOf("b"), result.names())
    }

    @Test
    fun oneChannelCannotTakeOver() {
        val sameChannel = (1..6).map { video("v$it", channel = "big") }
        val result = SuggestionRanker.rank(listOf(sameChannel + video("other")), emptySet(), emptySet())
        assertEquals(SuggestionRanker.MAX_PER_CHANNEL, result.count { it.uploaderName == "big" })
        assertEquals("other", result.last().name)
    }

    @Test
    fun subscribedChannelsGetABoost() {
        val subscribed = video("sub", channel = "mine")
        val result = SuggestionRanker.rank(
            listOf(listOf(video("first"), subscribed)),
            emptySet(),
            setOf(subscribed.uploaderUrl)
        )
        assertEquals(listOf("sub", "first"), result.names())
    }

    @Test
    fun limitIsRespected() {
        val many = (1..100).map { video("v$it") }
        val result = SuggestionRanker.rank(listOf(many), emptySet(), emptySet(), limit = 10)
        assertEquals(10, result.size)
        assertFalse(result.isEmpty())
    }
}
