package com.tapreader.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Resolves and caches one clean portrait cover per local book. Catalog-provided
 * artwork wins; books received from another device fall back to Open Library's
 * public Covers service. Every successful result is stored under app storage, so
 * the library stays fast and visually stable without a network connection.
 */
class CoverStore(private val context: Context, private val library: LibraryStore) {
    companion object { private const val TAG = "TapReaderCover" }

    private val directory = File(context.filesDir, "covers").apply { mkdirs() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun cachedCover(fileName: String): Bitmap? =
        BitmapFactory.decodeFile(fileFor(fileName).absolutePath)

    /** Call off the UI thread. Returns a cached cover or resolves and caches one. */
    fun loadOrFetch(item: Shelf): Bitmap? {
        cachedCover(item.fileName)?.let { return it }
        val url = item.coverUrl ?: findOpenLibraryCover(item.title, item.author) ?: return null
        val bytes = getBytes(url) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        runCatching { fileFor(item.fileName).writeBytes(bytes) }
            .onFailure { Log.w(TAG, "cover cache failed: ${it.message}") }
        return bitmap
    }

    fun delete(fileName: String) { fileFor(fileName).delete() }

    // ---- URL-keyed thumbnails (Get-free-books gallery) ----------------------

    private val thumbMem = android.util.LruCache<String, Bitmap>(64)
    // One network fetch per URL even when tiles race for the same cover; the
    // loser blocks on the per-URL lock, then finds it cached.
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Memory-cache only — safe on the UI thread. */
    fun cachedThumb(url: String): Bitmap? = thumbMem.get(url)

    /** Fetch + cache a catalog cover thumbnail. Call off the UI thread. */
    fun loadOrFetchThumb(url: String): Bitmap? {
        thumbMem.get(url)?.let { return it }
        val f = thumbFileFor(url)
        if (f.isFile) BitmapFactory.decodeFile(f.absolutePath)?.let {
            thumbMem.put(url, it); return it
        }
        val lock = inFlight.computeIfAbsent(url) { Any() }
        synchronized(lock) {
            try {
                thumbMem.get(url)?.let { return it }
                if (f.isFile) BitmapFactory.decodeFile(f.absolutePath)?.let {
                    thumbMem.put(url, it); return it
                }
                val bytes = getBytes(url) ?: return null
                val bmp = decodeScaled(bytes) ?: return null
                runCatching { f.writeBytes(bytes) }
                    .onFailure { Log.w(TAG, "thumb cache failed: ${it.message}") }
                thumbMem.put(url, bmp)
                return bmp
            } finally {
                inFlight.remove(url)
            }
        }
    }

    /** Catalog covers ship large; the gallery tile needs ~300px. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 300) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun thumbFileFor(url: String) =
        File(directory, "thumb_${url.hashCode().toUInt().toString(16)}.cover")

    /** Free-text Open Library cover search for the companion's cover picker. */
    fun searchCovers(query: String): List<String> {
        return try {
            val url = "https://openlibrary.org/search.json?limit=12&fields=cover_i&q=" +
                URLEncoder.encode(query, "UTF-8")
            val body = getBytes(url)?.toString(Charsets.UTF_8) ?: return emptyList()
            val docs = JSONObject(body).optJSONArray("docs") ?: return emptyList()
            (0 until docs.length()).mapNotNull { i ->
                docs.optJSONObject(i)?.optLong("cover_i", 0L)?.takeIf { it > 0L }
                    ?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
            }.distinct()
        } catch (e: Exception) {
            Log.w(TAG, "cover search failed: ${e.message}")
            emptyList()
        }
    }

    /** Save a user-chosen cover. Only Open Library cover URLs are accepted, so the
     *  companion cannot be used to make the glasses fetch arbitrary hosts. */
    fun applyUrl(item: Shelf, url: String): Boolean {
        if (!url.startsWith("https://covers.openlibrary.org/")) return false
        val bytes = getBytes(url) ?: return false
        if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) return false
        return runCatching { fileFor(item.fileName).writeBytes(bytes) }.isSuccess
    }

    /** Existing cached file for the LAN companion to stream, if available. */
    fun cachedCoverFile(fileName: String): File? = fileFor(fileName).takeIf { it.isFile && it.length() > 0L }

    private fun fileFor(fileName: String) = File(directory, "${fileName.hashCode().toUInt().toString(16)}.cover")

    private fun findOpenLibraryCover(title: String, author: String): String? =
        openLibraryCoverCandidates(title, author).firstOrNull()

    private fun openLibraryCoverCandidates(title: String, author: String): List<String> {
        return try {
            val query = buildString {
                append("https://openlibrary.org/search.json?limit=8&title=")
                append(URLEncoder.encode(title, "UTF-8"))
                if (author.isNotBlank()) append("&author=").append(URLEncoder.encode(author, "UTF-8"))
            }
            val body = getBytes(query)?.toString(Charsets.UTF_8) ?: return emptyList()
            val docs = JSONObject(body).optJSONArray("docs") ?: return emptyList()
            (0 until docs.length()).mapNotNull { i ->
                docs.optJSONObject(i)?.optLong("cover_i", 0L)?.takeIf { it > 0L }
                    ?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
            }.distinct()
        } catch (e: Exception) {
            Log.w(TAG, "Open Library cover lookup failed: ${e.message}")
            emptyList()
        }
    }

    private fun getBytes(url: String): ByteArray? = try {
        val request = Request.Builder().url(url).header("User-Agent", "TapReader/1.0").build()
        http.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "cover download failed: ${e.message}")
        null
    }
}
