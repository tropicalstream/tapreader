package com.tapreader.app

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-first TapReader companion server. Opening http://GLASSES_IP:8787 on a
 * phone serves the companion itself; all data and private keys remain on the
 * glasses. The API is deliberately LAN-only and carries no third-party tracker.
 */
class ReceiveServer(
    private val context: Context,
    private val library: LibraryStore,
    private val onReceived: (fileName: String) -> Unit,
    /** Any companion-driven library mutation (delete/restore/reset/cover): the
     *  glasses' on-screen shelf must follow without user navigation. */
    private val onLibraryChanged: () -> Unit = {}
) {
    companion object {
        const val PORT = 8787
        private const val TAG = "TapReader"
        private const val MAX_BYTES = 200L * 1024 * 1024
    }

    private val covers = CoverStore(context, library)
    private val coach = GeminiCoachClient(library)
    private val voicePreview = VoicePreviewClient(library)
    @Volatile private var running = false
    private var server: ServerSocket? = null

    // Parsing a whole book is expensive; cache by file + mtime so repeated /book
    // and /coach calls don't re-parse. Invalidated automatically on re-upload.
    private val parseCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Book>>()
    private fun parseBook(fileName: String): Book? {
        val f = File(library.booksDir, fileName)
        if (!f.exists()) return null
        val stamp = f.lastModified()
        parseCache[fileName]?.let { if (it.first == stamp) return it.second }
        val b = runCatching { DocumentParser.parse(context, f) }.getOrNull() ?: return null
        parseCache[fileName] = stamp to b
        return b
    }

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                server = ServerSocket(PORT)
                Log.i(TAG, "TapReader companion at http://${ipAddress()}:$PORT")
                while (running) {
                    val sock = try { server!!.accept() } catch (_: Exception) { break }
                    Thread { runCatching { handle(sock) }.onFailure { Log.w(TAG, "server request: ${it.message}") } }.start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "server failed: ${e.message}")
            }
        }.start()
    }

    fun stop() { running = false; runCatching { server?.close() }; server = null }

    fun ipAddress(): String = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") val ip = wm.connectionInfo.ipAddress
        if (ip == 0) "?" else InetAddress.getByAddress(byteArrayOf(
            (ip and 0xff).toByte(), (ip shr 8 and 0xff).toByte(),
            (ip shr 16 and 0xff).toByte(), (ip shr 24 and 0xff).toByte()
        )).hostAddress ?: "?"
    } catch (_: Exception) { "?" }

    private fun handle(sock: Socket) {
        sock.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { respond(out, 400, "bad request"); return }
            val method = parts[0].uppercase()
            val path = parts[1]
            var contentLength = 0L
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                val index = header.indexOf(':')
                if (index > 0 && header.substring(0, index).trim().equals("Content-Length", true)) {
                    contentLength = header.substring(index + 1).trim().toLongOrNull() ?: 0L
                }
            }
            if (contentLength > MAX_BYTES) { respond(out, 413, "payload too large"); return }
            val body = if (contentLength > 0) readExactly(input, contentLength.toInt()) else ByteArray(0)
            if (contentLength > 0 && body.size != contentLength.toInt()) { respond(out, 400, "truncated request"); return }

            if (method == "OPTIONS") { respond(out, 204, ""); return }
            when {
                method == "GET" && (path == "/" || path == "/index.html" || path == "/companion.css" || path == "/companion.js") -> serveAsset(out, path)
                path.startsWith("/ping") -> respond(out, 200, "TapReader ${library.getString("device_name", "Glasses")}")

                method == "GET" && (path == "/stats" || path == "/api/v1/state") -> respondJson(out, stateJson())
                method == "GET" && path.startsWith("/book") -> respondJson(out, bookJson(query(path, "file")))
                method == "GET" && path.startsWith("/api/v1/settings") -> respondJson(out, settingsJson().toString())
                method == "POST" && path.startsWith("/api/v1/settings") -> respondJson(out, updateSettings(json(body)).toString())
                method == "POST" && path.startsWith("/api/v1/cover/search") -> coverSearch(out, json(body))
                method == "POST" && path.startsWith("/api/v1/cover/apply") -> coverApply(out, json(body))
                method == "GET" && path.startsWith("/api/v1/cover") -> serveCover(out, query(path, "file"))
                method == "DELETE" && path.startsWith("/api/v1/books/purge") -> purgeBook(out, query(path, "file"))
                method == "DELETE" && path.startsWith("/api/v1/books") -> deleteBook(out, query(path, "file"))
                method == "POST" && path.startsWith("/api/v1/books/restore") -> restoreBook(out, json(body).optString("file"))
                method == "POST" && path.startsWith("/api/v1/books/reset") -> resetBook(out, json(body).optString("file"))
                method == "POST" && path.startsWith("/api/v1/books/summary") -> bookSummary(out, json(body).optString("file"))
                method == "GET" && path.startsWith("/api/v1/voices/search") -> searchVoices(out, query(path, "q"))
                method == "POST" && path.startsWith("/api/v1/voices/select") -> selectVoice(out, json(body).optString("id"))
                method == "POST" && path.startsWith("/api/v1/voices") -> saveVoice(out, json(body))
                method == "DELETE" && path.startsWith("/api/v1/voices") -> deleteVoice(out, query(path, "id"))
                method == "POST" && path.startsWith("/api/v1/voice/preview") -> previewVoice(out, json(body))
                method == "POST" && path.startsWith("/api/v1/keys/test") -> testKey(out, json(body))
                method == "POST" && path.startsWith("/api/v1/coach") -> coach(out, json(body).optString("file"))
                method == "GET" && path.startsWith("/api/v1/sources/popular") -> sourcesPopular(out, query(path, "source"))
                method == "GET" && path.startsWith("/api/v1/sources/search") -> sourcesSearch(out, query(path, "source"), query(path, "q"))
                method == "GET" && path.startsWith("/api/v1/sources/repos") -> sourcesRepos(out)
                method == "POST" && path.startsWith("/api/v1/sources/download") -> sourcesDownload(out, json(body))
                method == "POST" && path.startsWith("/api/v1/nas/shares") -> nasShares(out, json(body))
                method == "POST" && path.startsWith("/api/v1/nas/list") -> nasList(out, json(body))
                method == "POST" && path.startsWith("/api/v1/nas/import") -> nasImport(out, json(body))
                method == "POST" && path.startsWith("/upload") -> upload(out, query(path, "name") ?: "book.txt", body)
                else -> respond(out, 404, "not found")
            }
        }
    }

    private fun stateJson(): String = JSONObject(statsJson()).put("settings", settingsJson()).toString()

    private fun statsJson(): String {
        val s = library.streak()
        fun bookJson(item: Shelf) = JSONObject()
            .put("file", item.fileName).put("title", item.title).put("author", item.author)
            .put("wordIndex", item.wordIndex).put("wordsRead", item.wordsRead).put("wordCount", item.wordCount)
            .put("percent", item.percent).put("lastOpened", item.lastOpened)
            .put("coverUrl", "/api/v1/cover?file=${java.net.URLEncoder.encode(item.fileName, "UTF-8")}")
        val books = JSONArray().apply { library.shelf().forEach { put(bookJson(it)) } }
        val archived = JSONArray().apply { library.archivedShelf().forEach { put(bookJson(it)) } }
        return JSONObject().put("device", library.getString("device_name", "Glasses"))
            .put("streak", JSONObject().put("days", s.days).put("todayWords", s.todayWords)
                .put("totalWords", s.totalWords).put("goal", s.goalWords).put("goalPercent", s.goalPercent))
            .put("books", books).put("archived", archived).toString()
    }

    private fun bookJson(fileName: String?): String {
        if (fileName.isNullOrBlank()) return "{\"error\":\"no file\"}"
        val b = parseBook(fileName) ?: return "{\"error\":\"parse failed\"}"
        val shelf = library.shelf().firstOrNull { it.fileName == fileName }
        val wordIndex = shelf?.wordIndex ?: 0
        val chapters = JSONArray().apply { b.chapterTitles.forEach { put(it) } }
        return JSONObject().put("file", fileName).put("title", b.title).put("author", b.author).put("format", b.format)
            .put("wordCount", b.wordCount).put("wordIndex", wordIndex).put("wordsRead", shelf?.wordsRead ?: 0)
            .put("percent", shelf?.percent ?: 0).put("currentChapter", b.chapterAt(wordIndex))
            .put("chapterCount", b.chapterTitles.size).put("chapters", chapters).toString()
    }

    /** The browser receives only masked API-key state, never a value. */
    private fun settingsJson(): JSONObject {
        val voices = JSONArray().apply {
            library.voicePresets().forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        }
        return JSONObject()
            .put("deviceName", library.getString("device_name", "Glasses"))
            .put("reader", JSONObject().put("fontSp", library.getInt(LibraryStore.K_FONT_SP, 21))
                .put("wpm", library.getInt(LibraryStore.K_WPM, 320)).put("dailyGoal", library.getInt(LibraryStore.K_DAILY_GOAL, 2000))
                .put("theme", library.getInt(LibraryStore.K_THEME, 0)).put("mode", library.getInt(LibraryStore.K_MODE, 0))
                .put("topHud", library.getBool(LibraryStore.K_TOP_HUD, true)))
            .put("keys", JSONObject().put("fish", masked(library.getString(LibraryStore.K_FISH_KEY, "")))
                .put("gemini", masked(library.getString("llm_key_gemini", ""))))
            .put("voices", voices).put("selectedVoice", library.getString(LibraryStore.K_FISH_VOICE, TtsReader.DEFAULT_VOICE))
    }

    private fun updateSettings(body: JSONObject): JSONObject {
        body.optString("deviceName").trim().takeIf { it.isNotBlank() }?.let { library.putString("device_name", it.take(40)) }
        body.optJSONObject("reader")?.let { reader ->
            if (reader.has("fontSp")) library.putInt(LibraryStore.K_FONT_SP, reader.optInt("fontSp").coerceIn(12, 40))
            if (reader.has("wpm")) library.putInt(LibraryStore.K_WPM, reader.optInt("wpm").coerceIn(80, 900))
            if (reader.has("dailyGoal")) library.putInt(LibraryStore.K_DAILY_GOAL, reader.optInt("dailyGoal").coerceIn(200, 20_000))
            if (reader.has("theme")) library.putInt(LibraryStore.K_THEME, reader.optInt("theme").coerceIn(0, 2))
            if (reader.has("mode")) library.putInt(LibraryStore.K_MODE, reader.optInt("mode").coerceIn(0, 2))
            if (reader.has("topHud")) library.putBool(LibraryStore.K_TOP_HUD, reader.optBoolean("topHud", true))
        }
        body.optJSONObject("keys")?.let { keys ->
            keys.optString("fish").trim().takeIf { it.isNotBlank() }?.let { library.putString(LibraryStore.K_FISH_KEY, it) }
            keys.optString("gemini").trim().takeIf { it.isNotBlank() }?.let {
                library.putString("llm_key_gemini", it); library.putString(LibraryStore.K_LLM_PROVIDER, "gemini")
            }
        }
        body.optString("selectedVoice").trim().takeIf { it.isNotBlank() }?.let { library.putString(LibraryStore.K_FISH_VOICE, it) }
        return settingsJson()
    }

    private fun serveCover(out: OutputStream, fileName: String?) {
        val item = library.shelf().firstOrNull { it.fileName == fileName }
            ?: library.archivedShelf().firstOrNull { it.fileName == fileName }
        if (item == null) { respond(out, 404, "book not found"); return }
        val cover = covers.cachedCoverFile(item.fileName) ?: run { covers.loadOrFetch(item); covers.cachedCoverFile(item.fileName) }
        if (cover == null) { respond(out, 404, "cover unavailable"); return }
        writeBytes(out, 200, "image/jpeg", cover.readBytes())
    }

    private fun coverSearch(out: OutputStream, body: JSONObject) {
        val query = body.optString("query").trim()
        if (query.isBlank()) { respond(out, 400, "query required"); return }
        val urls = covers.searchCovers(query)
        respondJson(out, JSONObject().put("covers", JSONArray().apply { urls.forEach { put(it) } }).toString())
    }

    private fun coverApply(out: OutputStream, body: JSONObject) {
        val item = library.shelf().firstOrNull { it.fileName == body.optString("file") }
        if (item == null) { respond(out, 404, "book not found"); return }
        if (covers.applyUrl(item, body.optString("url"))) { onLibraryChanged(); respondJson(out, "{\"ok\":true}") }
        else respond(out, 422, "Could not download that cover")
    }

    private fun bookSummary(out: OutputStream, fileName: String) {
        val item = library.shelf().firstOrNull { it.fileName == fileName }
        if (item == null) { respond(out, 404, "book not found"); return }
        BookInfoClient.summary(item.title, item.author).fold(
            onSuccess = { respondJson(out, JSONObject().put("summary", it.text).put("source", it.source).toString()) },
            onFailure = { respond(out, 422, it.message ?: "Summary unavailable") }
        )
    }

    private fun deleteBook(out: OutputStream, fileName: String?) {
        if (fileName.isNullOrBlank()) { respond(out, 400, "no file"); return }
        // Not destructive: the book moves to the archive ("Off the glasses" in the
        // companion) with its cover and progress intact, ready to restore.
        library.archive(fileName); onLibraryChanged(); respondJson(out, "{\"ok\":true}")
    }

    private fun restoreBook(out: OutputStream, fileName: String?) {
        if (fileName.isNullOrBlank()) { respond(out, 400, "no file"); return }
        if (library.restore(fileName)) { onReceived(fileName); respondJson(out, "{\"ok\":true}") }
        else respond(out, 404, "not in the archive")
    }

    private fun purgeBook(out: OutputStream, fileName: String?) {
        if (fileName.isNullOrBlank()) { respond(out, 400, "no file"); return }
        covers.delete(fileName); library.purge(fileName); onLibraryChanged(); respondJson(out, "{\"ok\":true}")
    }

    private fun resetBook(out: OutputStream, fileName: String?) {
        if (fileName.isNullOrBlank()) { respond(out, 400, "no file"); return }
        library.resetProgress(fileName); onLibraryChanged(); respondJson(out, "{\"ok\":true}")
    }

    private fun saveVoice(out: OutputStream, body: JSONObject) {
        val id = body.optString("id").trim()
        if (id.isBlank()) { respond(out, 400, "voice ID required"); return }
        val presets = library.voicePresets()
        // Keep at most five; reject the sixth rather than silently evicting one.
        if (presets.none { it.id == id } && presets.size >= 5) {
            respond(out, 409, "You already have 5 saved voices. Delete one to make room."); return
        }
        library.saveVoicePreset(body.optString("name"), id)
        if (library.getString(LibraryStore.K_FISH_VOICE, "").isBlank()) library.putString(LibraryStore.K_FISH_VOICE, id)
        respondJson(out, settingsJson().toString())
    }

    private fun searchVoices(out: OutputStream, q: String?) {
        val results = voicePreview.search(q ?: "")
        if (results == null) { respond(out, 422, "Add a fish.audio key in Settings to search voices"); return }
        respondJson(out, JSONObject().put("results", results).toString())
    }

    private fun deleteVoice(out: OutputStream, id: String?) {
        if (id.isNullOrBlank()) { respond(out, 400, "voice ID required"); return }
        library.deleteVoicePreset(id); respondJson(out, settingsJson().toString())
    }

    private fun selectVoice(out: OutputStream, id: String) {
        if (library.voicePresets().none { it.id == id }) { respond(out, 400, "unknown voice"); return }
        library.putString(LibraryStore.K_FISH_VOICE, id); respondJson(out, settingsJson().toString())
    }

    private fun previewVoice(out: OutputStream, body: JSONObject) {
        val audio = voicePreview.preview(body.optString("id"), body.optString("text"))
        if (audio == null) { respond(out, 422, "Preview unavailable: add a fish.audio key and a valid voice ID"); return }
        writeBytes(out, 200, "audio/mpeg", audio)
    }

    /** Save-&-test: when the browser sends a freshly typed key, validate THAT key
     *  live and only persist it if it works. With no key supplied, test the saved one. */
    private fun testKey(out: OutputStream, body: JSONObject) {
        val service = body.optString("service")
        val typed = body.optString("key").trim()
        val result = when (service) {
            "gemini" -> coach.validateKey(typed.ifBlank { null }).map {
                if (typed.isNotBlank()) {
                    library.putString("llm_key_gemini", typed)
                    library.putString(LibraryStore.K_LLM_PROVIDER, "gemini")
                    "Gemini key works — saved to the glasses"
                } else "Saved Gemini key is working"
            }
            "fish" -> {
                val voice = library.getString(LibraryStore.K_FISH_VOICE, TtsReader.DEFAULT_VOICE).ifBlank { TtsReader.DEFAULT_VOICE }
                voicePreview.check(voice, typed.ifBlank { null }).map {
                    if (typed.isNotBlank()) {
                        library.putString(LibraryStore.K_FISH_KEY, typed)
                        "fish.audio key works — saved to the glasses"
                    } else "Saved fish.audio key is working"
                }.recoverCatching {
                    throw IllegalStateException(if (typed.isNotBlank()) "${it.message} — nothing was saved" else it.message)
                }
            }
            else -> Result.failure(IllegalArgumentException("Unknown service"))
        }
        result.fold(
            onSuccess = { respondJson(out, JSONObject().put("ok", true).put("message", it).toString()) },
            onFailure = { respondJson(out, JSONObject().put("ok", false).put("message", it.message ?: "Connection test failed").toString()) }
        )
    }

    private fun coach(out: OutputStream, fileName: String) {
        val b = parseBook(fileName)
        if (b == null) { respond(out, 404, "book not found"); return }
        val focused = library.savedWordIndex(fileName)
        coach.coach(b, focused).fold(
            onSuccess = { note -> respondJson(out, JSONObject().put("summary", note.summary).put("encouragement", note.encouragement)
                .put("fresh", note.fresh)
                .put("chapter", b.chapterAt(focused) + 1).put("percent", if (b.wordCount > 0) focused * 100 / b.wordCount else 0).toString()) },
            onFailure = { respond(out, 422, it.message ?: "Coach unavailable") }
        )
    }

    // ---- Free book sources (glasses fetch the catalogs and the files) -------

    private fun sourceResultsJson(results: List<BookSource.Result>): String {
        val arr = JSONArray()
        results.forEach { r ->
            arr.put(JSONObject().put("title", r.title).put("author", r.author)
                .put("url", r.downloadUrl).put("ext", r.ext)
                .put("cover", r.coverUrl.orEmpty()).put("file", r.suggestedFileName()))
        }
        return JSONObject().put("results", arr).toString()
    }

    private fun sourcesPopular(out: OutputStream, source: String?) = respondJson(out, sourceResultsJson(
        if (source == "standardebooks") BookSource.browseStandardEbooks() else BookSource.popularGutenberg()))

    private fun sourcesSearch(out: OutputStream, source: String?, q: String?) {
        if (q.isNullOrBlank()) { respond(out, 400, "query required"); return }
        respondJson(out, sourceResultsJson(
            if (source == "standardebooks") BookSource.searchStandardEbooks(q) else BookSource.searchGutenberg(q)))
    }

    private fun sourcesRepos(out: OutputStream) {
        val arr = JSONArray()
        BookSource.REPOSITORIES.forEach { arr.put(JSONObject().put("name", it.name).put("note", it.note).put("url", it.url)) }
        respondJson(out, JSONObject().put("repos", arr).toString())
    }

    private fun sourcesDownload(out: OutputStream, body: JSONObject) {
        val url = body.optString("url")
        // The glasses only fetch from the catalogs they list — a malicious page in
        // the user's browser must not be able to point them at arbitrary hosts.
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        val allowed = host == "gutenberg.org" || host.endsWith(".gutenberg.org") ||
            host == "gutendex.com" || host == "standardebooks.org" || host == "www.standardebooks.org"
        if (!url.startsWith("https://") || !allowed) { respond(out, 400, "unsupported source"); return }
        val name = body.optString("file").ifBlank { "book.${body.optString("ext", "epub")}" }
        if (!DocumentParser.isSupported(name)) { respond(out, 415, "unsupported type"); return }
        val bytes = BookSource.download(url)
        if (bytes == null || bytes.isEmpty()) { respond(out, 422, "The catalog did not return the book. Try again."); return }
        if (bytes.size.toLong() > MAX_BYTES) { respond(out, 413, "file too large"); return }
        val f = library.importFile(name, bytes)
        library.saveBookMetadata(f.name, body.optString("title").ifBlank { f.nameWithoutExtension },
            body.optString("author"), body.optString("cover").takeIf { it.isNotBlank() })
        onReceived(f.name)
        respondJson(out, JSONObject().put("ok", true).put("file", f.name).toString())
    }

    // ---- NAS / SMB (the glasses do the SMB work; browser can't) -------------

    private fun nasShares(out: OutputStream, body: JSONObject) {
        val host = body.optString("host").trim()
        if (host.isBlank()) { respond(out, 400, "host required"); return }
        runCatching {
            SmbClient.shares(host, body.optString("user").trim(), body.optString("pass"), body.optString("domain").trim())
        }.fold(
            onSuccess = { names ->
                respondJson(out, JSONObject().put("shares", JSONArray().apply { names.forEach { put(it) } }).toString())
            },
            onFailure = { respond(out, 422, "NAS error: ${(it.message ?: "connection failed").take(140)}") }
        )
    }

    private fun nasList(out: OutputStream, body: JSONObject) {
        val host = body.optString("host").trim()
        val share = body.optString("share").trim()
        if (host.isBlank() || share.isBlank()) { respond(out, 400, "host and share required"); return }
        val result = runCatching {
            SmbClient.list(host, share, body.optString("user").trim(), body.optString("pass"),
                body.optString("domain").trim(), body.optString("path").replace('/', '\\').trim('\\'))
        }
        result.fold(
            onSuccess = { entries ->
                val arr = JSONArray()
                entries.forEach { e ->
                    // Only surface folders and readable book files.
                    if (e.isDir || DocumentParser.isSupported(e.name))
                        arr.put(JSONObject().put("name", e.name).put("dir", e.isDir).put("size", e.size))
                }
                respondJson(out, JSONObject().put("entries", arr).toString())
            },
            onFailure = { respond(out, 422, "NAS error: ${(it.message ?: "connection failed").take(140)}") }
        )
    }

    private fun nasImport(out: OutputStream, body: JSONObject) {
        val host = body.optString("host").trim()
        val share = body.optString("share").trim()
        val path = body.optString("path").replace('/', '\\').trim('\\')
        val name = body.optString("name").ifBlank { path.substringAfterLast('\\') }
        if (host.isBlank() || share.isBlank() || path.isBlank()) { respond(out, 400, "host, share, path required"); return }
        if (!DocumentParser.isSupported(name)) { respond(out, 415, "unsupported type"); return }
        val result = runCatching {
            SmbClient.read(host, share, body.optString("user").trim(), body.optString("pass"),
                body.optString("domain").trim(), path)
        }
        result.fold(
            onSuccess = { bytes ->
                if (bytes.size.toLong() > MAX_BYTES) { respond(out, 413, "file too large"); return }
                val f = library.importFile(name, bytes)
                onReceived(f.name)
                respondJson(out, JSONObject().put("ok", true).put("file", f.name).toString())
            },
            onFailure = { respond(out, 422, "NAS read failed: ${(it.message ?: "").take(140)}") }
        )
    }

    private fun upload(out: OutputStream, name: String, bytes: ByteArray) {
        val isConfig = name == "__fishkey__.cfg"
        if (bytes.isEmpty()) { respond(out, 400, "empty upload"); return }
        if (!isConfig && !DocumentParser.isSupported(name)) { respond(out, 415, "unsupported type"); return }
        if (isConfig) {
            val lines = String(bytes, Charsets.UTF_8).split('\n')
            library.putString(LibraryStore.K_FISH_KEY, lines.getOrNull(0)?.trim().orEmpty())
            library.putString(LibraryStore.K_FISH_VOICE, lines.getOrNull(1)?.trim().orEmpty())
            respond(out, 200, "config saved")
        } else {
            val f = library.importFile(name, bytes)
            respond(out, 200, "saved ${f.name}")
            onReceived(f.name)
        }
    }

    private fun serveAsset(out: OutputStream, path: String) {
        val asset = when (path) { "/", "/index.html" -> "companion/index.html"; "/companion.css" -> "companion/companion.css"; else -> "companion/companion.js" }
        val type = when { asset.endsWith(".css") -> "text/css; charset=utf-8"; asset.endsWith(".js") -> "application/javascript; charset=utf-8"; else -> "text/html; charset=utf-8" }
        val bytes = runCatching { context.assets.open(asset).use { it.readBytes() } }.getOrNull()
        if (bytes == null) respond(out, 404, "companion asset missing") else writeBytes(out, 200, type, bytes)
    }

    private fun query(path: String, name: String): String? = Regex("[?&]${Regex.escape(name)}=([^&]+)").find(path)?.groupValues?.get(1)
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    private fun json(bytes: ByteArray): JSONObject = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrDefault(JSONObject())
    private fun masked(value: String): String = if (value.isBlank()) "Not set" else "********"
    private fun readExactly(input: BufferedInputStream, count: Int): ByteArray {
        val bytes = ByteArray(count); var read = 0
        while (read < count) { val n = input.read(bytes, read, count - read); if (n < 0) break; read += n }
        return if (read == count) bytes else bytes.copyOf(read)
    }
    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) { val c = input.read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString(); if (c == '\n'.code) return sb.toString().trimEnd('\r'); sb.append(c.toChar()); if (sb.length > 8192) return sb.toString() }
    }
    private fun respond(out: OutputStream, code: Int, body: String) = writeBytes(out, code, "text/plain; charset=utf-8", body.toByteArray())
    private fun respondJson(out: OutputStream, json: String) = writeBytes(out, 200, "application/json; charset=utf-8", json.toByteArray())
    private fun writeBytes(out: OutputStream, code: Int, type: String, payload: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; 422 -> "Unprocessable Content"; else -> "Error" }
        // No CORS headers on purpose: the companion is served by this same origin,
        // so it needs none — and a wildcard would let any website the user visits
        // POST/DELETE to the glasses (delete books, overwrite keys). Cross-origin
        // requests are therefore blocked by the browser, which is what we want.
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nContent-Length: ${payload.size}\r\n\r\n").toByteArray())
        out.write(payload); out.flush()
    }
}
