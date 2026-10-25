package org.schabi.newpipe.local.background

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.rxjava3.RxWorker
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.App
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.local.feed.service.FeedLoadManager
import org.schabi.newpipe.local.suggestions.SuggestionsLoader
import org.schabi.newpipe.tunnel.TunnelManager

/**
 * Periodically, in the background: checks subscribed channels for new videos (the feed) and
 * recomputes the suggestions, so both are ready when the app is opened.
 * Everything goes through the privacy tunnel; the work waits for it to connect.
 */
class BackgroundRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : RxWorker(appContext, workerParams) {

    override fun createWork(): Single<Result> {
        if (BotBlockGuard.isPaused(applicationContext)) {
            // YouTube flagged this IP recently: let it cool down instead of insisting
            return Single.just(Result.success())
        }
        return waitForTunnel()
            .andThen(
                Completable.defer {
                    FeedLoadManager(applicationContext)
                        .startLoading(FeedGroupEntity.GROUP_ALL_ID, ignoreOutdatedThreshold = false)
                        .doOnSuccess { results ->
                            if (results.any { BotBlockGuard.isBotBlock(it.error) }) {
                                BotBlockGuard.record(applicationContext)
                            }
                        }
                        .ignoreElement()
                }
                    // a failing feed must not prevent the suggestions from being refreshed
                    .doOnError { Log.w(TAG, "Background feed update failed", it) }
                    .onErrorComplete()
            )
            .andThen(Completable.defer { refreshSuggestionsIfNeeded() })
            .toSingleDefault(Result.success())
            // tunnel not up in time (no network, VPN needing setup...): try again next period
            .onErrorReturnItem(Result.success())
    }

    /** Recomputes the suggestions only when something was watched since the last time. */
    private fun refreshSuggestionsIfNeeded(): Completable {
        val context = applicationContext
        if (BotBlockGuard.isPaused(context)) {
            return Completable.complete()
        }
        val lastWatched = NewPipeDatabase.getInstance(context).streamHistoryDAO().history
            .blockingFirst().firstOrNull()?.accessDate?.toString().orEmpty()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (lastWatched == prefs.getString(LAST_SEEN_HISTORY_KEY, null)) {
            return Completable.complete()
        }
        return SuggestionsLoader.refresh(context, false)
            .doOnSuccess { prefs.edit { putString(LAST_SEEN_HISTORY_KEY, lastWatched) } }
            .ignoreElement()
            .doOnError { Log.w(TAG, "Background suggestions update failed", it) }
            .onErrorComplete()
    }

    private fun waitForTunnel(): Completable = TunnelManager.status
        .filter { it.isConnected }
        .firstOrError()
        .timeout(TUNNEL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .ignoreElement()

    companion object {
        private val TAG = BackgroundRefreshWorker::class.java.simpleName
        private const val WORK_NAME = App.PACKAGE_NAME + "_background_refresh"
        private const val TUNNEL_TIMEOUT_MINUTES = 3L
        private const val LAST_SEEN_HISTORY_KEY = "tubetube_suggestions_history_marker"

        /** Schedules (or cancels) the work from the current settings, keeping a running one. */
        @JvmStatic
        fun schedule(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val hours = prefs.getString(
                context.getString(R.string.background_refresh_interval_key),
                context.getString(R.string.background_refresh_interval_default)
            )?.toLongOrNull() ?: 0L
            val wifiOnly = prefs.getBoolean(
                context.getString(R.string.background_refresh_wifi_only_key),
                false
            )
            schedule(context, hours, wifiOnly, false)
        }

        /** @param hours 0 disables the background refresh */
        @JvmStatic
        fun schedule(context: Context, hours: Long, wifiOnly: Boolean, replace: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (hours <= 0) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequest.Builder(
                BackgroundRefreshWorker::class.java,
                hours,
                TimeUnit.HOURS
            ).setConstraints(constraints).build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
