package org.schabi.newpipe.sponsorblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockTest {
    private val promo = listOf("sponsor", "selfpromo", "interaction")

    @Test
    fun onlyAHashPrefixOfTheVideoIdIsSent() {
        // reference value computed independently: sha256("dQw4w9WgXcQ")[:4]
        assertEquals("5f6b", SponsorBlock.hashPrefix("dQw4w9WgXcQ"))
        assertEquals(SponsorBlock.HASH_PREFIX_LENGTH, SponsorBlock.hashPrefix("x").length)
    }

    @Test
    fun picksOurVideoOutOfTheSharedPrefixResponse() {
        val json = """
            [
              {"videoID": "otherVideo1", "segments": [
                {"category": "sponsor", "actionType": "skip", "segment": [1.0, 2.0], "UUID": "o1"}
              ]},
              {"videoID": "ourVideo123", "segments": [
                {"category": "selfpromo", "actionType": "skip", "segment": [300.5, 320.0], "UUID": "b"},
                {"category": "sponsor", "actionType": "skip", "segment": [60.25, 90.0], "UUID": "a"},
                {"category": "intro", "actionType": "skip", "segment": [0.0, 5.0], "UUID": "c"}
              ]}
            ]
        """.trimIndent()
        val result = SponsorBlock.parseSegments(json, "ourVideo123", promo)
        assertEquals(
            listOf(
                Segment("sponsor", 60_250, 90_000, "a"),
                Segment("selfpromo", 300_500, 320_000, "b")
            ),
            result.skip
        )
        assertNull(result.fullVideoCategory)
    }

    @Test
    fun wholeVideoAdIsReported() {
        val json = """
            [{"videoID": "v", "segments": [
              {"category": "sponsor", "actionType": "full", "segment": [0, 0], "UUID": "f"}
            ]}]
        """.trimIndent()
        val result = SponsorBlock.parseSegments(json, "v", promo)
        assertEquals("sponsor", result.fullVideoCategory)
        assertTrue(result.skip.isEmpty())
    }

    @Test
    fun brokenOrForeignResponsesYieldNothing() {
        assertEquals(VideoSegments.NONE, SponsorBlock.parseSegments("not json", "v", promo))
        assertEquals(VideoSegments.NONE, SponsorBlock.parseSegments("[]", "v", promo))
        val invalidBounds = """
            [{"videoID": "v", "segments": [
              {"category": "sponsor", "actionType": "skip", "segment": [10.0, 5.0], "UUID": "x"},
              {"category": "sponsor", "actionType": "skip", "segment": [10.0], "UUID": "y"}
            ]}]
        """.trimIndent()
        assertTrue(SponsorBlock.parseSegments(invalidBounds, "v", promo).skip.isEmpty())
    }
}
