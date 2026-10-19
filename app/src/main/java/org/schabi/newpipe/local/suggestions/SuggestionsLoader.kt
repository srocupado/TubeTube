package org.schabi.newpipe.local.suggestions

import android.content.Context
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import java.util.Collections
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.util.ExtractorHelper

/**
 * Builds the suggestion list on the device: seeds are the videos watched last (local history)
 * and, while the history is short, recent videos of subscribed channels; suggestions are the
 * "similar videos" of those seeds, ranked by [SuggestionRanker].
 *
 * Scrolling down loads more: the next seeds are older history videos, then unwatched videos of
 * subscribed channels, then the suggestions already shown (similar videos of similar videos),
 * so the list keeps going.
 *
 * Nothing about the user is sent anywhere: the only requests are the same video pages the app
 * loads anyway, through the privacy tunnel.
 */
object SuggestionsLoader {
    private const val MAX_SEEDS = 8
    private const val MAX_FEED_SEEDS = 4
    private const val SEEDS_PER_PAGE = 4
    private const val ITEMS_PER_PAGE = 30
    private const val MAX_BATCHES_PER_PAGE = 3
    private const val MIN_NEW_ON_REFRESH = 10
    private const val MAX_LIST_SIZE = 200
    private const val CONCURRENT_REQUESTS = 4

    /** Cached suggestions older than this are recomputed when the tab opens. */
    private const val MAX_CACHE_AGE_MILLIS = 12 * 60 * 60 * 1000L

    private data class Seed(val serviceId: Int, val url: String)

    private data class SeedResult(val related: List<StreamInfoItem>, val error: Throwable?)

    private class LocalData(
        val history: List<Seed>,
        val watchedUrls: Set<String>,
        val subscribedChannels: Set<String>,
        val unwatchedFeed: List<Seed>
    )

    /** Seeds already used in the current list, so "load more" picks new ones. */
    private val usedSeeds: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * Shows the cached list (minus videos watched since) when fresh enough, otherwise refreshes
     * it. [forceLoad] (pull to refresh) always refreshes.
     */
    fun load(context: Context, forceLoad: Boolean): Single<SuggestionsInfo> = Single.defer {
        if (!forceLoad) {
            val cached = SuggestionsCache.read(context)
            if (cached != null &&
                System.currentTimeMillis() - cached.savedAtMillis < MAX_CACHE_AGE_MILLIS
            ) {
                val data = localData(context)
                val fresh = cached.items.filter { it.url !in data.watchedUrls }
                if (fresh.isNotEmpty()) {
                    // the cached list came from these seeds
                    resetUsedSeeds(initialSeeds(data))
                    return@defer Single.just(SuggestionsInfo(fresh))
                }
            }
        }
        refresh(context, forceLoad)
    }

    /**
     * Computes new suggestions and puts them at the top, above the ones already in the list
     * (kept below, minus watched videos); caches the result. Used when the list is (re)built and
     * by the background refresh.
     */
    fun refresh(context: Context, forceLoad: Boolean): Single<SuggestionsInfo> = Single.fromCallable { localData(context) }
        .flatMap { data ->
            val previous = SuggestionsCache.read(context)?.items.orEmpty()
                .filter { it.url !in data.watchedUrls }
            computeNew(data, previous, forceLoad)
                .map { fresh -> SuggestionsInfo((fresh + previous).take(MAX_LIST_SIZE)) }
        }
        .doOnSuccess { info ->
            if (info.relatedItems.isNotEmpty()) {
                SuggestionsCache.write(context, info.relatedItems.filterIsInstance<StreamInfoItem>())
            }
        }

    /**
     * Pull to refresh: suggestions not in [shown] yet, to be stacked above it.
     * The cache is updated with the combined list.
     */
    fun newOnTop(context: Context, shown: List<StreamInfoItem>): Single<List<StreamInfoItem>> = Single.fromCallable { localData(context) }
        .flatMap { data -> computeNew(data, shown, true) }
        .doOnSuccess { fresh ->
            if (fresh.isNotEmpty()) {
                SuggestionsCache.write(context, (fresh + shown).take(MAX_LIST_SIZE))
            }
        }

    /** New suggestions from the latest seeds, leaving out everything in [listed]. */
    private fun computeNew(
        data: LocalData,
        listed: List<StreamInfoItem>,
        forceLoad: Boolean
    ): Single<List<StreamInfoItem>> {
        val listedUrls = listed.mapNotNull { it.url }.toHashSet()
        val seeds = initialSeeds(data)
        resetUsedSeeds(seeds)
        if (seeds.isEmpty()) {
            return Single.just(emptyList())
        }
        val wanted = if (listed.isEmpty()) SuggestionRanker.DEFAULT_LIMIT else ITEMS_PER_PAGE
        return relatedOf(seeds, forceLoad).flatMap { related ->
            val excluded = data.watchedUrls + listedUrls + seeds.map { it.url }
            val fresh = SuggestionRanker.rank(related, excluded, data.subscribedChannels, wanted)
            if (fresh.size >= MIN_NEW_ON_REFRESH || listed.isEmpty()) {
                Single.just(fresh)
            } else {
                // the latest videos only gave suggestions already listed: start from others
                val shownUrls = listedUrls + fresh.mapNotNull { it.url }
                collect(data, candidates(data, listed), shownUrls, wanted - fresh.size, 0, forceLoad)
                    .map { (more, _) -> fresh + more }
                    .onErrorReturnItem(fresh)
            }
        }
    }

