package com.myAllVideoBrowser.util

/**
 * Single source of truth for the search engines wired into the app.
 *
 * Every engine declares both how a regular query is resolved into a search URL and how
 * autocomplete suggestions are fetched, so the user's pick in Settings flows through to the
 * address-bar suggestions as well.
 */
object SearchEngineRegistry {

    /**
     * Format used to parse autocomplete responses.
     *
     * - [DUCKDUCKGO_PHRASES]: array of objects, each having a "phrase" field.
     * - [OPEN_SEARCH_TUPLE]: `[query, [suggestions]]` shape (Google / Bing / Yandex / Ask).
     */
    enum class SuggestionFormat {
        DUCKDUCKGO_PHRASES,
        OPEN_SEARCH_TUPLE
    }

    data class Engine(
        val name: String,
        val searchUrlTemplate: String,
        val suggestUrlTemplate: String,
        val suggestionFormat: SuggestionFormat
    )

    // DuckDuckGo first so its URL is the canonical fallback when nothing else matches.
    val engines: List<Engine> = listOf(
        Engine(
            name = "DuckDuckGo (Privacy)",
            searchUrlTemplate = "https://duckduckgo.com/?q=%s",
            suggestUrlTemplate = "https://duckduckgo.com/ac/?q=%s&kl=wt-wt",
            suggestionFormat = SuggestionFormat.DUCKDUCKGO_PHRASES
        ),
        Engine(
            name = "Google",
            searchUrlTemplate = "https://www.google.com/search?q=%s",
            suggestUrlTemplate = "https://suggestqueries.google.com/complete/search?client=firefox&q=%s",
            suggestionFormat = SuggestionFormat.OPEN_SEARCH_TUPLE
        ),
        Engine(
            name = "Bing",
            searchUrlTemplate = "https://www.bing.com/search?q=%s",
            suggestUrlTemplate = "https://www.bing.com/osjson.aspx?query=%s",
            suggestionFormat = SuggestionFormat.OPEN_SEARCH_TUPLE
        ),
        Engine(
            name = "Yahoo",
            searchUrlTemplate = "https://search.yahoo.com/search?p=%s",
            // Yahoo's own gossip endpoint is JSONP; Bing's osjson works as a sane open-search fallback.
            suggestUrlTemplate = "https://www.bing.com/osjson.aspx?query=%s",
            suggestionFormat = SuggestionFormat.OPEN_SEARCH_TUPLE
        )
    )

    /** Engine matching the saved template, or DuckDuckGo when the user has a custom URL. */
    fun findByTemplate(template: String?): Engine {
        if (template.isNullOrBlank()) return engines.first()
        return engines.firstOrNull { it.searchUrlTemplate == template } ?: engines.first()
    }
}
