package com.myAllVideoBrowser.ui.component.binding

import androidx.annotation.OptIn
import androidx.databinding.BindingAdapter
import androidx.media3.common.util.UnstableApi
//import androidx.viewpager2.widget.ViewPager2
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.ui.main.home.browser.CustomViewPager2
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab

object CustomViewPager2Binding {

    @OptIn(UnstableApi::class)
    @BindingAdapter("app:items")
    @JvmStatic
    fun CustomViewPager2.setWebItems(currentItems: List<WebTab>?) {
        with(adapter as BrowserFragment.TabsFragmentStateAdapter?) {
            this?.setRoutes(currentItems ?: emptyList())
        }
    }

    @BindingAdapter("app:offScreenPageLimit")
    @JvmStatic
    fun CustomViewPager2.setOffScreenPageLimit(pageLimit: Int) {
        offscreenPageLimit = pageLimit
    }

    @BindingAdapter("app:currentItem")
    @JvmStatic
    fun CustomViewPager2.setCurrentItem(currentItemPosition: Int) {
        // CRITICAL ORDERING FIX:
        // `app:items` (adapter data) and `app:currentItem` are applied in the
        // same DataBinding pass. If we set the position synchronously here, it
        // can run BEFORE the adapter has grown for a newly-opened tab, so
        // ViewPager2 clamps the index to the OLD item count and lands one tab
        // behind — the "1-step delay" where opening a tab shows the previous
        // one and the page/back-button target the wrong fragment.
        //
        // Posting the position change defers it until after the adapter's
        // notifyDataSetChanged() has taken effect, and smoothScroll=false makes
        // it jump straight to the target page instead of animating toward a
        // page that may not be laid out yet.
        if (currentItem == currentItemPosition) return
        post {
            val count = adapter?.itemCount ?: 0
            val target = currentItemPosition.coerceIn(0, (count - 1).coerceAtLeast(0))
            if (currentItem != target) {
                setCurrentItem(target, false)
            }
        }
    }
}
