package org.schabi.newpipe.local.suggestions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.fragments.list.BaseListInfoFragment
import org.schabi.newpipe.local.background.BotBlockGuard

/**
 * "Suggestions" tab: videos similar to what the user watched, computed on the device.
 * Pulling down at the top stacks new suggestions above the current ones; reaching the end
 * loads more below.
 */
class SuggestionsFragment :
    BaseListInfoFragment<InfoItem, SuggestionsInfo>(UserAction.REQUESTED_FEED) {

    private var swipeRefresh: SwipeRefreshLayout? = null
    private var newOnTopWorker: Disposable? = null

    init {
        setInitialData(ServiceList.YouTube.serviceId, SuggestionsInfo.URL, "")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_suggestions, container, false)

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        swipeRefresh = rootView.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh_layout).apply {
            setOnRefreshListener { loadNewOnTop() }
        }
    }

    override fun onDestroyView() {
        newOnTopWorker?.dispose()
        swipeRefresh = null
        super.onDestroyView()
    }

    override fun loadResult(forceLoad: Boolean): Single<SuggestionsInfo> = SuggestionsLoader.load(requireContext().applicationContext, forceLoad)

    override fun loadMoreItemsLogic(): Single<InfoItemsPage<InfoItem>> {
        // read on the main thread, before the work moves to a background one
        val shown = infoListAdapter.itemsList.filterIsInstance<StreamInfoItem>()
        return SuggestionsLoader.loadMore(requireContext().applicationContext, shown)
    }

    private fun loadNewOnTop() {
        val shown = infoListAdapter.itemsList.filterIsInstance<StreamInfoItem>()
        if (shown.isEmpty() || isLoading.get()) {
            // nothing to stack on (first load or error): plain reload
            swipeRefresh?.isRefreshing = false
            if (!isLoading.get()) {
                reloadContent()
            }
            return
        }
        newOnTopWorker?.dispose()
        val context = requireContext().applicationContext
        newOnTopWorker = SuggestionsLoader.newOnTop(context, shown)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { fresh ->
                    swipeRefresh?.isRefreshing = false
                    if (fresh.isEmpty()) {
                        Toast.makeText(context, R.string.suggestions_nothing_new, Toast.LENGTH_SHORT).show()
                    } else {
                        val current = ArrayList(infoListAdapter.itemsList)
                        infoListAdapter.clearStreamItemList()
                        infoListAdapter.addInfoItemList(fresh + current)
                        itemsList.scrollToPosition(0)
                    }
                },
                { error ->
                    swipeRefresh?.isRefreshing = false
                    if (BotBlockGuard.isBotBlock(error)) {
                        // not a bug: YouTube flagged the current IP for a while
                        Toast.makeText(context, R.string.suggestions_bot_block, Toast.LENGTH_LONG).show()
                    } else {
                        showSnackBarError(ErrorInfo(error, UserAction.REQUESTED_FEED, "Loading new suggestions"))
                    }
                }
            )
    }

    override fun showEmptyState() {
        super.showEmptyState()
        setEmptyStateMessage(R.string.suggestions_empty)
    }
}
