package com.tapreader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A book on the shelf: file + reading progress + last-opened. */
data class Shelf(
    val fileName: String,
    val title: String,
    val author: String,
    val wordIndex: Int,
    val wordsRead: Int,
    val wordCount: Int,
    val lastOpened: Long,
    val coverUrl: String?
) {
    /**
     * This is deliberately based on words that the reader actually focused while
     * playing, rather than the current navigation cursor. A chapter jump should
     * never make a book appear read.
     */
    val percent: Int get() = if (wordCount > 0) (wordsRead * 100 / wordCount).coerceIn(0, 100) else 0
}

/** A saved fish.audio voice. The selected ID remains in K_FISH_VOICE. */
data class VoicePreset(val id: String, val name: String)

/**
 * Persistent library: imported book files live in filesDir/books; reading
 * progress and streak stats live in SharedPreferences. Also tracks the daily
 * reading streak that powers the encouragement banner.
 */
class LibraryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("tapreader", Context.MODE_PRIVATE)
    val booksDir: File = File(context.filesDir, "books").apply { mkdirs() }
    // "Deleted" books land here instead of being destroyed: the file and its
    // progress survive so the companion can restore them to the glasses later.
    val archiveDir: File = File(context.filesDir, "archive").apply { mkdirs() }

    fun listFiles(): List<File> =
        booksDir.listFiles()?.filter { it.isFile && DocumentParser.isSupported(it.name) }
            ?.sortedByDescending { lastOpened(it.name) } ?: emptyList()

    fun shelf(): List<Shelf> = listFiles().map(::toShelf)

    fun archivedShelf(): List<Shelf> =
        archiveDir.listFiles()?.filter { it.isFile && DocumentParser.isSupported(it.name) }
            ?.sortedByDescending { lastOpened(it.name) }?.map(::toShelf) ?: emptyList()

    private fun toShelf(f: File): Shelf {
        val o = progress(f.name)
        return Shelf(
            fileName = f.name,
            title = o?.optString("title").orEmpty().ifBlank { f.nameWithoutExtension.replace('_', ' ') },
            author = o?.optString("author").orEmpty(),
            wordIndex = o?.optInt("word", 0) ?: 0,
            // Older versions only stored a navigation position, which may have
            // come from a chapter/page jump. It is intentionally not treated as
            // reading history; the new counter begins with actual focused words.
            wordsRead = o?.optInt("read", 0) ?: 0,
            wordCount = o?.optInt("count", 0) ?: 0,
            lastOpened = o?.optLong("last", 0) ?: 0,
            coverUrl = o?.optString("cover", "")?.takeIf { it.isNotBlank() }
        )
    }

    fun importFile(name: String, bytes: ByteArray): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val f = File(booksDir, safe)
        f.writeBytes(bytes)
        // Re-importing a book supersedes any archived copy of the same file.
        File(archiveDir, safe).delete()
        return f
    }

    /** "Delete" from the library: the file moves to the archive, progress stays. */
    fun archive(fileName: String) {
        val src = File(booksDir, fileName)
        if (src.exists()) src.renameTo(File(archiveDir, fileName))
    }

    /** Bring an archived book back onto the glasses. */
    fun restore(fileName: String): Boolean {
        val src = File(archiveDir, fileName)
        return src.exists() && src.renameTo(File(booksDir, fileName))
    }

    /** Permanently delete an archived book and its progress. Not undoable. */
    fun purge(fileName: String) {
        File(archiveDir, fileName).delete()
        File(booksDir, fileName).delete()
        prefs.edit().remove("prog_$fileName").apply()
    }

    private fun progress(fileName: String): JSONObject? =
        prefs.getString("prog_$fileName", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun lastOpened(fileName: String): Long = progress(fileName)?.optLong("last", 0) ?: 0

    fun savedWordIndex(fileName: String): Int = progress(fileName)?.optInt("word", 0) ?: 0

    fun saveProgress(book: Book, fileName: String, wordIndex: Int, wordsRead: Int) {
        val o = progress(fileName) ?: JSONObject()
        o.put("title", book.title).put("author", book.author)
            .put("word", wordIndex.coerceIn(0, book.wordCount))
            .put("read", wordsRead.coerceIn(0, book.wordCount))
            .put("count", book.wordCount).put("last", System.currentTimeMillis())
        prefs.edit().putString("prog_$fileName", o.toString()).apply()
    }

    /** Reset a book back to the beginning (reading position + words-read → 0). */
    fun resetProgress(fileName: String) {
        val o = progress(fileName) ?: return
        o.put("word", 0).put("read", 0)
        prefs.edit().putString("prog_$fileName", o.toString()).apply()
    }

    /** Keep catalog metadata so a downloaded book can retain its source cover. */
    fun saveBookMetadata(fileName: String, title: String, author: String, coverUrl: String?) {
        val o = progress(fileName) ?: JSONObject()
        o.put("title", title).put("author", author)
        if (!coverUrl.isNullOrBlank()) o.put("cover", coverUrl)
        prefs.edit().putString("prog_$fileName", o.toString()).apply()
    }

    // ---- Settings ----------------------------------------------------------

    fun getString(key: String, def: String): String = prefs.getString(key, def) ?: def
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    fun getBool(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    fun putBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    fun voicePresets(): List<VoicePreset> {
        val raw = prefs.getString(K_VOICE_PRESETS, "[]") ?: "[]"
        val saved = runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { index ->
                val o = arr.getJSONObject(index)
                VoicePreset(o.optString("id"), o.optString("name").ifBlank { "Saved voice ${index + 1}" })
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
        if (saved.isNotEmpty()) return saved.take(5)
        val selected = getString(K_FISH_VOICE, TtsReader.DEFAULT_VOICE).ifBlank { TtsReader.DEFAULT_VOICE }
        return listOf(VoicePreset(selected, "Default narrator"))
    }

    fun saveVoicePreset(name: String, id: String): List<VoicePreset> {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return voicePresets()
        val updated = voicePresets().filterNot { it.id == cleanId }.toMutableList()
        updated.add(0, VoicePreset(cleanId, name.trim().ifBlank { "Saved voice" }))
        val final = updated.take(5)
        saveVoicePresets(final)
        return final
    }

    fun deleteVoicePreset(id: String): List<VoicePreset> {
        val final = voicePresets().filterNot { it.id == id }
        saveVoicePresets(final)
        if (getString(K_FISH_VOICE, "") == id) putString(K_FISH_VOICE, final.firstOrNull()?.id.orEmpty())
        return final
    }

    private fun saveVoicePresets(presets: List<VoicePreset>) {
        val arr = JSONArray()
        presets.take(5).forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs.edit().putString(K_VOICE_PRESETS, arr.toString()).apply()
    }

    // ---- Reading streak / encouragement -----------------------------------

    /** Words read counter for today + running day streak. Call when words advance. */
    fun recordWordsRead(delta: Int) {
        if (delta <= 0) return
        val today = todayStamp()
        val lastDay = prefs.getInt("streak_day", 0)
        var streak = prefs.getInt("streak_len", 0)
        var todayWords = prefs.getInt("streak_words", 0)
        if (lastDay != today) {
            streak = if (lastDay == today - 1) streak + 1 else 1
            todayWords = 0
        }
        todayWords += delta
        prefs.edit()
            .putInt("streak_day", today)
            .putInt("streak_len", streak.coerceAtLeast(1))
            .putInt("streak_words", todayWords)
            .putInt("total_words", prefs.getInt("total_words", 0) + delta)
            .apply()
    }

    data class Streak(val days: Int, val todayWords: Int, val totalWords: Int, val goalWords: Int) {
        val goalMet: Boolean get() = todayWords >= goalWords
        val goalPercent: Int get() = if (goalWords > 0) (todayWords * 100 / goalWords).coerceIn(0, 100) else 0
    }

    fun streak(): Streak {
        val today = todayStamp()
        val lastDay = prefs.getInt("streak_day", 0)
        val days = if (lastDay == today || lastDay == today - 1) prefs.getInt("streak_len", 0) else 0
        val todayWords = if (lastDay == today) prefs.getInt("streak_words", 0) else 0
        return Streak(days, todayWords, prefs.getInt("total_words", 0), getInt("daily_goal", 2000))
    }

    private fun todayStamp(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()

    companion object {
        // Settings keys.
        const val K_FISH_KEY = "fish_api_key"
        const val K_FISH_VOICE = "fish_voice_id"
        const val K_MODE = "reader_mode"          // 0 paged, 1 autoscroll, 2 rsvp
        const val K_FONT_SP = "font_sp"
        const val K_WPM = "rsvp_wpm"
        const val K_SCROLL_SPEED = "scroll_speed"
        const val K_THEME = "theme"               // 0 amber-dark, 1 white-dark, 2 sepia-dim
        const val K_DAILY_GOAL = "daily_goal"
        const val K_FOCUS_MODE = "focus_mode"     // 0 comfort band, 1 dead center
        const val K_LLM_PROVIDER = "llm_provider" // summary AI provider id
        const val K_TOP_HUD = "top_hud_enabled"
        const val K_VOICE_PRESETS = "voice_presets"
    }
}
