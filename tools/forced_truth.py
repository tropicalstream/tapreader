"""
Precise ground-truth word timings for the live-test clips, by CTC forced
alignment (wav2vec2-base-960h, 20 ms frames). Whisper's word timestamps are
only good to a few hundred ms — too coarse to judge a word highlight — whereas
forced alignment against the known text lands within a frame or two.

Writes app/build/livetest/<book>/truth.json: {"u000.wav": [[start_ms, end_ms] | null, ...]},
one entry per book word (null where a word has no letters to align, e.g. "1888").

  <python with torch + transformers> tools/forced_truth.py [book ...]
"""
import json, os, re, subprocess, sys, tempfile
import numpy as np
import torch
from transformers import Wav2Vec2ForCTC, Wav2Vec2Processor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app/build/livetest')
NAME = 'facebook/wav2vec2-base-960h'

proc = Wav2Vec2Processor.from_pretrained(NAME)
model = Wav2Vec2ForCTC.from_pretrained(NAME).eval()
vocab = proc.tokenizer.get_vocab()
BLANK = vocab['<pad>']; SEP = vocab['|']

def load16k(path):
    with tempfile.NamedTemporaryFile(suffix='.raw') as t:
        subprocess.run(['ffmpeg', '-v', 'quiet', '-y', '-i', path, '-ac', '1', '-ar', '16000', '-f', 's16le', t.name], check=True)
        return np.frombuffer(open(t.name, 'rb').read(), dtype=np.int16).astype(np.float32) / 32768.0

def align(emission, tokens):
    """Viterbi CTC alignment; returns the frame index at which each token is emitted (first frame)."""
    T, N = emission.shape[0], len(tokens)
    # Extended sequence with blanks: b t0 b t1 b ... tN-1 b
    ext = [BLANK]
    for t in tokens: ext += [t, BLANK]
    S = len(ext)
    NEG = -1e30
    dp = np.full((T, S), NEG, dtype=np.float64)
    bp = np.zeros((T, S), dtype=np.int32)
    dp[0, 0] = emission[0, ext[0]]
    if S > 1: dp[0, 1] = emission[0, ext[1]]
    for t in range(1, T):
        prev = dp[t - 1]
        stay = prev
        step = np.concatenate(([NEG], prev[:-1]))
        skip = np.concatenate(([NEG, NEG], prev[:-2]))
        allow_skip = np.array([s >= 2 and ext[s] != BLANK and ext[s] != ext[s - 2] for s in range(S)])
        skip = np.where(allow_skip, skip, NEG)
        cand = np.stack([stay, step, skip])
        arg = cand.argmax(0)
        dp[t] = cand.max(0) + emission[t, ext]
        bp[t] = arg
    s = S - 1 if dp[T - 1, S - 1] >= dp[T - 1, S - 2] else S - 2
    path = [0] * T
    for t in range(T - 1, -1, -1):
        path[t] = s
        s -= bp[t, s]
    first = [None] * N; last = [None] * N
    for t, s in enumerate(path):
        if s % 2 == 1:
            k = s // 2
            if first[k] is None: first[k] = t
            last[k] = t
    return first, last

def main():
    books = sys.argv[1:] or sorted(x for x in os.listdir(OUT) if os.path.isfile(os.path.join(OUT, x, 'report.json')))
    for bk in books:
        d = os.path.join(OUT, bk)
        rep = json.load(open(os.path.join(d, 'report.json')))
        truth, got, total = {}, 0, 0
        for u in rep['utterances']:
            audio = load16k(os.path.join(d, u['wav']))
            with torch.no_grad():
                logits = model(torch.tensor(audio)[None]).logits[0]
            em = torch.log_softmax(logits, -1).numpy()
            frame_ms = 1000.0 * len(audio) / 16000 / em.shape[0]
            tokens, owner = [], []           # owner[k] = word index of token k
            for wi, w in enumerate(u['words']):
                letters = re.sub(r"[^A-Z']", '', w.upper().replace('’', "'")).strip("'")
                if not letters: continue
                if tokens: tokens.append(SEP); owner.append(-1)
                for ch in letters:
                    tokens.append(vocab[ch]); owner.append(wi)
            row = [None] * len(u['words'])
            if tokens and em.shape[0] > len(tokens):
                first, last = align(em, tokens)
                for k, wi in enumerate(owner):
                    if wi < 0 or first[k] is None: continue
                    s, e = first[k] * frame_ms, (last[k] + 1) * frame_ms
                    if row[wi] is None: row[wi] = [round(s), round(e)]
                    else: row[wi][1] = round(e)
            truth[u['wav']] = row
            got += sum(x is not None for x in row); total += len(row)
        json.dump(truth, open(os.path.join(d, 'truth.json'), 'w'))
        print(f"{bk}: aligned {got}/{total} words", flush=True)

main()
