package org.schabi.newpipe.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList

class KioskVisibilityTest {
    @Test
    fun legacyYoutubeTrendingIsHidden() {
        val kiosks = KioskVisibility.availableKiosks(ServiceList.YouTube)
        assertFalse("Trending" in kiosks)
        assertTrue("live" in kiosks)
        assertTrue("trending_music" in kiosks)
    }

    @Test
    fun otherServicesAreUntouched() {
        assertFalse(KioskVisibility.isHidden(ServiceList.SoundCloud.serviceId, "Trending"))
    }
}