    /**
     * Next page for infinite scrolling, appended at the bottom.
     * @param shown the items already in the list, in display order
     */
    fun loadMore(context: Context, shown: List<StreamInfoItem>): Single<InfoItemsPage<InfoItem>> = Single.fromCallable { localData(context) }.flatMap { data ->
        val shownUrls = shown.mapNotNull { it.url }.toHashSet()
        collect(data, candidates(data, shown), shownUrls, ITEMS_PER_PAGE, 0, false)
            .doOnSuccess { (items, _) ->
                if (items.isNotEmpty()) {
                    // the list keeps what was scrolled through, the next opening shows it too
                    SuggestionsCache.write(context, (shown + items).take(MAX_LIST_SIZE))
                }
            }
            .map { (items, hasMore) ->
                InfoItemsPage<InfoItem>(
                    ArrayList<InfoItem>(items),
                    if (hasMore) SuggestionsInfo.NEXT_PAGE else null,
                    emptyList()
                )
            }
    }

    /** Seeds not used yet: older history, unwatched subscription videos, then [shown] items. */
    private fun candidates(data: LocalData, shown: List<StreamInfoItem>): List<Seed> = (
        data.history + data.unwatchedFeed +
            shown.filter { it.serviceId == ServiceList.YouTube.serviceId && it.url != null }
                .map { Seed(it.serviceId, it.url) }
        )
        .distinctBy { it.url }
        .filter { it.url !in usedSeeds }

    /** @return up to [wanted] new items, and whether more could still be computed */
    private fun collect(
        data: LocalData,
        candidates: List<Seed>,
        shownUrls: Set<String>,
        wanted: Int,
        batch: Int,
        forceLoad: Boolean
    ): Single<Pair<List<StreamInfoItem>, Boolean>> {
        val seeds = candidates.take(SEEDS_PER_PAGE)
        if (seeds.isEmpty()) {
            // nothing left to start from: end of the list
            return Single.just(Pair(emptyList(), false))
        }
        usedSeeds += seeds.map { it.url }
        val rest = candidates.drop(seeds.size)
        return relatedOf(seeds, forceLoad).flatMap { related ->
            val excluded = data.watchedUrls + shownUrls + seeds.map { it.url }
            val items = SuggestionRanker.rank(related, excluded, data.subscribedChannels, wanted)
            if (items.isEmpty() && rest.isNotEmpty() && batch + 1 < MAX_BATCHES_PER_PAGE) {
                // these seeds only had videos already shown: try the next ones
                collect(data, rest, shownUrls, wanted, batch + 1, forceLoad)
            } else {
                Single.just(Pair(items, rest.isNotEmpty() || items.isNotEmpty()))
            }
        }
    }

    private fun resetUsedSeeds(seeds: List<Seed>) {
        synchronized(usedSeeds) {
            usedSeeds.clear()
            usedSeeds += seeds.map { it.url }
        }
    }

    private fun initialSeeds(data: LocalData): List<Seed> {
        val seeds = data.history.take(MAX_SEEDS).toMutableList()
        if (seeds.size < MAX_SEEDS) {
            seeds += data.unwatchedFeed.take(minOf(MAX_FEED_SEEDS, MAX_SEEDS - seeds.size))
        }
        return seeds
    }

    private fun localData(context: Context): LocalData {
        val db = NewPipeDatabase.getInstance(context)
        val youtube = ServiceList.YouTube.serviceId

        val history = db.streamHistoryDAO().history.blockingFirst()
            .map { it.streamEntity }
            .filter { it.serviceId == youtube }
            .distinctBy { it.url }
        val watchedUrls = history.map { it.url }.toHashSet()

        val subscribedChannels = db.subscriptionDAO().getAll().blockingFirst()
            .mapNotNull { it.url }
            .toHashSet()

        val unwatchedFeed = FeedDatabaseManager(context)
            .getStreams(FeedGroupEntity.GROUP_ALL_ID, false, false, false)
            .blockingGet()
            .orEmpty()
            .map { it.stream }
            .filter { it.serviceId == youtube && it.url !in watchedUrls }
            .map { Seed(it.serviceId, it.url) }

        return LocalData(
            history.map { Seed(it.serviceId, it.url) },
            watchedUrls,
            subscribedChannels,
            unwatchedFeed
        )
    }

    /** Related items of each seed, in seed order; fails only if every seed failed. */
    private fun relatedOf(seeds: List<Seed>, forceLoad: Boolean): Single<List<List<StreamInfoItem>>> = Flowable.fromIterable(seeds)
        // keeps the seed order (most recent first) while loading a few pages at a time
        .concatMapEager({ seed -> relatedOf(seed, forceLoad).toFlowable() }, CONCURRENT_REQUESTS, 1)
        .toList()
        .map { results ->
            val related = results.filter { it.error == null }.map { it.related }
            if (related.isEmpty()) {
                // every page failed (offline, tunnel down...): show the error, not "empty"
                throw results.first().error!!
            }
            related
        }

    private fun relatedOf(seed: Seed, forceLoad: Boolean): Single<SeedResult> = ExtractorHelper.getStreamInfo(seed.serviceId, seed.url, forceLoad)
        .map { info -> SeedResult(info.relatedItems.filterIsInstance<StreamInfoItem>(), null) }
        .onErrorReturn { SeedResult(emptyList(), it) }
}
