package org.schabi.newpipe.local.filter

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/** Remembers the filter of each list (Suggestions, Subscriptions) on the device. */
enum class VideoFilterStore(private val key: String) {
    SUGGESTIONS("tubetube_filter_suggestions"),
    FEED("tubetube_filter_feed");

    fun load(context: Context): VideoFilter = VideoFilter.decode(PreferenceManager.getDefaultSharedPreferences(context).getString(key, null))

    fun save(context: Context, filter: VideoFilter) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            if (filter.isDefault) remove(key) else putString(key, filter.encode())
        }
    }
}
