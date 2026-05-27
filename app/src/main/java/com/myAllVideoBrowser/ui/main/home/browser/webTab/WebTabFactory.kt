package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.util.Patterns
import com.myAllVideoBrowser.ui.main.home.browser.BrowserViewModel
import com.myAllVideoBrowser.util.SharedPrefHelper

class WebTabFactory {
    companion object {

        /**
         * Build a [WebTab] from arbitrary user input. Honours the search engine the user has
         * picked in Settings; falls back to the legacy [BrowserViewModel.SEARCH_URL] template
         * when no [SharedPrefHelper] is available.
         */
        @JvmStatic
        @JvmOverloads
        fun createWebTabFromInput(input: String, sharedPrefHelper: SharedPrefHelper? = null): WebTab {
            if (input.isEmpty()) return WebTab.HOME_TAB

            val trimmed = input.trim()
            return when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                    WebTab(trimmed, null, null, emptyMap())

                Patterns.WEB_URL.matcher(trimmed).matches() ->
                    WebTab("https://$trimmed", null, null, emptyMap())

                else -> {
                    val template = sharedPrefHelper?.getSearchEngineTemplate()
                        ?: BrowserViewModel.SEARCH_URL
                    val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
                    val url = if (template.contains("%s")) {
                        template.replace("%s", encoded)
                    } else {
                        String.format(template, encoded)
                    }
                    WebTab(url, null, null, emptyMap())
                }
            }
        }
    }
}
