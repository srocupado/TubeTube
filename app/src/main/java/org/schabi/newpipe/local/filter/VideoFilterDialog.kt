package org.schabi.newpipe.local.filter

import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import org.schabi.newpipe.R

/** Dialog to edit a [VideoFilter]; [onApply] gets the new filter (the default one to clear). */
object VideoFilterDialog {
    fun show(
        context: Context,
        current: VideoFilter,
        showOnlySubscribed: Boolean,
        onApply: (VideoFilter) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_video_filter, null)
        val period = view.findViewById<Spinner>(R.id.filter_period)
        val length = view.findViewById<Spinner>(R.id.filter_length)
        val sort = view.findViewById<Spinner>(R.id.filter_sort)
        val hideShorts = view.findViewById<SwitchCompat>(R.id.filter_hide_shorts)
        val hideLive = view.findViewById<SwitchCompat>(R.id.filter_hide_live)
        val onlySubscribed = view.findViewById<SwitchCompat>(R.id.filter_only_subscribed)
        val words = view.findViewById<EditText>(R.id.filter_hidden_words)

        period.setUp(context, R.array.filter_period_entries, current.period.ordinal)
        length.setUp(context, R.array.filter_length_entries, current.length.ordinal)
        sort.setUp(
            context,
            if (showOnlySubscribed) R.array.filter_sort_entries_suggestions else R.array.filter_sort_entries_feed,
            current.sort.ordinal
        )
        hideShorts.isChecked = current.hideShorts
        hideLive.isChecked = current.hideLive
        onlySubscribed.isVisible = showOnlySubscribed
        onlySubscribed.isChecked = current.onlySubscribed
        words.setText(current.hiddenWords.joinToString(", "))

        AlertDialog.Builder(context)
            .setTitle(R.string.filter_title)
            .setView(view)
            .setPositiveButton(R.string.filter_apply) { _, _ ->
                onApply(
                    VideoFilter(
                        period = VideoFilter.Period.entries[period.selectedItemPosition],
                        length = VideoFilter.Length.entries[length.selectedItemPosition],
                        sort = VideoFilter.Sort.entries[sort.selectedItemPosition],
                        hideShorts = hideShorts.isChecked,
                        hideLive = hideLive.isChecked,
                        onlySubscribed = showOnlySubscribed && onlySubscribed.isChecked,
                        hiddenWords = VideoFilter.parseWords(words.text.toString())
                    )
                )
            }
            .setNeutralButton(R.string.filter_clear) { _, _ -> onApply(VideoFilter()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun Spinner.setUp(context: Context, entries: Int, selected: Int) {
        adapter = ArrayAdapter.createFromResource(context, entries, android.R.layout.simple_spinner_item)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelection(selected)
    }
}
