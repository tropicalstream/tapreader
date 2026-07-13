package com.tapreader.app

import android.util.Log
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Book overviews from public, reputable references — Wikipedia first, then the
 * book's Open Library work description. No LLM involved: what the companion
 * shows is what the source actually says, with the source named.
 */
object BookInfoClient {
    private const val TAG = "TapReaderInfo"

    data class Summary(val text: String, val source: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Summary>()

    fun summary(title: String, author: String): Result<Summary> = runCatching {
        val key = "$title|$author"
        cache[key]?.let { return@runCatching it }
        val found = wikipedia(title, author)
            ?: openLibrary(title, author)
            ?: throw IllegalStateException("Neither Wikipedia nor Open Library has a description for \"$title\"")
        cache[key] = found
        found
    }.onFailure { Log.w(TAG, "summary lookup failed: ${it.message}") }

    /** Wikipedia's lead-section extract. Tries the plain title, then "(novel)". */
    private fun wikipedia(title: String, author: String): Summary? {
        for (candidate in listOf(title, "$title (novel)")) {
            val o = getJson("https://en.wikipedia.org/api/rest_v1/page/summary/" +
                URLEncoder.encode(candidate, "UTF-8").replace("+", "%20")) ?: continue
            if (o.optString("type") != "standard") continue
            val extract = o.optString("extract").trim()
            // Guard against matching an unrelated article: for the plain-title hit,
            // require the author's surname to appear when we know the author.
            val surname = author.trim().substringAfterLast(' ')
            if (candidate == title && surname.length > 2 && !extract.contains(surname, ignoreCase = true)) continue
            if (extract.length >= 80) return Summary(extract, "Wikipedia")
        }
        return null
    }

    private fun openLibrary(title: String, author: String): Summary? {
        val query = buildString {
            append("https://openlibrary.org/search.json?limit=1&fields=key&title=")
            append(URLEncoder.encode(title, "UTF-8"))
            if (author.isNotBlank()) append("&author=").append(URLEncoder.encode(author, "UTF-8"))
        }
        val workKey = getJson(query)?.optJSONArray("docs")?.optJSONObject(0)?.optString("key") ?: return null
        if (!workKey.startsWith("/works/")) return null
        val work = getJson("https://openlibrary.org$workKey.json") ?: return null
        val description = when (val d = work.opt("description")) {
            is String -> d
            is JSONObject -> d.optString("value")
            else -> null
        }?.trim()?.takeIf { it.length >= 60 } ?: return null
        // Descriptions sometimes carry markdown links/dashes; keep it readable.
        return Summary(description.take(1_200), "Open Library")
    }

    private fun getJson(url: String): JSONObject? = try {
        val request = Request.Builder().url(url).header("User-Agent", "TapReader/1.0").build()
        http.newCall(request).execute().use { r ->
            if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "lookup failed: ${e.message}"); null
    }
}
