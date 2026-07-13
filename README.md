# TapReader

**A professional document & ebook reader with text-to-speech, built for AR smart glasses** (RayNeo X3 Pro), with a **local web companion** for managing your library from any phone or computer on the same Wi-Fi.

No cloud, no account, no telemetry. Your books and API keys live on the glasses; the companion is just a browser window into them.

---

## Reader app (glasses)

**Formats:** `.txt`, `.md`, `.epub`, `.pdf`, `.html`, `.xhtml`, `.fb2`, `.rtf`, `.docx`, and other UTF-8 text. PDFs are text-extracted; EPUB/FB2/DOCX are parsed natively with real chapter boundaries (EPUB honors the book's own OPF spine order; DOCX chapters come from Word's Heading styles, with the rendered table-of-contents page automatically skipped).

**Three reading modes** (switch any time — your place is kept):
- **Page** — a clean page of text; the word being read is highlighted and the page turns itself.
- **Auto-scroll** — text glides upward at your pace, highlight following along.
- **One word at a time (RSVP)** — a single word centered with the optimal-recognition-point letter tinted, for fast focused reading. Adjustable words-per-minute.

**Text-to-speech (fish.audio)** with **word-accurate highlighting** — the spoken word lights up in lockstep with narration. Text is sanitized to characters fish.audio can voice cleanly (stray punctuation used to send some books into a loop of garbled audio); overly-fragmented paragraphs from poorly-formatted files are coalesced so narration doesn't stutter mid-sentence.

**AI reading coach** (optional, needs your own Gemini key) — a spoiler-safe recap of what you've actually read plus forward-looking encouragement, or, for a book you haven't started, tips on how to approach it. An on-demand section summary can also be spoken aloud via fish.audio.

**Easy on the eyes:** black background, three themes — warm Amber, White, and Green (chosen for how AR waveguides render color, not the paper-book "sepia" concept, which doesn't translate to a transparent display) — large adjustable serif type, no glare, no clutter.

**Reading encouragement:** daily word goal, day-streak tracking, and finish celebrations on the library screen.

**Get free books in-app:** search **Project Gutenberg** (75k+ public-domain books) and download with one tap. Quick links to Standard Ebooks, Open Library, Internet Archive, LibriVox, Libby/OverDrive, Hoopla and more.

**Gestures:**
- single tap — play/pause (or activate the item under the cursor)
- double tap — reader control bar (swipe the pad to move the highlight, tap to activate) / library
- triple tap — settings
- pull left edge — back (saves your place)
- pull right edge — cycle reading mode
- top/bottom edge — scrub / scroll

## Web companion

With TapReader open on the glasses and your phone or computer on the same Wi-Fi, open the address shown in the glasses' Settings:

```
http://<glasses-ip>:8787
```

The companion is served directly by the glasses — there's no separate app to install and nothing goes through a third-party server.

- **Library** — cover gallery with progress, table of contents, a spoiler-safe summary sourced from Wikipedia/Open Library (not an LLM), and a cover picker if the auto-matched art is wrong.
- **Get free books** — search or browse popular titles from the same computer you're already using; the glasses do the actual download.
- **Reading coach** — the same Gemini-powered recap/encouragement as the glasses, from a bigger screen.
- **Narration voices** — search the fish.audio voice library, preview any voice, and keep up to five saved.
- **Import from NAS** — browse an SMB share (enter the host once, the glasses enumerate its folders) and copy books straight over.
- **Settings** — reader preferences and API keys. A saved key is never redisplayed once entered — the field shows only that a key is set — and "Save & test" validates a new key live before committing it, so a bad paste can't clobber a working key.
- **Removed books aren't gone** — deleting a book from either the glasses or the companion archives it (progress kept) instead of destroying it; it shows up in a distinct "Off the glasses" section with one-tap restore.

## Building

Requirements: Android Studio / Android SDK, JDK 17. Toolchain: AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 29.

1. Create `local.properties` with `sdk.dir=/path/to/Android/sdk`.
2. Build and install:

   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

No API keys are needed to build or install the app. TTS (fish.audio), the reading coach (Gemini), and radio-style voice search all need your own free-tier keys, entered on-device — nothing is bundled or required to read a book.

## Credits

- **[fish.audio](https://fish.audio/)** — text-to-speech narration.
- **[Google Gemini](https://ai.google.dev/)** — the optional reading coach.
- **[Project Gutenberg](https://www.gutenberg.org/)** / **[Gutendex](https://gutendex.com/)**, **[Standard Ebooks](https://standardebooks.org/)**, **[Open Library](https://openlibrary.org/)** — free public-domain books and cover/summary data.
- **[PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)**, **[smbj](https://github.com/hierynomus/smbj)** — PDF text extraction and SMB access.

TapReader is a personal, non-commercial project.
