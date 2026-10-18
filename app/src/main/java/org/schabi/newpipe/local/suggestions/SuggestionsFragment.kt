package org.schabi.newpipe.local.suggestions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.reactivex.rxjava3.core.Single
import org.schabi.newpipe.R
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.fragments.list.BaseListInfoFragment

/** "Suggestions" tab: videos similar to what the user watched, computed on the device. */
class SuggestionsFragment :
    BaseListInfoFragment<InfoItem, SuggestionsInfo>(UserAction.REQUESTED_FEED) {

    init {
        setInitialData(ServiceList.YouTube.serviceId, SuggestionsInfo.URL, "")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_kiosk, container, false)

    override fun loadResult(forceLoad: Boolean): Single<SuggestionsInfo> = SuggestionsLoader.load(requireContext().applicationContext, forceLoad)

    override fun loadMoreItemsLogic(): Single<InfoItemsPage<InfoItem>> {
        // read on the main thread, before the work moves to a background one
        val shown = infoListAdapter.itemsList.filterIsInstance<StreamInfoItem>()
        return SuggestionsLoader.loadMore(requireContext().applicationContext, shown)
    }

    override fun showEmptyState() {
        super.showEmptyState()
        setEmptyStateMessage(R.string.suggestions_empty)
    }
}
