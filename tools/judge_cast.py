#!/usr/bin/env python3
"""
Independent checks of a live-test run (app/build/livetest/<book>/report.json):

  voices       For each speaker, Gemini listens to their longest clip (without
               being told who it is) and describes the voice; a second call
               scores 1-5 how well that voice fits the cast's description of
               the character (gender, age, accent, manner).
  attribution  A Pro model reads the passage with every spoken line numbered
               and the app's speaker for each, and lists any it believes wrong.

Needs .gemini_key at the repo root.   python3 tools/judge_cast.py [book ...]
"""
import base64, json, os, sys, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app/build/livetest')
KEY = open(next(p for p in (os.path.join(ROOT, '.gemini_key'), os.path.join(ROOT, '../tapreader-gemini/.gemini_key')) if os.path.exists(p))).read().strip()
LISTENER = 'gemini-3.8-flash'
AUDITOR = 'gemini-3.8-flash'

def gen(model, parts, as_json=True):
    body = {"contents": [{"role": "user", "parts": parts}], "generationConfig": {"temperature": 0}}
    if as_json: body["generationConfig"]["responseMimeType"] = "application/json"
    req = urllib.request.Request(f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps(body).encode(), headers={"x-goog-api-key": KEY, "Content-Type": "application/json"})
    js = json.loads(urllib.request.urlopen(req, timeout=600).read())
    txt = "".join(p.get("text", "") for p in js["candidates"][0]["content"]["parts"] if not p.get("thought"))
    return json.loads(txt) if as_json else txt

def judge(book):
    d = os.path.join(OUT, book)
    rep = json.load(open(os.path.join(d, 'report.json')))
    cast = rep['cast']
    roles = {r['name']: r for r in cast['roles']}
    roles['NARRATOR'] = cast['narrator']
    print(f"\n== {rep['title']}")
    # ---- voices
    by = {}
    for u in rep['utterances']:
        if u['durMs'] > by.get(u['speaker'], {}).get('durMs', 0): by[u['speaker']] = u
    scores = []
    for who, u in by.items():
        r = roles.get(who, cast['narrator'])
        audio = base64.b64encode(open(os.path.join(d, u['wav']), 'rb').read()).decode()
        heard = gen(LISTENER, [{"inline_data": {"mime_type": "audio/wav", "data": audio}},
            {"text": 'Describe only the speaker\'s voice. JSON: {"gender":"","apparent_age":"","accent":"","qualities":""}'}])
        verdict = gen(LISTENER, [{"text": f"""A voice was cast for this audiobook character.
CHARACTER: {who} in "{rep['title']}" — gender {r.get('gender')}, age {r.get('age')}, accent {r.get('accent')}. {r.get('sketch','')}
VOICE AS HEARD (by a listener who did not know the character): {json.dumps(heard)}
Score how well the voice fits the character, 1 (wrong gender/age/accent) to 5 (convincing). JSON: {{"score": n, "why": "one short sentence"}}"""}])
        scores.append(verdict['score'])
        print(f"  voice {who[:26]:<26} [{r.get('voiceSource')}] {verdict['score']}/5  heard: {heard.get('gender')}, {heard.get('apparent_age')}, {heard.get('accent')} — {verdict['why'][:110]}")
    # ---- attribution
    lines = [u for u in rep['utterances'] if u['quoteId'] >= 0]
    passage = "\n".join(f"[{'L%d' % i if u['quoteId'] >= 0 else 'narration'}] {u['text']}" for i, u in enumerate(rep['utterances']))
    table = "\n".join(f"L{rep['utterances'].index(u)}: {u['speaker']}" for u in lines)
    audit = gen(AUDITOR, [{"text": f"""Passage from "{rep['title']}" as split for an audiobook (quoted lines are tagged L#; narration is untagged):
{passage}

The app attributed each quoted line to a speaker ("NARRATOR" = read by the narrator, e.g. a quoted title or phrase; in first-person books the narrating character may be named):
{table}

Using the passage and your knowledge of the book, list every attribution that is wrong. JSON: {{"wrong":[{{"line":"L#","app":"","correct":"","why":""}}]}}"""}])
    wrong = audit.get('wrong', [])
    print(f"  attribution: {len(lines) - len(wrong)}/{len(lines)} lines judged correct")
    for w in wrong: print(f"    ✗ {w['line']}: app said {w['app']}, should be {w['correct']} — {w['why'][:100]}")
    return scores, len(lines), len(wrong)

if __name__ == '__main__':
    books = sys.argv[1:] or sorted(x for x in os.listdir(OUT) if os.path.isfile(os.path.join(OUT, x, 'report.json')))
    S, L, W = [], 0, 0
    for b in books:
        s, l, w = judge(b); S += s; L += l; W += w
    print(f"\nvoice fit mean {sum(S)/max(1,len(S)):.2f}/5 over {len(S)} voices · attribution {L-W}/{L} lines correct")
