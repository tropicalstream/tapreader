(() => {
  const state = { base: localStorage.getItem('tapreader-glasses-url') || (location.protocol.startsWith('http') ? location.origin : ''), data: null, filter: 'all', selectedBook: null, voiceResults: null, coverFile: null, coverBust: {}, nas: { host: '', share: '', user: '', pass: '', path: '', entries: [], shares: [] } };
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const notice = (text, error = false) => { const el = $('#notice'); el.textContent = text; el.hidden = !text; el.classList.toggle('error', error); };
  const base = () => state.base.replace(/\/$/, '');
  const api = async (path, options = {}) => {
    if (!base()) throw new Error('Enter the glasses address first.');
    const res = await fetch(base() + path, { ...options, headers: { 'Content-Type': 'application/json', ...(options.headers || {}) } });
    if (!res.ok) throw new Error((await res.text()) || `Request failed (${res.status})`);
    return res;
  };
  const requestJson = async (path, options) => (await api(path, options)).json();
  const encode = (value) => encodeURIComponent(value);
  const escapeHtml = (v = '') => String(v).replace(/[&<>"']/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));

  function switchView(view) {
    $$('.nav-item').forEach(b => b.classList.toggle('active', b.dataset.view === view));
    $$('.view').forEach(v => v.classList.toggle('active', v.id === `${view}-view`));
    $('#page-title').textContent = ({library:'Your library', getbooks:'Get free books', coach:'Reading coach', voices:'Narration voices', nas:'Import from NAS', settings:'Glasses settings'})[view];
    if (view === 'getbooks' && !state.sourcesLoaded) { state.sourcesLoaded = true; sourceSearch(); loadRepos(); }
  }
  function renderMetrics() {
    const s = state.data.streak;
    $('#metrics').innerHTML = [
      ['' + s.days, 'day streak'], [s.todayWords.toLocaleString(), 'words today'],
      [s.totalWords.toLocaleString(), 'words read'], [s.goalPercent + '%', 'daily goal']
    ].map(([v, l]) => `<div class="metric"><strong>${v}</strong><span>${l}</span></div>`).join('');
  }
  function renderBooks() {
    const term = $('#book-search').value.trim().toLowerCase();
    const books = state.data.books.filter(book => {
      const searchable = `${book.title} ${book.author}`.toLowerCase().includes(term);
      const category = state.filter === 'all' || (state.filter === 'reading' && book.percent > 0 && book.percent < 100) || (state.filter === 'unread' && book.percent === 0) || (state.filter === 'finished' && book.percent >= 100);
      return searchable && category;
    });
    $('#book-grid').innerHTML = books.length ? books.map(book => {
      const initial = escapeHtml((book.title || '?').slice(0, 2).toUpperCase());
      const bust = state.coverBust[book.file] ? `&t=${state.coverBust[book.file]}` : '';
      const cover = `${base()}${book.coverUrl || `/api/v1/cover?file=${encode(book.file)}`}${bust}`;
      const f = escapeHtml(book.file);
      return `<article class="book-card"><div class="book-cover"><img src="${cover}" alt="Cover of ${escapeHtml(book.title)}" onerror="this.remove()"><span>${initial}</span></div><div class="book-body"><div class="book-title" title="${escapeHtml(book.title)}">${escapeHtml(book.title)}</div><div class="book-author">${escapeHtml(book.author || 'Unknown author')}</div><div class="progress-line"><span>${book.percent}% read</span><span>${(book.wordsRead || 0).toLocaleString()} words</span></div><div class="progress"><i style="width:${book.percent}%"></i></div><div class="book-actions"><button data-coach="${f}">Coach</button><button data-toc="${f}">Contents</button><button data-summary="${f}">Summary</button><button data-cover="${f}">New cover</button><button data-reset="${f}">Reset</button><button class="danger" data-delete="${f}">Delete</button></div></div></article>`;
    }).join('') : '<p class="empty">No books match this view.</p>';
    renderArchived();
  }
  function renderArchived() {
    const archived = state.data.archived || [];
    $('#archived-block').hidden = !archived.length;
    if (!archived.length) return;
    $('#archived-grid').innerHTML = archived.map(book => {
      const initial = escapeHtml((book.title || '?').slice(0, 2).toUpperCase());
      const cover = `${base()}${book.coverUrl || `/api/v1/cover?file=${encode(book.file)}`}`;
      const f = escapeHtml(book.file);
      const progress = book.percent > 0 ? `${book.percent}% read — picks up where you left off` : 'Unread';
      return `<article class="book-card archived-card"><div class="book-cover"><span class="off-badge">Not on glasses</span><img src="${cover}" alt="Cover of ${escapeHtml(book.title)}" onerror="this.remove()"><span>${initial}</span></div><div class="book-body"><div class="book-title" title="${escapeHtml(book.title)}">${escapeHtml(book.title)}</div><div class="book-author">${escapeHtml(book.author || 'Unknown author')}</div><div class="progress-line"><span>${progress}</span></div><div class="book-actions"><button class="restore" data-restore="${f}">Restore to glasses</button><button class="danger" data-purge="${f}">Delete forever</button></div></div></article>`;
    }).join('');
  }
  function renderCoach() {
    const books = state.data.books;
    if (!state.selectedBook && books.length) state.selectedBook = books[0].file;
    $('#coach-books').innerHTML = books.map(b => `<button class="coach-book ${b.file === state.selectedBook ? 'active':''}" data-coach-select="${escapeHtml(b.file)}"><span>${escapeHtml(b.title)}<br><small>${escapeHtml(b.author || 'Unknown author')}</small></span><small>${b.percent}%</small></button>`).join('') || '<p class="empty">Add a book first.</p>';
    const selected = books.find(b => b.file === state.selectedBook);
    if (selected) $('#coach-output').innerHTML = `<h2>${escapeHtml(selected.title)}</h2><p>Gemini will receive only the text through your current reading position. It will return a recap of what you have read and spoiler-safe encouragement about themes and ideas still to explore.</p><button class="coach-button" id="ask-coach">Create reading note</button>`;
  }
  function renderVoices() {
    const settings = state.data.settings;
    const voices = settings.voices;
    $('#voice-count').textContent = `${voices.length} of 5 saved`;
    $('#voice-list').innerHTML = voices.length ? voices.map(v => `<label class="voice-row"><input type="radio" name="voice" value="${escapeHtml(v.id)}" ${v.id === settings.selectedVoice ? 'checked':''}><span class="voice-meta"><strong>${escapeHtml(v.name)}</strong><span>${escapeHtml(v.id)}</span></span><button type="button" data-preview="${escapeHtml(v.id)}">Preview</button><button type="button" class="delete-voice" data-delete-voice="${escapeHtml(v.id)}">Delete</button></label>`).join('') : '<p class="empty">No voices saved yet. Search below to add one.</p>';
    renderVoiceResults();
  }
  function renderVoiceResults() {
    const el = $('#voice-results'), results = state.voiceResults;
    if (results == null) return; // no search yet — keep the placeholder text
    if (!results.length) { el.innerHTML = '<p class="empty">No voices matched. Try another keyword.</p>'; return; }
    const saved = new Set((state.data?.settings.voices || []).map(v => v.id));
    const full = saved.size >= 5;
    el.innerHTML = results.map(r => {
      const meta = [ (r.languages || []).join(', '), (r.tags || []).slice(0, 3).join(' · ') ].filter(Boolean).join(' — ');
      const stats = `♥ ${(r.likes || 0).toLocaleString()} · ${(r.uses || 0).toLocaleString()} uses`;
      const isSaved = saved.has(r.id);
      const disabled = isSaved || full ? 'disabled' : '';
      return `<article class="voice-hit"><div class="voice-hit-main"><strong>${escapeHtml(r.title)}</strong><span class="voice-hit-by">${escapeHtml(r.author || 'fish.audio')}</span>${meta ? `<span class="voice-hit-tags">${escapeHtml(meta)}</span>` : ''}<span class="voice-hit-stats">${escapeHtml(stats)}</span></div><div class="voice-hit-actions"><button type="button" data-preview="${escapeHtml(r.id)}">Preview</button><button type="button" class="add-hit" data-add-voice="${escapeHtml(r.id)}" data-add-name="${escapeHtml(r.title)}" ${disabled}>${isSaved ? 'Saved' : full ? 'Full' : 'Add'}</button></div></article>`;
    }).join('');
  }
  function fillSettings() {
    const form = $('#settings-form'), s = state.data.settings, r = s.reader;
    form.deviceName.value = s.deviceName; form.fontSp.value = r.fontSp; form.wpm.value = r.wpm; form.dailyGoal.value = r.dailyGoal; form.theme.value = r.theme; form.mode.value = r.mode; form.topHud.checked = r.topHud;
    form.fish.placeholder = s.keys.fish; form.gemini.placeholder = s.keys.gemini;
    keyStatus('fish', s.keys.fish); keyStatus('gemini', s.keys.gemini);
    syncOutputs();
  }
  function keyStatus(service, masked, message, ok) {
    const el = $(`#key-status-${service}`); if (!el) return;
    if (message !== undefined) { el.textContent = message; el.className = `key-status ${ok ? 'ok' : 'bad'}`; return; }
    const saved = masked && masked !== 'Not set';
    el.textContent = saved ? '✓ A key is saved on the glasses' : 'No key saved yet';
    el.className = `key-status ${saved ? 'ok' : ''}`;
  }
  function syncOutputs() { $('#font-output').textContent = `${$('#settings-form').fontSp.value} sp`; $('#wpm-output').textContent = `${$('#settings-form').wpm.value} wpm`; }
  async function refresh() {
    notice('');
    const response = await requestJson('/api/v1/state');
    state.data = response;
    $('#connection-label').textContent = `${response.device} connected`;
    renderMetrics(); renderBooks(); renderCoach(); renderVoices(); fillSettings();
  }
  async function sourceSearch() {
    const q = $('#source-query').value.trim();
    const source = $('#source-pick').value;
    const grid = $('#source-results');
    grid.innerHTML = `<p class="empty">${q ? `Searching for “${escapeHtml(q)}”…` : 'Loading popular books…'}</p>`;
    try {
      const res = await requestJson(q
        ? `/api/v1/sources/search?source=${encode(source)}&q=${encode(q)}`
        : `/api/v1/sources/popular?source=${encode(source)}`);
      state.sourceResults = res.results || [];
      renderSourceResults();
    } catch (e) { grid.innerHTML = `<p class="empty">${escapeHtml(e.message)}</p>`; }
  }
  function renderSourceResults() {
    const results = state.sourceResults || [];
    const onGlasses = new Set((state.data?.books || []).map(b => b.file));
    $('#source-results').innerHTML = results.length ? results.map((r, i) => {
      const initial = escapeHtml((r.title || '?').slice(0, 2).toUpperCase());
      const have = onGlasses.has(r.file);
      return `<article class="book-card"><div class="book-cover">${r.cover ? `<img loading="lazy" src="${escapeHtml(r.cover)}" alt="Cover of ${escapeHtml(r.title)}" onerror="this.remove()">` : ''}<span>${initial}</span><span class="ext-badge">${escapeHtml(r.ext.toUpperCase())}</span></div><div class="book-body"><div class="book-title" title="${escapeHtml(r.title)}">${escapeHtml(r.title)}</div><div class="book-author">${escapeHtml(r.author || 'Unknown author')}</div><div class="book-actions"><button class="send-book${have ? '' : ' restore'}" data-send="${i}" ${have ? 'disabled' : ''}>${have ? 'On glasses ✓' : 'Send to glasses'}</button></div></div></article>`;
    }).join('') : '<p class="empty">Nothing matched. Try different words, or switch catalogs.</p>';
  }
  async function loadRepos() {
    try {
      const res = await requestJson('/api/v1/sources/repos');
      $('#source-repos').innerHTML = (res.repos || []).map(r =>
        `<a class="repo-row" href="${escapeHtml(r.url)}" target="_blank" rel="noopener"><strong>${escapeHtml(r.name)}</strong><span>${escapeHtml(r.note)}</span></a>`).join('');
    } catch (e) { $('#source-repos').innerHTML = `<p class="empty">${escapeHtml(e.message)}</p>`; }
  }
  async function coverSearch() {
    const q = $('#cover-query').value.trim();
    if (!q) { $('#cover-results').innerHTML = '<p class="empty">Type a few search words first.</p>'; return; }
    $('#cover-results').innerHTML = `<p class="empty">Searching Open Library for “${escapeHtml(q)}”…</p>`;
    try {
      const r = await requestJson('/api/v1/cover/search', { method:'POST', body:JSON.stringify({query:q}) });
      const urls = r.covers || [];
      $('#cover-results').innerHTML = urls.length
        ? urls.map(u => `<img class="cover-option" loading="lazy" src="${escapeHtml(u.replace('-L.jpg','-M.jpg'))}" data-cover-pick="${escapeHtml(u)}" alt="Cover candidate" title="Use this cover">`).join('')
        : '<p class="empty">No covers matched those words. Try fewer or different keywords.</p>';
    } catch (e) { $('#cover-results').innerHTML = `<p class="empty">${escapeHtml(e.message)}</p>`; }
  }
  function showInfo(title, html) {
    $('#info-title').textContent = title;
    $('#info-body').innerHTML = html;
    const d = $('#info-dialog'); if (!d.open) d.showModal();
  }
  function confirmAction(title, message, action) {
    const dialog = $('#confirm-dialog'); $('#confirm-title').textContent = title; $('#confirm-message').textContent = message;
    dialog.showModal(); dialog.addEventListener('close', () => { if (dialog.returnValue === 'confirm') action(); }, { once:true });
  }
  async function connect() {
    let value = $('#glasses-url').value.trim(); if (!value) value = location.origin;
    if (!/^https?:\/\//.test(value)) value = `http://${value}`;
    state.base = value.replace(/\/$/, ''); localStorage.setItem('tapreader-glasses-url', state.base); $('#glasses-url').value = state.base;
    try { await refresh(); notice(`Connected to ${state.data.device}.`); } catch (e) { notice(e.message, true); $('#connection-label').textContent = 'Connection unavailable'; }
  }
  async function searchVoices(query) {
    const el = $('#voice-results');
    el.innerHTML = '<p class="empty">Searching the fish.audio voice library…</p>';
    try {
      const res = await requestJson(`/api/v1/voices/search?q=${encode(query || '')}`);
      state.voiceResults = res.results || [];
      renderVoiceResults();
    } catch (e) { state.voiceResults = null; el.innerHTML = `<p class="empty">${escapeHtml(e.message)}</p>`; }
  }
  async function coachBook() {
    if (!state.selectedBook) return;
    const output = $('#coach-output'); output.innerHTML = '<p class="coach-empty">Gemini is preparing your reading note…</p>';
    try {
      const note = await requestJson('/api/v1/coach', { method:'POST', body:JSON.stringify({file:state.selectedBook}) });
      output.innerHTML = note.fresh
        ? `<p class="eyebrow">Not started yet</p><h3>How to engage this book</h3><p>${escapeHtml(note.summary)}</p><h3>Take the first step</h3><p>${escapeHtml(note.encouragement)}</p><button class="coach-button" id="ask-coach">Refresh note</button>`
        : `<p class="eyebrow">Through chapter ${note.chapter} · ${note.percent}% position</p><h3>What you have read</h3><p>${escapeHtml(note.summary)}</p><h3>Keep going</h3><p>${escapeHtml(note.encouragement)}</p><button class="coach-button" id="ask-coach">Refresh note</button>`;
    } catch(e) { output.innerHTML = `<p class="coach-empty">${escapeHtml(e.message)}</p><button class="coach-button" id="ask-coach">Try again</button>`; }
  }
  async function nasConnect() {
    const n = state.nas;
    if (!n.host) { notice('Enter the NAS host or IP first.', true); return; }
    notice('Connecting to the NAS…');
    try {
      const res = await requestJson('/api/v1/nas/shares', { method:'POST', body:JSON.stringify({ host:n.host, user:n.user, pass:n.pass }) });
      n.shares = res.shares || []; n.share = ''; n.path = ''; n.entries = [];
      renderNas(); notice(n.shares.length ? '' : 'Connected, but the NAS reported no browseable shares.', !n.shares.length);
    } catch (e) { notice(e.message, true); }
  }
  async function nasBrowse(path) {
    const n = state.nas;
    notice('Reading the NAS folder…');
    try {
      const res = await requestJson('/api/v1/nas/list', { method:'POST', body:JSON.stringify({ host:n.host, share:n.share, user:n.user, pass:n.pass, path }) });
      n.path = path; n.entries = res.entries || []; renderNas(); notice('');
    } catch (e) { notice(e.message, true); }
  }
  function renderNas() {
    const n = state.nas, pathEl = $('#nas-path');
    pathEl.hidden = false;
    // Root level: the server's shares are shown as top-level folders.
    if (!n.share) {
      pathEl.textContent = `\\\\${n.host}`;
      $('#nas-list').innerHTML = n.shares.length ? n.shares.map(s =>
        `<button class="nas-row" data-nas-share="${escapeHtml(s)}">🗄 ${escapeHtml(s)}</button>`).join('')
        : '<p class="empty">No shares found on this NAS.</p>';
      return;
    }
    pathEl.textContent = `\\\\${n.host}\\${n.share}${n.path ? '\\' + n.path : ''}`;
    const up = `<button class="nas-row" data-nas-up>⬆ ..</button>`;
    $('#nas-list').innerHTML = up + (n.entries.length ? n.entries.map(e => {
      const child = n.path ? `${n.path}\\${e.name}` : e.name;
      if (e.dir) return `<button class="nas-row" data-nas-dir="${escapeHtml(child)}">📁 ${escapeHtml(e.name)}</button>`;
      return `<div class="nas-row nas-file"><span class="nas-file-name">📄 ${escapeHtml(e.name)}<small>${Math.round((e.size||0)/1024).toLocaleString()} KB</small></span><button type="button" class="nas-add" data-nas-file="${escapeHtml(child)}" data-nas-name="${escapeHtml(e.name)}">Add book</button></div>`;
    }).join('') : '<p class="empty">No folders or books here.</p>');
  }
  async function nasImport(path, name) {
    const n = state.nas;
    confirmAction('Copy to glasses?', `${name} will be copied from the NAS to your glasses.`, async () => {
      notice(`Copying ${name} from the NAS…`);
      try {
        await requestJson('/api/v1/nas/import', { method:'POST', body:JSON.stringify({ host:n.host, share:n.share, user:n.user, pass:n.pass, path, name }) });
        notice(`${name} added to your glasses.`); await refresh();
      } catch (e) { notice(e.message, true); }
    });
  }
  document.addEventListener('click', async event => {
    if (event.target.closest('[data-nas-up]')) {
      const n = state.nas;
      if (!n.path) { n.share = ''; n.entries = []; renderNas(); return; }   // back to the share list
      return nasBrowse(n.path.split('\\').slice(0, -1).join('\\'));
    }
    const nasShare = event.target.closest('[data-nas-share]'); if (nasShare) { state.nas.share = nasShare.dataset.nasShare; return nasBrowse(''); }
    const nasDir = event.target.closest('[data-nas-dir]'); if (nasDir) return nasBrowse(nasDir.dataset.nasDir);
    const nasFile = event.target.closest('[data-nas-file]'); if (nasFile) return nasImport(nasFile.dataset.nasFile, nasFile.dataset.nasName);
    const nav = event.target.closest('.nav-item'); if (nav) return switchView(nav.dataset.view);
    if (event.target.id === 'connect') return connect();
    const f = event.target.closest('.filter'); if (f) { state.filter = f.dataset.filter; $$('.filter').forEach(x => x.classList.toggle('active', x === f)); return renderBooks(); }
    const select = event.target.closest('[data-coach-select]'); if (select) { state.selectedBook = select.dataset.coachSelect; return renderCoach(); }
    if (event.target.id === 'ask-coach') return coachBook();
    const coach = event.target.closest('[data-coach]'); if (coach) { state.selectedBook = coach.dataset.coach; switchView('coach'); return renderCoach(); }
    const toc = event.target.closest('[data-toc]'); if (toc) {
      const bk = state.data.books.find(b => b.file === toc.dataset.toc);
      showInfo(bk ? bk.title : 'Table of contents', '<p class="empty">Reading the chapter list…</p>');
      try {
        const b = await requestJson(`/book?file=${encode(toc.dataset.toc)}`);
        const items = (b.chapters || []).map((c, i) => `<li class="${i === b.currentChapter ? 'current' : ''}">${escapeHtml(c || `Chapter ${i + 1}`)}</li>`).join('');
        $('#info-body').innerHTML = (b.chapters || []).length > 1 ? `<p class="small-copy">${b.chapterCount} chapters — the highlighted one is where you are reading.</p><ol class="toc-list">${items}</ol>` : '<p class="empty">This book has no table of contents.</p>';
      } catch (e) { $('#info-body').innerHTML = `<p class="empty">${escapeHtml(e.message)}</p>`; }
      return;
    }
    const summaryBtn = event.target.closest('[data-summary]'); if (summaryBtn) {
      const bk = state.data.books.find(b => b.file === summaryBtn.dataset.summary);
      showInfo(bk ? bk.title : 'About this book', '<p class="empty">Looking this book up…</p>');
      try {
        const r = await requestJson('/api/v1/books/summary', { method:'POST', body:JSON.stringify({file:summaryBtn.dataset.summary}) });
        $('#info-body').innerHTML = `<p class="summary-text">${escapeHtml(r.summary)}</p><p class="summary-source">Source: ${escapeHtml(r.source)}</p>`;
      } catch (e) { $('#info-body').innerHTML = `<p class="empty">${escapeHtml(e.message)}</p>`; }
      return;
    }
    const coverBtn = event.target.closest('[data-cover]'); if (coverBtn) {
      const bk = state.data.books.find(b => b.file === coverBtn.dataset.cover);
      state.coverFile = coverBtn.dataset.cover;
      $('#cover-query').value = `${bk ? bk.title : ''} ${bk && bk.author ? bk.author : ''}`.trim();
      $('#cover-results').innerHTML = '';
      $('#cover-dialog').showModal();
      return coverSearch();
    }
    const pick = event.target.closest('[data-cover-pick]'); if (pick) {
      try {
        notice('Downloading the cover to your glasses…');
        await requestJson('/api/v1/cover/apply', { method:'POST', body:JSON.stringify({file:state.coverFile, url:pick.dataset.coverPick}) });
        state.coverBust[state.coverFile] = Date.now();
        $('#cover-dialog').close(); renderBooks(); notice('Cover updated.');
      } catch (e) { notice(e.message, true); }
      return;
    }
    const reset = event.target.closest('[data-reset]'); if (reset) return confirmAction('Reset progress?', 'This resets the saved reading position and words-read count for this book.', async () => { try { await requestJson('/api/v1/books/reset',{method:'POST',body:JSON.stringify({file:reset.dataset.reset})}); await refresh(); notice('Progress reset.'); } catch(e) { notice(e.message,true); } });
    const del = event.target.closest('[data-delete]'); if (del) return confirmAction('Remove from glasses?', 'The book comes off the glasses but stays in “Off the glasses” below, progress included, so you can restore it any time.', async () => { try { await api(`/api/v1/books?file=${encode(del.dataset.delete)}`,{method:'DELETE'}); await refresh(); notice('Moved to “Off the glasses”. Restore it from the bottom of the library.'); } catch(e) { notice(e.message,true); } });
    const send = event.target.closest('[data-send]'); if (send) {
      const r = (state.sourceResults || [])[+send.dataset.send]; if (!r) return;
      send.disabled = true; send.textContent = 'Sending…';
      try {
        await requestJson('/api/v1/sources/download', { method:'POST', body:JSON.stringify(r) });
        await refresh(); renderSourceResults();
        notice(`“${r.title}” is on your glasses.`);
      } catch (e) { notice(e.message, true); send.disabled = false; send.textContent = 'Send to glasses'; }
      return;
    }
    const restore = event.target.closest('[data-restore]'); if (restore) { try { restore.disabled = true; restore.textContent = 'Restoring…'; await requestJson('/api/v1/books/restore',{method:'POST',body:JSON.stringify({file:restore.dataset.restore})}); await refresh(); notice('Restored to the glasses.'); } catch(e) { notice(e.message,true); await refresh(); } return; }
    const purge = event.target.closest('[data-purge]'); if (purge) return confirmAction('Delete forever?', 'This permanently deletes the book file and its reading progress. This cannot be undone.', async () => { try { await api(`/api/v1/books/purge?file=${encode(purge.dataset.purge)}`,{method:'DELETE'}); await refresh(); notice('Book deleted permanently.'); } catch(e) { notice(e.message,true); } });
    const preview = event.target.closest('[data-preview]'); if (preview) { try { notice('Generating voice preview…'); const response = await api('/api/v1/voice/preview',{method:'POST',body:JSON.stringify({id:preview.dataset.preview,text:'This is how I sound while reading with TapReader.'})}); const audio = new Audio(URL.createObjectURL(await response.blob())); audio.play(); notice(''); } catch(e) { notice(e.message,true); } }
    const addVoice = event.target.closest('[data-add-voice]'); if (addVoice) { try { const settings = await requestJson('/api/v1/voices',{method:'POST',body:JSON.stringify({id:addVoice.dataset.addVoice,name:addVoice.dataset.addName})}); state.data.settings = settings; renderVoices(); fillSettings(); notice(`Added “${addVoice.dataset.addName}” to your glasses.`); } catch(e) { notice(e.message,true); } }
    const voiceDelete = event.target.closest('[data-delete-voice]'); if (voiceDelete) return confirmAction('Delete voice?', 'This removes the saved voice from the glasses and companion.', async () => { try { const settings = await requestJson(`/api/v1/voices?id=${encode(voiceDelete.dataset.deleteVoice)}`,{method:'DELETE'}); state.data.settings = settings; renderVoices(); fillSettings(); } catch(e) { notice(e.message,true); } });
    const testKey = event.target.closest('[data-test-key]');
    if (testKey) {
      const service = testKey.dataset.testKey;
      const field = $('#settings-form')[service];
      const typed = field.value.trim();
      try {
        testKey.disabled = true;
        keyStatus(service, null, typed ? 'Saving and testing the key…' : 'Testing the saved key…', true);
        const result = await requestJson('/api/v1/keys/test',{method:'POST',body:JSON.stringify({service, key: typed})});
        if (result.ok && typed) { field.value = ''; state.data.settings = await requestJson('/api/v1/settings'); fillSettings(); }
        keyStatus(service, null, result.message, result.ok);
      } catch(e) { keyStatus(service, null, e.message, false); }
      finally { testKey.disabled = false; }
    }
  });
  $('#book-search').addEventListener('input', renderBooks); $('#settings-form').fontSp.addEventListener('input',syncOutputs); $('#settings-form').wpm.addEventListener('input',syncOutputs);
  $('#voice-finder').addEventListener('submit', event => { event.preventDefault(); searchVoices($('#voice-query').value.trim()); });
  $('#cover-form').addEventListener('submit', event => { event.preventDefault(); coverSearch(); });
  $('#source-form').addEventListener('submit', event => { event.preventDefault(); sourceSearch(); });
  $('#source-pick').addEventListener('change', sourceSearch);
  $('#glasses-url').addEventListener('keydown', event => { if (event.key === 'Enter') connect(); });
  $('#book-upload').addEventListener('change', async event => { const file = event.target.files[0]; if (!file) return; try { notice(`Uploading ${file.name}…`); const res = await fetch(`${base()}/upload?name=${encode(file.name)}`, {method:'POST',body:file}); if (!res.ok) throw new Error(await res.text()); await refresh(); notice(`${file.name} added to your glasses.`); } catch(e) { notice(e.message,true); } finally { event.target.value = ''; } });
  $('#add-voice').addEventListener('submit', async event => { event.preventDefault(); const form = new FormData(event.target); try { const settings = await requestJson('/api/v1/voices',{method:'POST',body:JSON.stringify({name:form.get('name'),id:form.get('id')})}); state.data.settings = settings; event.target.reset(); renderVoices(); fillSettings(); notice('Voice saved to glasses.'); } catch(e) { notice(e.message,true); } });
  $('#voice-list').addEventListener('change', async event => { if (event.target.name !== 'voice') return; try { const settings = await requestJson('/api/v1/voices/select',{method:'POST',body:JSON.stringify({id:event.target.value})}); state.data.settings = settings; renderVoices(); fillSettings(); notice('Narration voice selected.'); } catch(e) { notice(e.message,true); } });
  $('#settings-form').addEventListener('submit', async event => { event.preventDefault(); const form = event.currentTarget; const payload = {deviceName:form.deviceName.value,reader:{fontSp:+form.fontSp.value,wpm:+form.wpm.value,dailyGoal:+form.dailyGoal.value,theme:+form.theme.value,mode:+form.mode.value,topHud:form.topHud.checked},keys:{fish:form.fish.value,gemini:form.gemini.value}}; try { const settings = await requestJson('/api/v1/settings',{method:'POST',body:JSON.stringify(payload)}); state.data.settings = settings; form.fish.value='';form.gemini.value='';fillSettings();notice('Settings synced to glasses.'); } catch(e) { notice(e.message,true); } });
  $('#nas-form').addEventListener('submit', event => {
    event.preventDefault();
    const f = new FormData(event.target);
    state.nas = { host: (f.get('host')||'').trim(), share: '', user: (f.get('user')||'').trim(), pass: f.get('pass')||'', path: '', entries: [], shares: [] };
    nasConnect();
  });
  $('#glasses-url').value = state.base;
  if (state.base) connect();
})();
