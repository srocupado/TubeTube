package org.schabi.newpipe.sponsorblock

import android.content.Context
import android.widget.Toast
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.SerialDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Skips promotional parts (sponsors, self-promotion, "subscribe!" reminders...) of YouTube videos
 * using SponsorBlock. Owned by the player; everything runs on the main thread.
 */
class SponsorBlockSkipper(
    private val context: Context,
    private val playback: Playback
) {
    /** What the skipper needs from the player. */
    interface Playback {
        /** Current position in milliseconds, or a negative value if unknown. */
        fun currentPositionMillis(): Long

        fun seekTo(positionMillis: Long)
    }

    private val fetchDisposable = SerialDisposable()
    private val tickerDisposable = SerialDisposable()

    private var videoId: String? = null
    private var segments: List<Segment> = emptyList()
    private val skippedKeys = HashSet<String>()

    /** Called whenever the player switches to (or refreshes) a stream. */
    fun onStream(info: StreamInfo) {
        if (info.serviceId != ServiceList.YouTube.serviceId || !isEnabled()) {
            reset()
            return
        }
        if (info.id == videoId) {
            return // same video (e.g. quality change): keep segments and skipped state
        }
        reset()
        val id = info.id
        videoId = id
        val categories = selectedCategories()
        fetchDisposable.set(
            Single.fromCallable { SponsorBlock.fetchSegments(id, categories) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ result -> onSegments(id, result) }, { /* no segments then */ })
        )
    }

    fun release() {
        reset()
    }

    private fun onSegments(id: String, result: VideoSegments) {
        if (id != videoId) {
            return // the user already moved to another video
        }
        if (result.fullVideoCategory != null && shouldNotify()) {
            Toast.makeText(context, R.string.sponsorblock_full_video_toast, Toast.LENGTH_LONG)
                .show()
        }
        segments = result.skip
        if (segments.isNotEmpty()) {
            // checked often so that not even the first second of an ad is shown
            tickerDisposable.set(
                Observable.interval(CHECK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ checkPosition() }, { })
            )
        }
    }

    private fun checkPosition() {
        val position = playback.currentPositionMillis()
        if (position < 0) {
            return
        }
        val segment = segments.firstOrNull {
            position >= it.startMs && position < it.endMs - END_MARGIN_MILLIS &&
                it.key !in skippedKeys
        } ?: return
        // skipped once: if the user deliberately seeks back into it, it plays
        skippedKeys.add(segment.key)
        playback.seekTo(segment.endMs)
        if (shouldNotify()) {
            Toast.makeText(context, R.string.sponsorblock_skipped_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun reset() {
        fetchDisposable.set(null)
        tickerDisposable.set(null)
        videoId = null
        segments = emptyList()
        skippedKeys.clear()
    }

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(context)

    private fun isEnabled() = prefs().getBoolean(context.getString(R.string.sponsorblock_enabled_key), true)

    private fun shouldNotify() = prefs().getBoolean(context.getString(R.string.sponsorblock_notify_key), true)

    private fun selectedCategories(): Set<String> = prefs().getStringSet(
        context.getString(R.string.sponsorblock_categories_key),
        null
    ) ?: context.resources.getStringArray(R.array.sponsorblock_categories_default).toSet()

    companion object {
        private const val CHECK_INTERVAL_MILLIS = 200L

        /** Do not "skip" the last instant of a segment: it would just seek a few ms ahead. */
        private const val END_MARGIN_MILLIS = 300L
    }
}
