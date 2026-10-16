package org.schabi.newpipe.local.suggestions

import android.content.Context
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.util.ExtractorHelper

/**
 * Builds the suggestion list on the device: seeds are the videos watched last (local history)
 * and, while the history is short, recent videos of subscribed channels; suggestions are the
 * "similar videos" of those seeds, ranked by [SuggestionRanker].
 *
 * Nothing about the user is sent anywhere: the only requests are the same video pages the app
 * loads anyway, through the privacy tunnel.
 */
object SuggestionsLoader {
    private const val MAX_SEEDS = 10
    private const val MAX_FEED_SEEDS = 4
    private const val CONCURRENT_REQUESTS = 3

    private data class Seed(val serviceId: Int, val url: String)

    private data class SeedResult(val related: List<StreamInfoItem>, val error: Throwable?)

    fun load(context: Context, forceLoad: Boolean): Single<SuggestionsInfo> = Single.fromCallable {
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

        val seeds = history.take(MAX_SEEDS).map { Seed(it.serviceId, it.url) }.toMutableList()
        if (seeds.size < MAX_SEEDS) {
            val feed = FeedDatabaseManager(context)
                .getStreams(FeedGroupEntity.GROUP_ALL_ID, false, false, false)
                .blockingGet()
                .orEmpty()
                .map { it.stream }
                .filter { it.serviceId == youtube && it.url !in watchedUrls }
            seeds += feed.take(minOf(MAX_FEED_SEEDS, MAX_SEEDS - seeds.size))
                .map { Seed(it.serviceId, it.url) }
        }
        Triple(seeds, watchedUrls, subscribedChannels)
    }.flatMap { (seeds, watchedUrls, subscribedChannels) ->
        if (seeds.isEmpty()) {
            return@flatMap Single.just(SuggestionsInfo(emptyList()))
        }
        Flowable.fromIterable(seeds)
            // keeps the seed order (most recent first) while loading a few pages at a time
            .concatMapEager({ seed -> relatedOf(seed, forceLoad).toFlowable() }, CONCURRENT_REQUESTS, 1)
            .toList()
            .map { results ->
                val related = results.filter { it.error == null }.map { it.related }
                if (related.isEmpty()) {
                    // every page failed (offline, tunnel down...): show the error, not "empty"
                    throw results.first().error!!
                }
                val excluded = watchedUrls + seeds.map { it.url }
                SuggestionsInfo(SuggestionRanker.rank(related, excluded, subscribedChannels))
            }
    }

    private fun relatedOf(seed: Seed, forceLoad: Boolean): Single<SeedResult> = ExtractorHelper.getStreamInfo(seed.serviceId, seed.url, forceLoad)
        .map { info -> SeedResult(info.relatedItems.filterIsInstance<StreamInfoItem>(), null) }
        .onErrorReturn { SeedResult(emptyList(), it) }
}
