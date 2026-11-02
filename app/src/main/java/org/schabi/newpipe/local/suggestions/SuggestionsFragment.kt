package org.schabi.newpipe.local.suggestions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.fragments.list.BaseListInfoFragment
import org.schabi.newpipe.local.background.BotBlockGuard
import org.schabi.newpipe.local.filter.VideoFacts
import org.schabi.newpipe.local.filter.VideoFilter
import org.schabi.newpipe.local.filter.VideoFilterDialog
import org.schabi.newpipe.local.filter.VideoFilterStore

/**
 * "Suggestions" tab: videos similar to what the user watched, computed on the device.
 * Pulling down at the top stacks new suggestions above the current ones; reaching the end
 * loads more below. The filter menu hides and orders what is shown, on the device.
 */
class SuggestionsFragment :
    BaseListInfoFragment<InfoItem, SuggestionsInfo>(UserAction.REQUESTED_FEED) {

    private var swipeRefresh: SwipeRefreshLayout? = null
    private var newOnTopWorker: Disposable? = null
    private val disposables = CompositeDisposable()

    /** Everything loaded, before filtering: what the loader and the cache work with. */
    private val rawItems = ArrayList<StreamInfoItem>()
    private var filter = VideoFilter()
    private var subscribedChannels: Set<String> = emptySet()

    /** "Load more" pages that only had filtered-out videos, loaded in a row without scrolling. */
    private var emptyPagesInARow = 0

    init {
        setInitialData(ServiceList.YouTube.serviceId, SuggestionsInfo.URL, "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filter = VideoFilterStore.SUGGESTIONS.load(requireContext())
        disposables.add(
            NewPipeDatabase.getInstance(requireContext()).subscriptionDAO().getAll()
                .map { list -> list.mapNotNull { it.url }.toHashSet() }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { channels ->
                    subscribedChannels = channels
                    if (filter.onlySubscribed && view != null) {
                        rerender()
                    }
                }
        )
    }

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
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

    // ---- menu ----

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_suggestions_fragment, menu)
        updateFilterTitle(menu.findItem(R.id.menu_item_filter))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_item_filter) {
            VideoFilterDialog.show(requireContext(), filter, showOnlySubscribed = true) { newFilter ->
                filter = newFilter
                VideoFilterStore.SUGGESTIONS.save(requireContext(), newFilter)
                activity?.invalidateOptionsMenu()
                emptyPagesInARow = 0
                rerender()
                itemsList.scrollToPosition(0)
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun updateFilterTitle(item: MenuItem?) {
        val count = filter.activeCount
        item?.title = getString(R.string.filter_menu) + if (count > 0) " ($count)" else ""
        item?.icon?.alpha = if (count > 0) 255 else 160
    }

    // ---- loading ----

    override fun loadResult(forceLoad: Boolean): Single<SuggestionsInfo> = SuggestionsLoader.load(requireContext().applicationContext, forceLoad)

    override fun loadMoreItemsLogic(): Single<InfoItemsPage<InfoItem>> {
        // copied on the main thread, before the work moves to a background one
        val shown = ArrayList(rawItems)
        return SuggestionsLoader.loadMore(requireContext().applicationContext, shown)
    }

    override fun handleResult(result: SuggestionsInfo) {
        rawItems.clear()
        rawItems += result.relatedItems.filterIsInstance<StreamInfoItem>()
        emptyPagesInARow = 0
        val visible = visible(rawItems)
        if (visible.isEmpty() && rawItems.isNotEmpty()) {
            // everything filtered out: say so instead of loading more on its own
            val nextPage = currentNextPage
            currentNextPage = null
            super.handleResult(result.withItems(emptyList(), keepNextPage = false))
            currentNextPage = nextPage
            showFilteredOutState()
        } else {
            super.handleResult(result.withItems(visible, keepNextPage = true))
        }
    }

    override fun handleNextItems(result: InfoItemsPage<InfoItem>) {
        val newItems = result.items.filterIsInstance<StreamInfoItem>()
        rawItems += newItems
        if (filter.sort == VideoFilter.Sort.DEFAULT) {
            val visibleNew = visible(newItems)
            super.handleNextItems(InfoItemsPage(ArrayList<InfoItem>(visibleNew), result.nextPage, result.errors))
            if (visibleNew.isNotEmpty()) {
                emptyPagesInARow = 0
            } else if (hasMoreItems() && ++emptyPagesInARow < MAX_EMPTY_PAGES_IN_A_ROW) {
                // nothing new passed the filter, so the list did not grow: fetch the next page
                loadMoreItems()
            }
        } else {
            // sorted lists are shown whole again, the new videos take their place in the order
            super.handleNextItems(InfoItemsPage(emptyList(), result.nextPage, result.errors))
            rerender()
        }
    }

    private fun loadNewOnTop() {
        if (rawItems.isEmpty() || isLoading.get()) {
            // nothing to stack on (first load or error): plain reload
            swipeRefresh?.isRefreshing = false
            if (!isLoading.get()) {
                reloadContent()
            }
            return
        }
        newOnTopWorker?.dispose()
        val context = requireContext().applicationContext
        newOnTopWorker = SuggestionsLoader.newOnTop(context, ArrayList(rawItems))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { fresh ->
                    swipeRefresh?.isRefreshing = false
                    if (fresh.isEmpty()) {
                        Toast.makeText(context, R.string.suggestions_nothing_new, Toast.LENGTH_SHORT).show()
                    } else {
                        rawItems.addAll(0, fresh)
                        rerender()
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

    // ---- filtering ----

    private fun visible(items: List<StreamInfoItem>): List<StreamInfoItem> = filter.apply(items, subscribedChannels) { VideoFacts.of(it) }

    /** Shows [rawItems] again through the current filter. */
    private fun rerender() {
        if (view == null || isLoading.get() && rawItems.isEmpty()) {
            return
        }
        val visible = visible(rawItems)
        infoListAdapter.clearStreamItemList()
        if (visible.isEmpty() && rawItems.isNotEmpty()) {
            showFilteredOutState()
        } else if (visible.isNotEmpty()) {
            infoListAdapter.addInfoItemList(visible)
            hideLoading()
            showListFooter(hasMoreItems())
        }
    }

    private fun showFilteredOutState() {
        showEmptyState()
        setEmptyStateMessage(R.string.filter_no_results)
    }

    override fun showEmptyState() {
        super.showEmptyState()
        setEmptyStateMessage(R.string.suggestions_empty)
    }

    private fun SuggestionsInfo.withItems(items: List<StreamInfoItem>, keepNextPage: Boolean): SuggestionsInfo {
        val copy = SuggestionsInfo(items)
        copy.nextPage = if (keepNextPage) nextPage else null
        return copy
    }

    companion object {
        private const val MAX_EMPTY_PAGES_IN_A_ROW = 3
    }
}
