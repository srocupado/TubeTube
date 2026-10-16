package org.schabi.newpipe.local.suggestions

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListInfo
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Wraps locally computed suggestions into a [ListInfo], so the standard list UI can show them. */
class SuggestionsInfo(items: List<StreamInfoItem>) :
    ListInfo<InfoItem>(
        ServiceList.YouTube.serviceId,
        ListLinkHandler(URL, URL, ID, emptyList(), null),
        ""
    ) {
    init {
        relatedItems = ArrayList<InfoItem>(items)
    }

    companion object {
        const val ID = "tubetube_suggestions"
        const val URL = "tubetube://suggestions"
    }
}
