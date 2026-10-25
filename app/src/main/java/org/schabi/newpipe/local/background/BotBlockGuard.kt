package org.schabi.newpipe.local.background

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

/**
 * When YouTube answers "sign in to confirm you're not a bot", the current IP is flagged.
 * Automatic work (background refresh) then pauses for a while instead of insisting from the same
 * IP, which would only make the block last longer. Nothing is retried or worked around.
 */
object BotBlockGuard {
    private const val KEY = "tubetube_bot_block_until"
    private const val PAUSE_MILLIS = 6 * 60 * 60 * 1000L

    fun isBotBlock(error: Throwable?): Boolean = generateSequence(error) { it.cause }
        .take(10)
        .any { it is SignInConfirmNotBotException }

    fun record(context: Context, now: Long = System.currentTimeMillis()) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putLong(KEY, now + PAUSE_MILLIS)
        }
    }

    fun isPaused(context: Context, now: Long = System.currentTimeMillis()): Boolean = PreferenceManager.getDefaultSharedPreferences(context).getLong(KEY, 0L) > now
}
