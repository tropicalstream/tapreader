# TapReader

TapReader is a document and ebook reader with text-to-speech narration, built for the RayNeo X3 Pro AR smart glasses, paired with a local web companion for managing a library from any phone or computer on the same Wi-Fi. It reads plain text, EPUB, PDF, DOCX, and several other formats natively, with three switchable reading modes — a self-turning page, an auto-scrolling stream, and a one-word-at-a-time speed-reading view — and narration that highlights each spoken word in lockstep with the audio. An optional AI reading coach gives a spoiler-safe recap of what has been read so far, and free public-domain books can be found and downloaded in-app from sources like Project Gutenberg. Everything runs without a cloud account: books and any API keys the user supplies stay on the glasses, and the web companion is served directly from the device rather than through a third party.

## Character voices

Narration is a full cast. With a Gemini key saved alongside the fish.audio key, TapReader reads a little ahead of you and works out who speaks every line — from dialogue tags, turn-taking and forms of address, including first-person narrators and diary or letter writers — then gives each character a fish.audio voice that suits them: gender, age, accent and manner. The narrator and leading characters are auditioned by ear: each shortlisted voice performs two contrasting lines in the character's own delivery, and Gemini listens for fit and rules out flat or mechanical readings. Lines carry their delivery ("teasing", "whispering, anxious") and foreign accents into the voice. Speech uses fish.audio's free S2.1 Pro model (`s2.1-pro-free`).

Casts are saved per book, and only the passage about to be read aloud is ever sent for casting — never the whole book. From the web companion's Characters list you can preview any voice, pick a different one from the fish.audio library, or have a character re-auditioned. Settings can keep your own chosen voice as narrator, or turn character voices off.

The word highlight is timed from the audio itself — silences and energy dips matched to word boundaries — so it follows the voice rather than an even spread, and lines play back to back without gaps.

## Chapters

Chapter lists come from each book's own table of contents, with page markers and picture captions filtered out; books without one fall back to the chapter headings in the text. EPUBs that break every printed line into its own paragraph are rejoined into real paragraphs, and quotes are kept whole across those breaks.

## Demo

[![Tap ebook reader](https://i.ytimg.com/vi/eFKBDk4psL4/hqdefault.jpg)](https://youtu.be/eFKBDk4psL4)

## Controls

- Single tap — play/pause, or activate the item under the cursor
- Double tap — open the reader control bar or the library
- Triple tap — open settings
- Pull left edge — go back (saves your place)
- Pull right edge — cycle reading mode
- Top/bottom edge — scrub or scroll

## Download

[TapReader.apk](TapReader.apk)
