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
     * Shows the cached suggestions (minus videos watched since) when fresh enough, otherwise
     * computes new ones. [forceLoad] (pull to refresh) always computes.
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

    /** Computes new suggestions and caches them; also used by the background refresh. */
    fun refresh(context: Context, forceLoad: Boolean): Single<SuggestionsInfo> = Single.fromCallable { localData(context) }
        .flatMap { data ->
            val seeds = initialSeeds(data)
            resetUsedSeeds(seeds)
            if (seeds.isEmpty()) {
                return@flatMap Single.just(SuggestionsInfo(emptyList()))
            }
            relatedOf(seeds, forceLoad).map { related ->
                val excluded = data.watchedUrls + seeds.map { it.url }
                SuggestionsInfo(SuggestionRanker.rank(related, excluded, data.subscribedChannels))
            }
        }
        .doOnSuccess { info ->
            if (info.relatedItems.isNotEmpty()) {
                SuggestionsCache.write(context, info.relatedItems.filterIsInstance<StreamInfoItem>())
            }
        }

    /**
     * Next page for infinite scrolling.
     * @param shown the items already in the list, in display order
     */
    fun loadMore(context: Context, shown: List<StreamInfoItem>): Single<InfoItemsPage<InfoItem>> = Single.fromCallable { localData(context) }.flatMap { data ->
        val shownUrls = shown.mapNotNull { it.url }.toHashSet()
        val candidates = (
            data.history + data.unwatchedFeed +
                shown.filter { it.serviceId == ServiceList.YouTube.serviceId && it.url != null }
                    .map { Seed(it.serviceId, it.url) }
            )
            .distinctBy { it.url }
            .filter { it.url !in usedSeeds }
        nextBatch(data, candidates, shownUrls, 0)
    }

    private fun nextBatch(
        data: LocalData,
        candidates: List<Seed>,
        shownUrls: Set<String>,
        batch: Int
    ): Single<InfoItemsPage<InfoItem>> {
        val seeds = candidates.take(SEEDS_PER_PAGE)
        if (seeds.isEmpty()) {
            // nothing left to start from: end of the list
            return Single.just(InfoItemsPage(emptyList(), null, emptyList()))
        }
        usedSeeds += seeds.map { it.url }
        val rest = candidates.drop(seeds.size)
        return relatedOf(seeds, false).flatMap { related ->
            val excluded = data.watchedUrls + shownUrls + seeds.map { it.url }
            val items = SuggestionRanker.rank(related, excluded, data.subscribedChannels, ITEMS_PER_PAGE)
            if (items.isEmpty() && rest.isNotEmpty() && batch + 1 < MAX_BATCHES_PER_PAGE) {
                // these seeds only had videos already shown: try the next ones
                nextBatch(data, rest, shownUrls, batch + 1)
            } else {
                val next = if (rest.isEmpty() && items.isEmpty()) null else SuggestionsInfo.NEXT_PAGE
                Single.just(InfoItemsPage<InfoItem>(ArrayList<InfoItem>(items), next, emptyList()))
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
