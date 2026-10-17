package org.schabi.newpipe.player

import android.net.Uri
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class StaleStreamRecoveryTest {
    private var now = 0L
    private val recovery = StaleStreamRecovery { now }
    private val url = "https://www.youtube.com/watch?v=WLWJy1eXX2c"

    private fun httpError(code: Int) = IOException(
        "Source error",
        InvalidResponseCodeException(code, null, null, emptyMap(), DataSpec(mock(Uri::class.java)), ByteArray(0))
    )

    @Test
    fun refreshesOn403UpToTheLimit() {
        repeat(StaleStreamRecovery.MAX_ATTEMPTS) { assertTrue(recovery.shouldRefresh(httpError(403), url)) }
        assertFalse(recovery.shouldRefresh(httpError(403), url))
    }

    @Test
    fun budgetResetsAfterTheWindowOrForAnotherVideo() {
        repeat(StaleStreamRecovery.MAX_ATTEMPTS) { recovery.shouldRefresh(httpError(403), url) }
        assertTrue(recovery.shouldRefresh(httpError(403), "https://www.youtube.com/watch?v=other000000"))
        now += StaleStreamRecovery.WINDOW_MS + 1
        assertTrue(recovery.shouldRefresh(httpError(403), url))
    }

    @Test
    fun otherErrorsAreLeftToThePlayer() {
        assertFalse(recovery.shouldRefresh(httpError(404), url))
        assertFalse(recovery.shouldRefresh(IOException("timeout"), url))
        assertFalse(recovery.shouldRefresh(httpError(403), null))
    }
}
