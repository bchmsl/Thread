/* Notes: index on the left, the note as a dot-grid page on the right.
   Rich text (no Markdown). Every edit saves locally first; "Synced" only after the simulated server ack. */
(function () {
  const T = window.T;
  const S = T.sel;
  const D = T.D;
  const N = T.noteSync;
  const html = T.html;
  const I = T.I;
  const page = { filter: 'all', q: '', id: T.params.get('id'), compare: false };
  let lastRange = null;

  /* ---------------- Index ---------------- */
  function visibleNotes() {
    const q = page.q.trim().toLowerCase();
    return T.state.notes.filter((n) => {
      if (page.filter === 'linked' && !n.topicIds.length) return false;
      if (page.filter === 'standalone' && n.topicIds.length) return false;
      if (!q) return true;
      return (n.title || '').toLowerCase().includes(q) || T.plain(n.body).toLowerCase().includes(q);
    }).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  }
  function listRows() {
    const ns = visibleNotes();
    if (!ns.length) {
      return html`<li class="muted" style="padding:12px">${T.state.notes.length ? 'No notes match.' : 'No notes yet. Start one with New note.'}</li>`;
    }
    return html`${ns.map((n) => {
      const st = N.get(n.id);
      const flag = ['conflict', 'local-failed', 'sync-failed'].includes(st) ? 'bad' : st === 'offline' ? 'warn' : '';
      const tps = n.topicIds.map(S.topic).filter(Boolean);
      return html`<li><a class="nrow" href="notes.html?id=${n.id}" data-act="note-open" data-id="${n.id}" data-k="nrow-${n.id}" ${n.id === page.id ? T.raw('aria-current="page"') : ''}>
        <span class="nrow__title"><span>${n.title || 'Untitled note'}</span>
          ${flag ? html`<span class="nrow__flag ${flag === 'warn' ? 'tone-warn' : ''}" title="${T.SYNC_TEXT[st][1]}">${I(flag === 'warn' ? 'cloudOff' : 'alert', 15)}<span class="vh">${T.SYNC_TEXT[st][1]}</span></span>` : ''}</span>
        <span class="nrow__snip">${T.plain(n.body) || 'Empty note'}</span>
        <span class="nrow__meta">${tps.length ? html`${tps.map((t) => T.hex(t, 'xs'))}<span class="nrow__tps">${tps.map((t) => t.name).join(', ')}</span>` : html`${T.hexEmpty('xs')}<span class="nrow__tps">${n.inbox ? 'In Inbox' : 'Standalone'}</span>`}
          <span aria-hidden="true">·</span><span class="nrow__when">${D.ago(n.updatedAt)}</span></span></a></li>`;
    })}`;
  }
  function index() {
    const chip = (v, l) => html`<button class="chip" type="button" aria-pressed="${page.filter === v}" data-act="note-filter" data-v="${v}" data-k="nf-${v}">${l}</button>`;
    return html`<aside class="nindex" aria-label="All notes">
      <div class="nindex__top"><h1 class="nindex__title">Notes</h1>
        <button class="btn btn--primary" type="button" data-act="note-new" data-k="note-new">New note</button></div>
      <label class="vh" for="nfilter">Filter notes</label>
      <input class="input" type="search" id="nfilter" placeholder="Filter by title or text" value="${page.q}" data-k="nfilter" autocomplete="off">
      <div class="nindex__filters" role="group" aria-label="Show">${chip('all', 'All')}${chip('linked', 'In a Topic')}${chip('standalone', 'Standalone')}</div>
      <ul class="nindex__list" id="nlist">${listRows()}</ul>
    </aside>`;
  }

  /* ---------------- Save status and banners ---------------- */
  function saveInfo(n) {
    const st = N.get(n.id);
    const skip = T.state.session && T.state.session.mode === 'skip';
    const at = D.time(n.updatedAt);
    const M = {
      saving: ['device', 'Saving on this device', '', 'busy'],
      offline: ['device', 'Saved on this device', skip ? 'Local only, no sync' : 'Syncs when online', 'warn'],
      syncing: ['cloudUp', 'Saved on this device', 'Syncing…', 'busy'],
      synced: ['cloudCheck', 'Synced', 'On this device and the server, ' + at, 'ok'],
      'local-failed': ['alert', 'Not saved', 'Copy your text before leaving', 'bad'],
      'sync-failed': ['alert', 'Saved on this device', 'Sync failed', 'bad'],
      conflict: ['compare', 'Saved on this device', 'Conflict with another device', 'bad']
    };
    return M[st] || M.synced;
  }
  function saveStatus(n) {
    const [icon, text, small, tone] = saveInfo(n);
    return html`${I(icon, 16)}<span>${text}</span>${small ? html`<small>${small}</small>` : ''}`;
  }
  function banners(n) {
    const st = N.get(n.id);
    const box = (tone, icon, title, text, acts) => html`<div class="nbanner nbanner--${tone}" role="${tone === 'bad' ? 'alert' : 'status'}">${I(icon, 18)}
      <div class="nbanner__body"><p class="nbanner__title">${title}</p><p class="nbanner__text">${text}</p></div>
      ${acts ? html`<div class="nbanner__acts">${acts}</div>` : ''}</div>`;
    if (st === 'local-failed') {
      return box('bad', 'alert', 'Not saved on this device', 'This device refused to store the change. Your text is still on screen. Copy it, then retry.',
        html`<button class="btn btn--line btn--sm" type="button" data-act="note-retry">${I('refresh', 15)} Retry save</button>
          <button class="btn btn--line btn--sm" type="button" data-act="note-copy">${I('copy', 15)} Copy text</button>`);
    }
    if (st === 'sync-failed') {
      return box('bad', 'alert', 'Sync failed', 'Your change is saved on this device. The server did not accept it yet.',
        html`<button class="btn btn--line btn--sm" type="button" data-act="note-retry">${I('refresh', 15)} Retry sync</button>`);
    }
    if (st === 'conflict') {
      const o = T.otherDeviceVersion(n);
      return box('bad', 'compare', 'Changed on two devices', 'This device and ' + o.device + ' both edited this note. Both versions are kept until you choose.',
        html`<button class="btn btn--line btn--sm" type="button" data-act="note-compare" data-k="note-compare">${I('compare', 15)} Compare versions</button>`);
    }
    if (st === 'offline') {
      const skip = T.state.session && T.state.session.mode === 'skip';
      return box('warn', skip ? 'device' : 'cloudOff', skip ? 'Local only' : 'Offline',
        skip ? 'You skipped sign-in, so this note stays on this device.' : 'Edits save on this device and sync when you are back online.');
    }
    return '';
  }

  /* ---------------- Editor pane ---------------- */
  const TB = [
    ['heading', 'heading', 'Heading'], ['bold', 'bold', 'Bold (⌘B)'], ['italic', 'italic', 'Italic (⌘I)'], null,
    ['ul', 'list', 'Bulleted list'], ['ol', 'listOrdered', 'Numbered list'], ['check', 'checklist', 'Checklist. ⌘Enter ticks the current item'], null,
    ['link', 'link', 'Link']
  ];
  function toolbar() {
    let first = true;
    return html`<div class="tb" role="toolbar" aria-label="Formatting" aria-controls="editor">
      ${TB.map((b) => {
        if (!b) return html`<span class="tb__sep" aria-hidden="true"></span>`;
        const tab = first ? '0' : '-1';
        first = false;
        return html`<button class="tb__btn" type="button" tabindex="${tab}" data-act="tb" data-cmd="${b[0]}" aria-label="${b[2]}" title="${b[2]}" ${T.raw(b[0] === 'link' ? 'aria-expanded="false" aria-controls="tb-link"' : 'aria-pressed="false"')}>${I(b[1], 18)}</button>`;
      })}
      <form class="tb__link" id="tb-link" data-submit="tb-link" novalidate autocomplete="off" hidden>
        <label class="vh" for="tb-url">Link address</label>
        <input class="input" type="url" id="tb-url" name="url" placeholder="https://" aria-describedby="tb-err">
        <button class="btn btn--line btn--sm" type="submit">Add link</button>
        <button class="btn btn--text btn--sm" type="button" data-act="tb-link-cancel">Cancel</button>
        <p class="tb__err" id="tb-err" hidden></p></form>
    </div>`;
  }
  function demoSelect(n) {
    const pin = N.pinned(n.id) || 'live';
    const opt = (v, l) => html`<option value="${v}" ${pin === v ? 'selected' : ''}>${l}</option>`;
    return T.demoBar('Save state', html`<label class="vh" for="ndemo">Save state for this note (demo)</label>
      <select class="input input--sm input--auto" id="ndemo" data-change="note-demo" data-k="ndemo">
        ${opt('live', 'Live (follows network)')}${opt('saving', 'Saving locally')}${opt('offline', 'Saved locally, offline')}
        ${opt('syncing', 'Syncing')}${opt('synced', 'Synced')}${opt('local-failed', 'Not saved locally')}${opt('sync-failed', 'Sync failed')}${opt('conflict', 'Conflict')}</select>`);
  }
  function pane(n) {
    const tps = n.topicIds.map(S.topic).filter(Boolean);
    const [, , , tone] = saveInfo(n);
    return html`
      <a class="back nback" href="notes.html" data-act="note-back">${I('chevLeft', 16)} All notes</a>
      <div id="nbanners">${banners(n)}</div>
      <div class="pagebar">
        <p class="savest tone-${tone}" id="nsave" role="status" aria-live="polite">${saveStatus(n)}</p>
        <span class="pagebar__sp"></span>
        <button class="btn btn--text btn--sm" type="button" data-act="note-rem">${I('bell', 15)} Reminder</button>
        <button class="btn btn--text btn--sm btn--danger-text" type="button" data-act="note-trash">${I('trash', 15)} Move to Trash</button>
      </div>
      <article class="page" aria-label="Note">
        ${tps.length ? html`<div class="page__stickers" aria-hidden="true">${tps.slice(0, 3).map((t) => html`<a href="topic.html?id=${t.id}" tabindex="-1" title="${t.name}">${T.hex(t, 'md')}</a>`)}</div>` : ''}
        <div class="page__links">${tps.length ? html`<span>Linked to</span>${tps.map((t) => T.topicTag(t))}` : html`${T.topicTag(null, { noneLabel: n.inbox ? 'In Inbox, no Topic' : 'Standalone' })}`}
          <button class="btn btn--text btn--sm" type="button" data-act="note-link" data-k="note-link">${I('link', 15)} ${tps.length ? 'Change Topics' : 'Link to Topic'}</button></div>
        <h2 class="vh">Note: ${n.title || 'Untitled note'}</h2>
        <label class="vh" for="note-title">Note title</label>
        <textarea class="page__title" id="note-title" rows="1" placeholder="Untitled note" data-k="note-title">${n.title}</textarea>
        ${toolbar()}
        <div class="editor" id="editor" contenteditable="true" role="textbox" aria-multiline="true" aria-label="Note text" spellcheck="true" data-k="editor">${T.raw(T.sanitize(n.body))}</div>
      </article>
      <div class="pagedemo">${demoSelect(n)}</div>`;
  }
  function compareView(n) {
    const o = T.otherDeviceVersion(n);
    return html`<div class="cmp-wrap">
      <button class="back" type="button" data-act="cmp-back" style="background:none;border:0;cursor:pointer">${I('chevLeft', 16)} Back to the note</button>
      <div class="cmp-intro"><h2 class="h2" id="cmp-h" tabindex="-1">Choose which version to keep</h2>
        <p class="muted" style="margin-top:6px">Both devices changed “${n.title || 'Untitled note'}”. The version you do not keep goes to Trash, so you can still restore it.</p></div>
      <div class="cmp">
        <section class="cmp__col" aria-label="This device's version">
          <div class="cmp__head"><span class="cmp__dev">${I('device', 18)} This device</span><span class="cmp__at">Edited ${D.when(n.updatedAt, true)}</span></div>
          <p class="cmp__title">${n.title || 'Untitled note'}</p><div class="editor editor--ro">${T.raw(T.sanitize(n.body))}</div></section>
        <section class="cmp__col" aria-label="${o.device} version">
          <div class="cmp__head"><span class="cmp__dev">${I('device', 18)} ${o.device}</span><span class="cmp__at">Edited ${D.when(o.editedAt, true)}</span></div>
          <p class="cmp__title">${o.title}</p><div class="editor editor--ro">${T.raw(T.sanitize(o.body))}</div></section>
      </div>
      <div class="cmp__acts">
        <button class="btn btn--line" type="button" data-act="cmp-keep" data-v="local">Keep this device's version</button>
        <button class="btn btn--line" type="button" data-act="cmp-keep" data-v="other">Keep ${o.device} version</button>
        <button class="btn btn--line" type="button" data-act="cmp-keep" data-v="both">Keep both</button>
        <p class="cmp__hint">Keep both saves the ${o.device} version as a second note with the same Topics.</p>
      </div></div>`;
  }

  function sizeTitle() {
    const t = document.getElementById('note-title');
    if (t) { t.style.height = 'auto'; t.style.height = t.scrollHeight + 'px'; }
  }
  function patch() {
    const list = document.getElementById('nlist');
    if (list) {
      const keep = T.captureFocus(list);
      list.innerHTML = listRows().s;
      T.restoreFocus(list, keep);
    }
    const n = S.note(page.id);
    if (!n) return;
    const b = document.getElementById('nbanners');
    if (b) b.innerHTML = banners(n).s;
    const s = document.getElementById('nsave');
    if (s) { s.className = 'savest tone-' + saveInfo(n)[3]; s.innerHTML = saveStatus(n).s; }
    const d = document.getElementById('ndemo');
    if (d) d.value = N.pinned(n.id) || 'live';
    if (page.compare && N.get(n.id) !== 'conflict') { page.compare = false; T.rerender('local'); }
  }

  T.definePage({
    skip(source) {
      if (source === 'editor' || source === 'sync') { patch(); return true; }
      return false;
    },
    render(main) {
      const n = page.id ? S.note(page.id) : null;
      let right;
      if (page.id && !n) {
        right = html`<a class="back nback" href="notes.html" data-act="note-back">${I('chevLeft', 16)} All notes</a>
          ${T.empty({ icon: 'notes', title: 'Note not found', text: 'It may have been moved to Trash.', action: html`<a class="btn btn--line" href="trash.html">Open Trash</a>` })}`;
      } else if (!n) {
        right = T.empty({ icon: 'notes', title: T.state.notes.length ? 'Pick a note' : 'No notes yet',
          text: T.state.notes.length ? 'Choose one from the list, or start a new one.' : 'Notes are rich text pages. Link them to Topics or keep them standalone.' });
      } else {
        right = page.compare && N.get(n.id) === 'conflict' ? compareView(n) : pane(n);
      }
      main.innerHTML = html`<div class="notes ${page.id ? 'has-open' : ''}">${index()}<section class="npane" id="npane" tabindex="-1" aria-label="Note editor">${right}</section></div>`.s;
      sizeTitle();
    }
  });

  /* ---------------- Index actions ---------------- */
  function openNote(id, focusTitle) {
    page.id = id; page.compare = false;
    T.setParam('id', id);
    T.rerender('local');
    const el = focusTitle ? document.getElementById('note-title') : document.getElementById('npane');
    if (el) el.focus({ preventScroll: window.innerWidth >= 1000 });
    if (window.innerWidth < 1000) window.scrollTo(0, 0);
  }
  T.on('note-open', (el, e) => { e.preventDefault(); openNote(el.dataset.id); });
  T.on('note-back', (el, e) => {
    e.preventDefault();
    const was = page.id;
    page.id = null; page.compare = false;
    T.setParam('id', null);
    T.rerender('local');
    const row = document.querySelector('[data-k="nrow-' + was + '"]');
    if (row) row.focus();
  });
  T.on('note-new', () => {
    /* No Topic here, so the new note waits in Inbox until it is filed or kept standalone (owner decision, 2026-11-13). */
    const n = T.act.createNote({ title: '', topicIds: [] });
    page.filter = 'all'; page.q = '';
    openNote(n.id, true);
  });
  T.on('note-filter', (el) => { page.filter = el.dataset.v; patch(); document.querySelectorAll('[data-act="note-filter"]').forEach((c) => c.setAttribute('aria-pressed', String(c.dataset.v === page.filter))); });
  document.addEventListener('input', (e) => {
    if (e.target.id === 'nfilter') { page.q = e.target.value; patch(); return; }
    if (e.target.id === 'note-title') { sizeTitle(); N.edited(page.id, { title: e.target.value }); return; }
    if (e.target.id === 'editor') N.edited(page.id, { body: T.sanitize(e.target.innerHTML) });
  });

  /* ---------------- Note actions ---------------- */
  T.on('note-demo', (el) => N.pin(page.id, el.value));
  T.on('note-retry', () => { N.retry(page.id); T.toast('Trying again'); });
  T.on('note-copy', () => {
    const n = S.note(page.id);
    const text = (n.title ? n.title + '\n\n' : '') + (document.getElementById('editor') || {}).innerText;
    const done = () => T.toast('Note text copied');
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, selectFallback);
    else selectFallback();
    function selectFallback() {
      const ed = document.getElementById('editor');
      if (!ed) return;
      const r = document.createRange(); r.selectNodeContents(ed);
      const sel = getSelection(); sel.removeAllRanges(); sel.addRange(r);
      T.toast('Text selected. Press ⌘C or Ctrl+C to copy.');
    }
  });
  T.on('note-compare', () => { page.compare = true; T.rerender('local'); const h = document.getElementById('cmp-h'); if (h) h.focus(); });
  T.on('cmp-back', () => { page.compare = false; T.rerender('local'); const b = document.querySelector('[data-k="note-compare"]'); if (b) b.focus(); });
  T.on('cmp-keep', (el) => {
    const v = el.dataset.v;
    const n = S.note(page.id);
    const o = T.otherDeviceVersion(n);
    page.compare = false;
    const extra = T.act.resolveConflict(page.id, v);
    if (v === 'both') T.toast('Kept both. The ' + o.device + ' copy is a second note.', { open: () => openNote(extra.id), openLabel: 'Open copy' });
    else T.toast(v === 'local' ? 'Kept this device\'s version. The other one is in Trash.' : 'Kept the ' + o.device + ' version. Yours is in Trash.',
      { open: () => { location.href = 'trash.html'; }, openLabel: 'Open Trash' });
    const p = document.getElementById('npane');
    if (p) p.focus();
  });
  T.on('note-link', async () => {
    const n = S.note(page.id);
    const prev = n.topicIds.slice();
    const ids = await T.pickTopics({ title: 'Link note to Topics', multiple: true, selected: prev,
      hint: 'A note can belong to several Topics. Unlinking never deletes the note.' });
    if (!ids) return;
    T.act.setNoteTopics(n.id, ids);
    T.toast(ids.length ? 'Linked to ' + T.topicsPhrase(ids) : 'Note is now standalone',
      { undo: () => T.act.setNoteTopics(n.id, prev) });
  });
  T.on('note-rem', () => { const n = S.note(page.id); T.addReminderFor({ kind: 'note', id: n.id }, n.title || 'Note'); });
  T.on('note-trash', () => {
    const n = S.note(page.id);
    const entry = T.act.trash('note', n.id);
    page.id = null;
    T.setParam('id', null);
    T.rerender('local');
    T.toast('Moved to Trash: ' + (n.title || 'Untitled note'), { undo: () => { T.act.restore(entry.id); openNote(n.id); } });
  });

  /* ---------------- Editor behavior ---------------- */
  const editor = () => document.getElementById('editor');
  function save() { const ed = editor(); if (ed) N.edited(page.id, { body: T.sanitize(ed.innerHTML) }); }
  function blockOf(node, sel) {
    const ed = editor();
    while (node && node !== ed) { if (node.nodeType === 1 && node.matches(sel)) return node; node = node.parentNode; }
    return null;
  }
  function selNode() { const s = getSelection(); return s.rangeCount ? s.getRangeAt(0).startContainer : null; }
  function restoreRange() {
    const ed = editor();
    if (!ed) return;
    ed.focus();
    if (lastRange) { const s = getSelection(); s.removeAllRanges(); s.addRange(lastRange); }
  }
  document.addEventListener('selectionchange', () => {
    const ed = editor();
    const s = getSelection();
    if (!ed || !s.rangeCount || !ed.contains(s.anchorNode)) return;
    lastRange = s.getRangeAt(0).cloneRange();
    const node = s.anchorNode;
    const state = {
      heading: !!blockOf(node, 'h1, h2, h3'),
      bold: document.queryCommandState('bold'),
      italic: document.queryCommandState('italic'),
      ul: !!blockOf(node, 'ul:not(.checklist)'),
      ol: !!blockOf(node, 'ol'),
      check: !!blockOf(node, 'ul.checklist')
    };
    document.querySelectorAll('.tb__btn').forEach((b) => { if (b.dataset.cmd in state) b.setAttribute('aria-pressed', String(state[b.dataset.cmd])); });
  });
  document.addEventListener('mousedown', (e) => { if (e.target.closest('.tb__btn')) e.preventDefault(); });
  T.on('tb', (el) => {
    const cmd = el.dataset.cmd;
    if (cmd === 'link') {
      const f = document.getElementById('tb-link');
      if (!f) return;
      f.hidden = !f.hidden;
      el.setAttribute('aria-expanded', String(!f.hidden));
      if (!f.hidden) { f.reset(); clearLinkErr(f); f.elements.url.focus(); } else restoreRange();
      return;
    }
    restoreRange();
    const node = selNode();
    if (cmd === 'heading') document.execCommand('formatBlock', false, blockOf(node, 'h1, h2, h3') ? 'P' : 'H2');
    if (cmd === 'bold' || cmd === 'italic') document.execCommand(cmd);
    if (cmd === 'ol') document.execCommand('insertOrderedList');
    if (cmd === 'ul') {
      const cl = blockOf(node, 'ul.checklist');
      if (cl) { cl.classList.remove('checklist'); cl.querySelectorAll('li').forEach((li) => li.removeAttribute('data-checked')); }
      else document.execCommand('insertUnorderedList');
    }
    if (cmd === 'check') {
      const cl = blockOf(node, 'ul.checklist');
      const ul = blockOf(node, 'ul');
      if (cl) document.execCommand('insertUnorderedList');
      else {
        if (!ul) document.execCommand('insertUnorderedList');
        const list = blockOf(selNode(), 'ul');
        if (list) { list.classList.add('checklist'); list.querySelectorAll('li').forEach((li) => { if (!li.hasAttribute('data-checked')) li.setAttribute('data-checked', 'false'); }); }
      }
    }
    save();
    document.dispatchEvent(new Event('selectionchange'));
  });
  function clearLinkErr(f) {
    const err = document.getElementById('tb-err');
    if (err) err.hidden = true;
    f.elements.url.removeAttribute('aria-invalid');
  }
  function closeLink() {
    const f = document.getElementById('tb-link');
    if (f) f.hidden = true;
    const b = document.querySelector('.tb__btn[data-cmd="link"]');
    if (b) b.setAttribute('aria-expanded', 'false');
  }
  T.on('tb-link-cancel', () => { closeLink(); restoreRange(); });
  T.on('tb-link', (form) => {
    const url = form.elements.url.value.trim();
    if (!/^(https?:\/\/[^\s]+|mailto:[^\s]+)$/i.test(url)) {
      const err = document.getElementById('tb-err');
      if (err) { err.textContent = 'Use a full address that starts with https://, http:// or mailto:'; err.hidden = false; }
      form.elements.url.setAttribute('aria-invalid', 'true');
      form.elements.url.focus();
      return;
    }
    closeLink();
    restoreRange();
    const s = getSelection();
    if (!s.rangeCount || s.isCollapsed) document.execCommand('insertHTML', false, '<a href="' + T.esc(url) + '">' + T.esc(url) + '</a>&nbsp;');
    else document.execCommand('createLink', false, url);
    save();
    T.toast('Link added');
  });
  document.addEventListener('paste', (e) => {
    if (e.target.closest && e.target.closest('#editor')) {
      e.preventDefault();
      const text = (e.clipboardData || window.clipboardData).getData('text/plain');
      document.execCommand('insertText', false, text);
    }
  });
  document.addEventListener('click', (e) => {
    const li = e.target.closest && e.target.closest('#editor ul.checklist > li');
    if (!li) return;
    if (e.clientX - li.getBoundingClientRect().left > 28) return;
    li.setAttribute('data-checked', li.getAttribute('data-checked') === 'true' ? 'false' : 'true');
    save();
  });
  document.addEventListener('keydown', (e) => {
    if (e.target.id === 'editor' && (e.metaKey || e.ctrlKey) && e.key === 'Enter') {
      const li = blockOf(selNode(), 'ul.checklist > li');
      if (li) { e.preventDefault(); li.setAttribute('data-checked', li.getAttribute('data-checked') === 'true' ? 'false' : 'true'); save(); }
      return;
    }
    if (e.target.id === 'note-title' && e.key === 'Enter') { e.preventDefault(); const ed = editor(); if (ed) ed.focus(); return; }
    const btn = e.target.closest && e.target.closest('.tb__btn');
    if (!btn) return;
    const all = Array.from(document.querySelectorAll('.tb__btn'));
    let i = all.indexOf(btn);
    if (e.key === 'ArrowRight') i = (i + 1) % all.length;
    else if (e.key === 'ArrowLeft') i = (i - 1 + all.length) % all.length;
    else if (e.key === 'Home') i = 0;
    else if (e.key === 'End') i = all.length - 1;
    else return;
    e.preventDefault();
    all.forEach((b, j) => b.setAttribute('tabindex', j === i ? '0' : '-1'));
    all[i].focus();
  });

  /* Demo link from the prototype home: open a note already in conflict. */
  if (page.id && T.params.get('demo') === 'conflict' && S.note(page.id)) {
    T.state.demo.noteDemo[page.id] = 'conflict';
    T.state.demo.noteSync[page.id] = 'conflict';
    T.persist();
    T.setParam('demo', null);
  }
})();
