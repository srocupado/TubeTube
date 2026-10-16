package org.schabi.newpipe.util

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService

/** Kiosks the app offers; hides the ones whose page no longer exists. */
object KioskVisibility {
    /**
     * YouTube removed its combined Trending page in 2025. The extractor still lists the kiosk,
     * but it always fails ("Could not get Trending name"); the trending_* kiosks replace it.
     */
    private const val YOUTUBE_LEGACY_TRENDING = "Trending"

    @JvmStatic
    fun isHidden(serviceId: Int, kioskId: String): Boolean = serviceId == ServiceList.YouTube.serviceId && kioskId == YOUTUBE_LEGACY_TRENDING

    @JvmStatic
    fun availableKiosks(service: StreamingService): List<String> = service.kioskList.availableKiosks.filterNot { isHidden(service.serviceId, it) }
}
