package org.schabi.newpipe.player

import android.os.SystemClock
import com.google.android.exoplayer2.upstream.HttpDataSource

/**
 * YouTube stream URLs only work from the IP address that loaded them. When that address changes
 * mid-playback (the Tor circuit rotated to a new exit, Wi-Fi to mobile data...), the next chunk
 * request gets HTTP 403. The fix is to load the video page again and resume where it stopped;
 * this decides when to do that, a couple of times per video, so a real 403 still surfaces.
 */
class StaleStreamRecovery @JvmOverloads constructor(
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    private var lastUrl: String? = null
    private var attempts = 0
    private var windowStart = 0L

    fun shouldRefresh(error: Throwable, streamUrl: String?): Boolean {
        if (streamUrl == null || !isForbidden(error)) {
            return false
        }
        val now = clock()
        if (streamUrl != lastUrl || now - windowStart > WINDOW_MS) {
            lastUrl = streamUrl
            attempts = 0
            windowStart = now
        }
        if (attempts >= MAX_ATTEMPTS) {
            return false
        }
        attempts++
        return true
    }

    companion object {
        const val MAX_ATTEMPTS = 2
        const val WINDOW_MS = 60_000L
        private const val FORBIDDEN = 403

        fun isForbidden(error: Throwable): Boolean = generateSequence(error) { it.cause }
            .take(10)
            .any { it is HttpDataSource.InvalidResponseCodeException && it.responseCode == FORBIDDEN }
    }
}
