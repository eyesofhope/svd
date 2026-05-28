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
        fun createWebTabFromInput(
            input: String,
            sharedPrefHelper: SharedPrefHelper? = null,
            isIncognito: Boolean = false
        ): WebTab {
            // Incognito tabs are allowed to be empty (they show the incognito home).
            if (input.isEmpty() && !isIncognito) return WebTab.HOME_TAB
            if (input.isEmpty() && isIncognito) {
                return WebTab("", null, null, emptyMap(), isIncognito = true)
            }

            val trimmed = input.trim()
            return when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                    WebTab(trimmed, null, null, emptyMap(), isIncognito = isIncognito)

                Patterns.WEB_URL.matcher(trimmed).matches() ->
                    WebTab("https://$trimmed", null, null, emptyMap(), isIncognito = isIncognito)

                else -> {
                    val template = sharedPrefHelper?.getSearchEngineTemplate()
                        ?: BrowserViewModel.SEARCH_URL
                    val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
                    val url = if (template.contains("%s")) {
                        template.replace("%s", encoded)
                    } else {
                        String.format(template, encoded)
                    }
                    WebTab(url, null, null, emptyMap(), isIncognito = isIncognito)
                }
            }
        }

        /** Convenience: create an incognito tab from arbitrary input. */
        @JvmStatic
        @JvmOverloads
        fun createIncognitoTabFromInput(
            input: String,
            sharedPrefHelper: SharedPrefHelper? = null
        ): WebTab = createWebTabFromInput(input, sharedPrefHelper, isIncognito = true)
    }
}
