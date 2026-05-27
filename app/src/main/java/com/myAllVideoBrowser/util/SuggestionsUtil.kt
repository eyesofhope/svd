package com.myAllVideoBrowser.util

import com.myAllVideoBrowser.data.local.model.Suggestion
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class SuggestionsUtils {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun getSuggestions(
            okHttpClient: OkHttpClient,
            input: String,
            engine: SearchEngineRegistry.Engine = SearchEngineRegistry.engines.first()
        ): Flowable<List<Suggestion>> {
            return Flowable.create({ emitter ->
                val result: ArrayList<Suggestion> = ArrayList()
                if (input.isBlank()) {
                    emitter.onNext(result)
                    return@create
                }

                try {
                    val encoded = URLEncoder.encode(input, "UTF-8")
                    val url = engine.suggestUrlTemplate.replace("%s", encoded)
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json, text/javascript, */*; q=0.01")
                        .build()
                    val body = okHttpClient.newCall(request).execute()
                        .use { response -> response.body.string() }

                    when (engine.suggestionFormat) {
                        SearchEngineRegistry.SuggestionFormat.DUCKDUCKGO_PHRASES ->
                            parseDuckDuckGo(body, result)

                        SearchEngineRegistry.SuggestionFormat.OPEN_SEARCH_TUPLE ->
                            parseOpenSearchTuple(body, result)
                    }
                } catch (e: Throwable) {
                    AppLogger.e("Suggestion fetch failed: ${e.message}")
                }
                emitter.onNext(result)
            }, BackpressureStrategy.LATEST)
        }

        private fun parseDuckDuckGo(body: String, into: ArrayList<Suggestion>) {
            val jsn = JSONArray(body)
            for (i in 0 until jsn.length()) {
                try {
                    val phraseObj = JSONObject(jsn.get(i).toString())
                    val phrase = phraseObj.get("phrase").toString()
                    into.add(Suggestion(content = phrase))
                } catch (_: Throwable) {
                }
            }
        }

        /**
         * Parses the OpenSearch suggestion tuple `["query", ["s1", "s2", ...], ...]` shared by
         * Google / Bing / Yahoo / Ask / Yandex.
         */
        private fun parseOpenSearchTuple(body: String, into: ArrayList<Suggestion>) {
            val outer = JSONArray(body)
            if (outer.length() < 2) return
            val arr = outer.optJSONArray(1) ?: return
            for (i in 0 until arr.length()) {
                try {
                    val phrase = arr.getString(i)
                    if (phrase.isNotBlank()) {
                        into.add(Suggestion(content = phrase))
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }
}
