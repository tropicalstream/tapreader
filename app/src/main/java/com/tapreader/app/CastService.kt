package com.tapreader.app

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web companion's view of a book's cast: who has which fish.audio voice,
 * and swapping a character to another voice. Edits go to the live [Cast] when
 * that book is being narrated (so the next line already uses the new voice),
 * else to its file.
 */
class CastService(
    private val context: Context,
    private val library: LibraryStore,
    private val liveCast: (bookId: String) -> Cast? = { null }
) {
    private val castDir = File(context.filesDir, "casts")
    private val titles = ConcurrentHashMap<String, String>()

    private fun fish(): FishSpeech? = library.getString(LibraryStore.K_FISH_KEY, "").trim().takeIf { it.isNotBlank() }?.let { FishSpeech(it) }
    private fun gemini(): GeminiText? = library.getString(LibraryStore.K_GEMINI_KEY, "").trim().takeIf { it.isNotBlank() }?.let { GeminiText(it) }

    private fun director(): CastDirector? {
        val f = fish() ?: return null
        val g = gemini() ?: return null
        return CastDirector(g, f, castDir, library.getString(LibraryStore.K_FISH_VOICE, TtsReader.DEFAULT_VOICE))
    }

    private fun castFor(book: Book, d: CastDirector): Pair<Cast, Boolean> =
        liveCast(book.id)?.let { it to true } ?: (d.load(book) to false)

    fun castJson(book: Book): JSONObject {
        val d = director() ?: throw IllegalStateException("Character voices need both a fish.audio key and a Gemini key")
        val (cast, live) = castFor(book, d)
        val f = fish()
        fun title(id: String) = titles.getOrPut(id) { f?.voice(id)?.title ?: id }
        fun role(r: Role, label: String) = JSONObject(r.toJson().toString()).put("label", label).put("voiceName", if (r.voice.isBlank()) "" else title(r.voice))
        return synchronized(cast) {
            JSONObject().put("ready", cast.ready).put("live", live).put("person", cast.person).put("language", cast.language)
                .put("narrator", role(cast.narrator, "Narrator"))
                .put("roles", JSONArray().also { a ->
                    cast.roles.values.filter { it.voiceSource != "narrator" }.sortedByDescending { it.lines }.forEach { a.put(role(it, it.name)) }
                    cast.roles.values.filter { it.voiceSource == "narrator" }.forEach { a.put(role(it, "${it.name} (narrator)")) }
                })
        }
    }

    fun setVoice(book: Book, roleName: String, voice: String) {
        val d = director() ?: throw IllegalStateException("Character voices need both a fish.audio key and a Gemini key")
        val (cast, _) = castFor(book, d)
        synchronized(cast) {
            val r = cast.role(roleName) ?: throw IllegalArgumentException("No such character")
            r.voice = voice; r.voiceSource = "user"
            if (r === cast.narrator) cast.roles.values.filter { it.voiceSource == "narrator" }.forEach { it.voice = voice }
        }
        d.save(cast)
    }

    /** Re-auditions one character (their current voice excluded). Takes ~20 s. */
    fun recast(book: Book, roleName: String) {
        val d = director() ?: throw IllegalStateException("Character voices need both a fish.audio key and a Gemini key")
        val (cast, _) = castFor(book, d)
        d.recast(cast, roleName)
    }

    fun reset(book: Book) = CastDirector.forgetInBackground(castDir, book.id)
}
