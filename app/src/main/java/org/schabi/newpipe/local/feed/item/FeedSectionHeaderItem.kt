package org.schabi.newpipe.local.feed.item

import android.view.View
import androidx.core.view.isVisible
import com.xwray.groupie.viewbinding.BindableItem
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FeedSectionHeaderBinding

/**
 * TubeTube: header splitting the feed into "Coming soon" (premieres, scheduled lives) and the
 * regular feed. The "Coming soon" one can be collapsed; [expanded] is null for a fixed header.
 */
class FeedSectionHeaderItem(
    private val title: String,
    val expanded: Boolean?
) : BindableItem<FeedSectionHeaderBinding>() {
    override fun getLayout(): Int = R.layout.feed_section_header

    override fun initializeViewBinding(view: View) = FeedSectionHeaderBinding.bind(view)

    override fun bind(viewBinding: FeedSectionHeaderBinding, position: Int) {
        viewBinding.sectionTitle.text = title
        viewBinding.sectionArrow.isVisible = expanded != null
        viewBinding.sectionArrow.rotation = if (expanded == true) 180f else 0f
    }

    /** Headers take the whole row in grid mode. */
    override fun getSpanSize(spanCount: Int, position: Int): Int = spanCount

    override fun isSameAs(other: com.xwray.groupie.Item<*>): Boolean = other is FeedSectionHeaderItem && other.title.substringBefore(" (") == title.substringBefore(" (")

    override fun hasSameContentAs(other: com.xwray.groupie.Item<*>): Boolean = other is FeedSectionHeaderItem && other.title == title && other.expanded == expanded
}
