package com.tapreader.app

import android.util.Log
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Acquire books from legitimate free sources. In-app search + one-tap download
 * is powered by Gutendex (the Project Gutenberg API, ~75k public-domain books),
 * which serves DRM-free .epub/.txt directly. Other repositories and library
 * checkout systems are listed as links (opened from the companion app's browser,
 * since checkout flows and their apps live off-device).
 */
object BookSource {
    private const val TAG = "TapReader"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        // Generous read window: a book download over glasses Wi-Fi from a
        // throttled mirror can legitimately dribble for a while.
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class Result(
        val title: String,
        val author: String,
        val downloadUrl: String,
        val ext: String,
        val coverUrl: String?
    ) {
        fun suggestedFileName(): String {
            val base = (title.take(60) + if (author.isNotBlank()) " - ${author.take(30)}" else "")
                .replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().replace(Regex("\\s+"), "_")
            return "$base.$ext"
        }
    }

    /** Search Project Gutenberg via Gutendex. Returns downloadable results only. */
    fun searchGutenberg(query: String): List<Result> =
        gutendex("https://gutendex.com/books/?search=" + URLEncoder.encode(query.trim(), "UTF-8"))

    /** Popular Project Gutenberg books (most downloaded) — for tap-to-download browse. */
    fun popularGutenberg(): List<Result> = gutendex("https://gutendex.com/books/?sort=popular")

    private fun gutendex(url: String): List<Result> {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "TapReader/1.0").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val json = JSONObject(resp.body?.string().orEmpty())
                val arr = json.optJSONArray("results") ?: return emptyList()
                val out = ArrayList<Result>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val title = o.optString("title", "Untitled")
                    val authors = o.optJSONArray("authors")
                    val author = if (authors != null && authors.length() > 0)
                        normalizeName(authors.getJSONObject(0).optString("name", "")) else ""
                    val formats = o.optJSONObject("formats") ?: continue
                    var (dl, ext) = pickFormat(formats) ?: continue
                    // Gutendex hands out the illustrated epub (often 20+ MB). The
                    // reader is text-only, so fetch Gutenberg's .noimages variant
                    // instead — same book, ~30x smaller, quick on glasses Wi-Fi.
                    dl = dl.replace(Regex("\\.epub3?\\.images$"), ".epub.noimages")
                    val cover = formats.optString("image/jpeg", "").takeIf { it.isNotBlank() }
                    out.add(Result(title, author, dl, ext, cover))
                }
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "gutenberg search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Standard Ebooks: their OPDS feed now requires a paid Patrons membership, but
     * the public HTML catalog and individual epub downloads are free and open. We
     * scrape the search results for book pages and build the (predictable) epub
     * download URL: /ebooks/<author>/<title>/downloads/<author>_<title>.epub
     */
    fun searchStandardEbooks(query: String): List<Result> =
        standardEbooks("https://standardebooks.org/ebooks?query=" + URLEncoder.encode(query.trim(), "UTF-8"))

    /** Newest Standard Ebooks — for tap-to-download browse (no typing). */
    fun browseStandardEbooks(): List<Result> = standardEbooks("https://standardebooks.org/ebooks")

    private fun standardEbooks(url: String): List<Result> {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "TapReader/1.0 (personal ereader)").build()
            val html = http.newCall(req).execute().use { r -> if (!r.isSuccessful) return emptyList(); r.body?.string().orEmpty() }
            val paths = Regex("/ebooks/([a-z0-9._-]+)/([a-z0-9._-]+)").findAll(html)
                .map { it.value to it.groupValues }
                .filter { it.second[2] != "downloads" }   // exclude .../downloads
                .map { it.first }
                .distinct().take(24).toList()
            paths.mapNotNull { path ->
                val parts = path.removePrefix("/ebooks/").split("/")
                if (parts.size != 2) return@mapNotNull null
                val slug = "${parts[0]}_${parts[1]}"
                Result(
                    title = prettify(parts[1]),
                    author = prettify(parts[0]),
                    // ?source=download skips SE's "download has started" interstitial
                    // page and returns the actual epub bytes.
                    downloadUrl = "https://standardebooks.org$path/downloads/$slug.epub?source=download",
                    ext = "epub",
                    coverUrl = null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "standard ebooks search failed: ${e.message}")
            emptyList()
        }
    }

    private fun prettify(slug: String): String =
        slug.split('-').joinToString(" ") { w ->
            if (w.length <= 3 && w in setOf("of", "the", "and", "or", "a", "an", "to", "in")) w
            else w.replaceFirstChar { it.uppercase() }
        }.replaceFirstChar { it.uppercase() }

    /** Prefer EPUB (structured), then UTF-8 plain text, then any plain text. */
    private fun pickFormat(formats: JSONObject): Pair<String, String>? {
        formats.optString("application/epub+zip").takeIf { it.isNotBlank() }?.let { return it to "epub" }
        val keys = formats.keys()
        var plain: String? = null
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("text/plain")) {
                val v = formats.optString(k)
                if (k.contains("utf-8", true) && v.isNotBlank()) return v to "txt"
                if (v.isNotBlank() && plain == null) plain = v
            }
        }
        return plain?.let { it to "txt" }
    }

    fun download(url: String): ByteArray? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "TapReader/1.0").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}"); null
        }
    }

    private fun normalizeName(lastFirst: String): String {
        val parts = lastFirst.split(",").map { it.trim() }
        return if (parts.size == 2) "${parts[1]} ${parts[0]}" else lastFirst
    }

    /** Free & legitimate repositories / checkout systems (opened in a browser). */
    data class Repo(val name: String, val note: String, val url: String)
    val REPOSITORIES = listOf(
        Repo("Project Gutenberg", "75k+ public-domain books (searchable in-app)", "https://www.gutenberg.org/"),
        Repo("Standard Ebooks", "Beautifully formatted public-domain ebooks", "https://standardebooks.org/ebooks"),
        Repo("Open Library", "Borrow & read from the Internet Archive", "https://openlibrary.org/"),
        Repo("Internet Archive", "Millions of free texts", "https://archive.org/details/texts"),
        Repo("LibriVox", "Free public-domain audiobooks", "https://librivox.org/"),
        Repo("Libby / OverDrive", "Borrow ebooks with your library card", "https://libbyapp.com/"),
        Repo("Hoopla", "Library streaming — books, audiobooks", "https://www.hoopladigital.com/"),
        Repo("ManyBooks", "Free & discounted ebooks", "https://manybooks.net/")
    )
}
