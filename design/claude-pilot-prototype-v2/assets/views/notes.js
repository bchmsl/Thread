/* Notes: index | document sheet | margin. The sheet is the one light-on-dark writing surface.
   Save and sync states are simulated and always visible. A conflict keeps both versions until you choose. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const TM = T.time;
  const ui = T.ui;
  const esc = T.esc;
  const icon = T.icon;
  const store = T.store;

  let noteId = T.param('id');
  let filter = 'all';
  let saveTimer = null;
  let pipeline = [];
  const focusNew = T.param('new') === '1';

  const NET = [['online', 'Online'], ['offline', 'Offline'], ['localfail', 'Local save fails'], ['remotefail', 'Sync fails']];
  function net() { return T.state().sim.notesNet || 'online'; }
  /* At 900px and below the notes page shows one column: the index or one note. */
  function singleCol() { return window.matchMedia('(max-width: 900px)').matches; }

  const SYNC = {
    saving: { text: 'Saving locally', detail: 'Writing your change to this device.', dot: 'pending', local: 'busy', wire: 'off', cloud: '' },
    local: { text: 'Saved locally', detail: 'Safe on this device. Waiting to sync.', dot: 'pending', local: 'on', wire: 'off', cloud: '' },
    offline: { text: 'Saved locally, offline', detail: 'Safe on this device. It syncs when the connection is back. Keep writing.', dot: 'pending', local: 'on', wire: 'offline', cloud: 'off' },
    syncing: { text: 'Syncing', detail: 'Sending the saved change to your cloud copy.', dot: 'pending', local: 'on', wire: 'moving', cloud: 'busy' },
    synced: { text: 'Synced', detail: 'This device and the cloud have the same version.', dot: 'ok', local: 'on', wire: 'on', cloud: 'on' },
    localfail: { text: 'Local save failed', detail: 'This device could not store the change (demo: storage full). Your text is still in this window, so nothing is lost yet. Copy it or try again.', dot: 'bad', local: 'bad', wire: 'off', cloud: '' },
    remotefail: { text: 'Sync failed', detail: 'Saved on this device, but the cloud rejected the change (demo). Your text is safe here. Thread retries on its own; you can retry now.', dot: 'bad', local: 'on', wire: 'broken', cloud: 'bad' },
    conflict: { text: 'Changed on two devices', detail: 'Both versions are kept until you choose how to resolve it.', dot: 'bad', local: 'on', wire: 'broken', cloud: 'bad' }
  };
  function syncKey(n) {
    if (n.sync === 'local' && net() === 'offline') return 'offline';
    return SYNC[n.sync] ? n.sync : 'synced';
  }

  /* ---------- index ---------- */
  function filtered() {
    const all = T.state().notes.slice().sort((a, b) => (a.updated < b.updated ? 1 : -1));
    if (filter === 'standalone') return all.filter((n) => !n.topicIds.length && !n.inbox);
    if (filter === 'inbox') return all.filter((n) => n.inbox);
    if (filter !== 'all') return all.filter((n) => n.topicIds.indexOf(filter) !== -1);
    return all;
  }

  function index(cur) {
    const s = T.state();
    const list = filtered();
    const count = (f) => { const keep = filter; filter = f; const n = filtered().length; filter = keep; return n; };
    const chips = [['all', 'All'], ['standalone', 'Standalone'], ['inbox', 'Inbox']].map((c) =>
      '<button type="button" class="chip" aria-pressed="' + (filter === c[0]) + '" data-act="noteFilter" data-value="' + c[0] + '">' + c[1] + ' <span class="num" style="font-size:15px">' + count(c[0]) + '</span></button>').join('');
    const topicSel = '<label class="vh" for="noteTopicFilter">Filter by topic</label><select class="select select-sm" id="noteTopicFilter" data-change="noteFilterTopic"><option value="">By topic</option>' +
      s.topics.map((t) => '<option value="' + t.id + '"' + (filter === t.id ? ' selected' : '') + '>' + esc(t.name) + '</option>').join('') + '</select>';
    const items = list.map((n) => {
      const k = syncKey(n);
      const tabs = n.topicIds.map((x) => S.topicById(x)).filter(Boolean);
      return '<a class="nitem" href="notes.html?id=' + n.id + '"' + (cur && n.id === cur.id ? ' aria-current="page"' : '') + '>' +
        '<span class="nt"><span class="nt-title" data-title-for="' + n.id + '">' + esc(n.title || 'Untitled note') + '</span><span class="syncdot ' + SYNC[k].dot + '" id="ndot-' + n.id + '" title="' + SYNC[k].text + '"></span><span class="vh">' + SYNC[k].text + '</span></span>' +
        '<span class="nx">' + esc(T.util.noteText(n.html) || 'Empty note') + '</span>' +
        '<span class="ttab-row">' + (tabs.length ? tabs.map((t) => ui.ttab(t, { size: 'sm', link: false })).join('') : '<span class="faint" style="font-size:12px">' + (n.inbox ? 'Inbox' : 'Standalone') + '</span>') + '</span></a>';
    }).join('');
    return '<nav class="nindex" aria-label="Notes">' +
      '<div class="nindex-head"><h1>Notes</h1>' + ui.keycap('New note', '', 'data-act="newNote"') + '</div>' +
      '<div class="nfilter" role="group" aria-label="Filter notes">' + chips + topicSel + '</div>' +
      (items || '<p class="tray-empty">No notes here yet.</p>') + '</nav>';
  }

  /* ---------- sheet ---------- */
  function topicsLine(n) {
    const linked = n.topicIds.map((x) => S.topicById(x)).filter(Boolean);
    const free = T.state().topics.filter((t) => n.topicIds.indexOf(t.id) === -1);
    return '<div class="ed-sub">' +
      (linked.length ? linked.map((t) => ui.ttabX(t, 'noteUnlinkTopic', 'data-topic="' + t.id + '"', 'Unlink this note from ' + t.name)).join('') : '<span>' + (n.inbox ? 'In Inbox, no topic yet' : 'Standalone note') + '</span>') +
      (free.length ? '<label class="vh" for="noteLinkTopic">Link to a topic</label><select class="select select-sm" id="noteLinkTopic" data-change="noteLinkTopic" style="width:auto"><option value="">Link to a topic</option>' +
        free.map((t) => '<option value="' + t.id + '">' + esc(t.name) + '</option>').join('') + '</select>' : '') + '</div>';
  }

  function fmtbar(n) {
    const k = syncKey(n);
    const b = (cmd, ic, label, key) => '<button type="button" class="iconbtn" id="fmt-' + cmd + '" data-fmt="' + cmd + '" aria-pressed="false" aria-label="' + label + (key ? ' (' + key + ')' : '') + '" title="' + label + (key ? ' (' + key + ')' : '') + '">' + icon(ic) + '</button>';
    return '<div class="fmtbar" role="toolbar" aria-label="Formatting" aria-controls="edBody">' +
      '<a class="btn btn-sm btn-ghost sheet-back" href="notes.html">' + icon('chevronLeft') + 'All notes</a>' +
      b('bold', 'bold', 'Bold', T.isMac ? '⌘B' : 'Ctrl B') + b('italic', 'italic', 'Italic', T.isMac ? '⌘I' : 'Ctrl I') + '<span class="sep" aria-hidden="true"></span>' +
      b('heading', 'heading', 'Heading') + b('list', 'list', 'Bulleted list') + b('checklist', 'checklist', 'Checklist') + b('link', 'link', 'Link') +
      '<span class="savestate" id="saveState"><span class="syncdot ' + SYNC[k].dot + '"></span>' + SYNC[k].text + '</span></div>';
  }

  function markDiff(html, otherHtml) {
    const a = document.createElement('div');
    const b = document.createElement('div');
    a.innerHTML = T.util.sanitize(html);
    b.innerHTML = T.util.sanitize(otherHtml);
    const texts = new Set(Array.from(b.querySelectorAll('p,h2,h3,li')).map((e) => e.textContent.trim()));
    a.querySelectorAll('p,h2,h3,li').forEach((e) => { if (!texts.has(e.textContent.trim())) e.classList.add('diffmark'); });
    return a.innerHTML;
  }

  function conflictView(n) {
    return '<div class="conflict">' +
      '<div class="banner warn" role="alert">' + icon('alert') + '<div><strong>This note changed on two devices.</strong> Both versions are kept until you choose. Highlighted lines differ.</div></div>' +
      '<h2 style="margin-top:20px">' + esc(n.title) + '</h2>' +
      '<div class="versions">' +
        '<section class="version" aria-label="Version on this device"><div class="version-head">' + icon('laptop') + '<strong>This device</strong><span>Edited ' + esc(TM.fmtStamp(n.updated)) + '</span></div><div class="ed-body">' + markDiff(n.html, n.conflict.html) + '</div></section>' +
        '<section class="version" aria-label="Version from ' + esc(n.conflict.device) + '"><div class="version-head">' + icon('cloud') + '<strong>' + esc(n.conflict.device) + '</strong><span>Synced ' + esc(TM.fmtStamp(n.conflict.at)) + '</span></div><div class="ed-body">' + markDiff(n.conflict.html, n.html) + '</div></section>' +
      '</div>' +
      '<div class="resolve"><button type="button" class="btn-key" data-act="resolveConflict" data-value="both">' + icon('copy') + '<span>Keep both as two notes</span></button>' +
        '<button type="button" class="btn" data-act="resolveConflict" data-value="mine">Keep only this device</button>' +
        '<button type="button" class="btn" data-act="resolveConflict" data-value="theirs">Keep only ' + esc(n.conflict.device) + '</button></div>' +
      '<p class="rule" style="margin-top:12px;font-size:13px;color:var(--text-3)">If you keep one version, the other goes to Trash, so it can still be restored.</p>' +
    '</div>';
  }

  function sheet(n) {
    if (n.sync === 'conflict' && n.conflict) {
      return '<article class="sheet" aria-label="Note conflict">' + '<div class="fmtbar"><a class="btn btn-sm btn-ghost sheet-back" href="notes.html">' + icon('chevronLeft') + 'All notes</a><span class="savestate" id="saveState"><span class="syncdot bad"></span>Changed on two devices</span></div>' + conflictView(n) + '</article>';
    }
    return '<article class="sheet" aria-label="Note">' + fmtbar(n) +
      '<div class="sheet-inner">' +
        '<label class="vh" for="edTitle">Note title</label><textarea class="ed-title grow oneline" rows="1" data-enter-to="body" id="edTitle" placeholder="Untitled note" maxlength="140" autocomplete="off">' + esc(n.title) + '</textarea>' +
        topicsLine(n) +
        '<div class="ed-body" id="edBody" contenteditable="true" role="textbox" aria-multiline="true" aria-label="Note text" data-placeholder="Start writing. Formatting shows as you type, not as symbols.">' + T.util.sanitize(n.html) + '</div>' +
      '</div></article>';
  }

  function syncBlock(n) {
    const k = syncKey(n);
    const m = SYNC[k];
    const cloudIcon = m.cloud === 'off' ? 'cloudOff' : 'cloud';
    let actions = '';
    if (k === 'localfail') actions = '<div class="chips"><button type="button" class="btn btn-sm" data-act="noteRetry">' + icon('refresh') + 'Try again</button><button type="button" class="btn btn-sm btn-ghost" data-act="noteCopy">' + icon('copy') + 'Copy text</button></div>';
    if (k === 'remotefail') actions = '<div class="chips"><button type="button" class="btn btn-sm" data-act="noteRetry">' + icon('refresh') + 'Retry sync</button></div>';
    return '<section class="mblock" id="syncBlock" aria-labelledby="syncH"><h2 id="syncH">Save and sync</h2>' +
      '<div class="sync" aria-hidden="true"><span class="node ' + m.local + '">' + icon('laptop') + '</span><span class="wire ' + m.wire + '"></span><span class="node ' + m.cloud + '">' + icon(cloudIcon) + '</span></div>' +
      '<div class="sync-labels" aria-hidden="true"><span>This device</span><span>Cloud</span></div>' +
      '<p class="sync-text" role="status">' + m.text + '</p><p class="sync-detail">' + m.detail + '</p>' + actions +
      '<div class="simbox">' + ui.sim('Network', 'noteNet', NET, net(), 'aria-label="Network for notes, simulation"') +
        '<div class="chips"><button type="button" class="btn btn-sm btn-ghost" data-act="noteReplay">' + icon('play') + 'Replay a save</button>' +
        (n.sync !== 'conflict' ? '<button type="button" class="btn btn-sm btn-ghost" data-act="noteMakeConflict">' + icon('alert') + 'Simulate a conflict</button>' : '') + '</div></div>' +
    '</section>';
  }

  function margin(n) {
    const rems = T.state().reminders.filter((r) => r.target.kind === 'note' && r.target.id === n.id);
    const words = T.util.noteText(n.html).split(/\s+/).filter(Boolean).length;
    return '<aside class="nmargin" aria-label="Note details">' + syncBlock(n) +
      '<section class="mblock" aria-labelledby="remNH"><h2 id="remNH">Reminders</h2>' +
        (rems.length ? rems.map((r) => '<div class="remrow"><span class="when num">' + esc(TM.fmtTime(r.at)) + '<small>' + esc(TM.fmtDay(r.at)) + '</small></span><span class="what">' + esc(r.text) + '</span>' +
          '<button type="button" class="iconbtn iconbtn-sm" data-act="removeReminder" data-id="' + r.id + '" aria-label="Remove reminder">' + icon('x') + '</button></div>').join('') : '<p class="note-hint">No reminder for this note.</p>') +
        '<button type="button" class="btn btn-sm" style="justify-self:start" data-act="remind" data-kind="note" data-id="' + n.id + '">' + icon('bell') + 'Remind me</button></section>' +
      '<section class="mblock" aria-labelledby="infoNH"><h2 id="infoNH">This note</h2><p class="note-hint">Edited ' + esc(TM.fmtStamp(n.updated)) + '. ' + words + ' words. A note can belong to several topics or none.</p>' +
        '<button type="button" class="btn btn-sm btn-ghost btn-quiet-danger" style="justify-self:start" data-act="deleteNote">' + icon('trash') + 'Move to Trash</button></section>' +
    '</aside>';
  }

  function render() {
    flush();
    const s = T.state();
    let n = noteId ? S.noteById(noteId) : null;
    const trashed = noteId && !n ? s.trash.find((x) => x.kind === 'note' && x.item.id === noteId) : null;
    if (!n && !noteId && !singleCol()) { const first = filtered()[0] || s.notes[0]; if (first) { n = first; noteId = first.id; } }
    let right = '';
    if (n) right = sheet(n) + margin(n);
    else if (trashed) right = '<div class="sheet"><div class="sheet-inner">' + ui.empty('This note is in Trash', 'Use Undo, or restore it from Trash. Its topic links come back with it.', '<a class="btn" href="trash.html">' + icon('trash') + 'Open Trash</a>') + '</div></div>';
    else if (!singleCol()) right ='<div class="sheet"><div class="sheet-inner">' + ui.empty('No notes yet', 'Notes are rich text documents. Link them to one topic, several, or none.', ui.keycap('New note', '', 'data-act="newNote"')) + '</div></div>';
    if (n) document.title = (n.title || 'Untitled note') + ' | Thread v2 prototype';
    return '<div class="notes-layout' + (n || trashed ? ' has-note' : '') + '">' + index(n) + right + '</div>';
  }

  /* ---------- editor wiring ---------- */
  function editor() { return document.getElementById('edBody'); }

  function setSync(id, value, silent) {
    store.update((s) => { const n = s.notes.find((x) => x.id === id); if (n) n.sync = value; }, { silent: silent !== false });
    patchSync(id);
  }
  function patchSync(id) {
    const n = S.noteById(id);
    if (!n || id !== noteId) return;
    const k = syncKey(n);
    const blk = document.getElementById('syncBlock');
    if (blk) {
      const had = blk.contains(document.activeElement) ? document.activeElement : null;
      const key = had ? (had.dataset.act || had.dataset.change) : null;
      blk.outerHTML = syncBlock(n);
      if (key) { const again = document.querySelector('#syncBlock [data-act="' + key + '"], #syncBlock [data-change="' + key + '"]'); if (again) again.focus(); }
    }
    const ss = document.getElementById('saveState');
    if (ss) ss.innerHTML = '<span class="syncdot ' + SYNC[k].dot + '"></span>' + SYNC[k].text;
    const dot = document.getElementById('ndot-' + id);
    if (dot) { dot.className = 'syncdot ' + SYNC[k].dot; dot.title = SYNC[k].text; }
  }

  function clearPipeline() { pipeline.forEach(clearTimeout); pipeline = []; }
  function runPipeline(id) {
    clearPipeline();
    setSync(id, 'saving');
    const mode = net();
    pipeline.push(setTimeout(() => {
      if (mode === 'localfail') { setSync(id, 'localfail'); return; }
      setSync(id, 'local');
      if (mode === 'offline') return;
      pipeline.push(setTimeout(() => {
        setSync(id, 'syncing');
        pipeline.push(setTimeout(() => setSync(id, mode === 'remotefail' ? 'remotefail' : 'synced'), 1100));
      }, 700));
    }, 600));
  }

  function flush() {
    if (!saveTimer) return;
    clearTimeout(saveTimer);
    saveTimer = null;
    commitBody();
  }
  function commitBody() {
    const ed = editor();
    if (!ed || !noteId) return;
    const html = T.util.sanitize(ed.innerHTML);
    store.update((s) => { const n = s.notes.find((x) => x.id === noteId); if (n) { n.html = html; n.updated = T.nowStamp(); } }, { silent: true });
  }

  function updatePressed() {
    const ed = editor();
    if (!ed) return;
    const inEd = ed.contains(document.getSelection().anchorNode);
    document.querySelectorAll('[data-fmt]').forEach((b) => {
      let on = false;
      if (inEd) {
        const c = b.dataset.fmt;
        if (c === 'bold' || c === 'italic') on = document.queryCommandState(c);
        else {
          const node = document.getSelection().anchorNode;
          const el = node && (node.nodeType === 1 ? node : node.parentElement);
          if (c === 'heading') on = !!(el && el.closest('h2'));
          if (c === 'list') on = !!(el && el.closest('ul:not(.checklist)'));
          if (c === 'checklist') on = !!(el && el.closest('ul.checklist'));
          if (c === 'link') on = !!(el && el.closest('a'));
        }
      }
      b.setAttribute('aria-pressed', String(on));
    });
  }

  /* The last caret or selection inside the editor. A keyboard user who tabs to the toolbar
     moves focus away, so formatting goes back to where they were, not to the top of the note. */
  let lastRange = null;
  function format(cmd) {
    const ed = editor();
    if (!ed) return;
    const s0 = document.getSelection();
    const inside = s0.rangeCount && ed.contains(s0.anchorNode);
    ed.focus();
    if (!inside && lastRange && ed.contains(lastRange.commonAncestorContainer)) {
      const s = document.getSelection();
      s.removeAllRanges();
      s.addRange(lastRange);
    }
    const node = document.getSelection().anchorNode;
    const el = node && (node.nodeType === 1 ? node : node.parentElement);
    if (cmd === 'link') { openLink(el); return; }
    if (cmd === 'bold' || cmd === 'italic') document.execCommand(cmd);
    if (cmd === 'heading') document.execCommand('formatBlock', false, el && el.closest('h2') ? 'P' : 'H2');
    if (cmd === 'list') {
      const cl = el && el.closest('ul.checklist');
      if (cl) { cl.classList.remove('checklist'); cl.querySelectorAll('li').forEach((li) => li.removeAttribute('data-checked')); }
      else document.execCommand('insertUnorderedList');
    }
    if (cmd === 'checklist') {
      const ul = el && el.closest('ul');
      if (ul && ul.classList.contains('checklist')) document.execCommand('insertUnorderedList');
      else {
        if (!ul) document.execCommand('insertUnorderedList');
        const n2 = document.getSelection().anchorNode;
        const e2 = n2 && (n2.nodeType === 1 ? n2 : n2.parentElement);
        const list = e2 && e2.closest('ul');
        if (list) { list.classList.add('checklist'); list.querySelectorAll('li').forEach((li) => { if (!li.hasAttribute('data-checked')) li.setAttribute('data-checked', 'false'); }); }
      }
    }
    changed();
    updatePressed();
  }

  /* Links: a small popover asks for the address. Only http(s) addresses are kept (the sanitizer drops the rest). */
  const link = { range: null, anchor: null, draft: '', err: '' };
  const LINK_ERR = 'Use a full web address that starts with https://';
  function linkPop() {
    const editing = !!link.anchor;
    return '<form class="linkform" data-submit="noteLinkSave" novalidate autocomplete="off">' +
      '<h2>' + (editing ? 'Edit link' : 'Add a link') + '</h2>' +
      '<label class="field"><span>Web address</span><input class="input" type="url" id="linkUrl" data-input="noteLinkDraft" value="' + esc(link.draft) + '" placeholder="https://" maxlength="500" aria-describedby="linkErr"' + (link.err ? ' aria-invalid="true"' : '') + '></label>' +
      '<p class="linkerr" id="linkErr" role="alert">' + esc(link.err) + '</p>' +
      '<div class="linkform-foot">' + (editing ? '<button type="button" class="btn btn-sm btn-ghost" data-act="noteLinkRemove">' + icon('unlink') + 'Remove link</button>' : '') +
        '<button type="submit" class="btn-key"><span>' + (editing ? 'Save link' : 'Add link') + '</span></button></div></form>';
  }
  function openLink(el) {
    const ed = editor();
    const sel = document.getSelection();
    const a = el && el.closest('a');
    link.anchor = a && ed.contains(a) ? a : null;
    link.range = sel.rangeCount ? sel.getRangeAt(0).cloneRange() : null;
    link.draft = link.anchor ? (link.anchor.getAttribute('href') || '') : '';
    link.err = '';
    T.popover('linkPop', document.getElementById('fmt-link'), linkPop, link.anchor ? 'Edit link' : 'Add a link');
  }
  function restoreLinkRange() {
    const ed = editor();
    ed.focus();
    const sel = document.getSelection();
    sel.removeAllRanges();
    if (link.range && ed.contains(link.range.commonAncestorContainer)) sel.addRange(link.range);
    else { const r = document.createRange(); r.selectNodeContents(ed); r.collapse(false); sel.addRange(r); }
  }
  T.act.noteLinkDraft = (el) => { link.draft = el.value; };
  T.act.noteLinkSave = (form) => {
    const url = link.draft.trim();
    const ed = editor();
    if (!ed) { T.closePopover(); return; }
    if (!/^https?:\/\/[^\s/]+\S*$/i.test(url)) {
      link.err = LINK_ERR;
      const inp = form.querySelector('#linkUrl');
      form.querySelector('#linkErr').textContent = LINK_ERR;
      inp.setAttribute('aria-invalid', 'true');
      inp.focus();
      return;
    }
    if (link.anchor && ed.contains(link.anchor)) link.anchor.setAttribute('href', url);
    else {
      restoreLinkRange();
      const sel = document.getSelection();
      if (sel.isCollapsed) {
        const r = sel.getRangeAt(0);
        const host = r.startContainer.nodeType === 1 ? r.startContainer : r.startContainer.parentElement;
        let blk = host.closest('p,h2,h3,li');
        if (!blk || !ed.contains(blk)) blk = ed;
        const pre = document.createRange();
        pre.setStart(blk, 0);
        pre.setEnd(r.startContainer, r.startOffset);
        const before = pre.toString();
        if (before && !/\s$/.test(before)) document.execCommand('insertText', false, ' ');
        document.execCommand('insertHTML', false, '<a href="' + esc(url) + '">' + esc(url) + '</a>&nbsp;');
      }
      else document.execCommand('createLink', false, url);
    }
    ed.focus();
    T.closePopover();
    changed();
    updatePressed();
  };
  T.act.noteLinkRemove = () => {
    const ed = editor();
    const a = link.anchor;
    if (ed && a && ed.contains(a)) {
      ed.focus();
      const r = document.createRange();
      r.selectNodeContents(a);
      const sel = document.getSelection();
      sel.removeAllRanges();
      sel.addRange(r);
      document.execCommand('unlink');
      sel.collapseToEnd();
      changed();
    }
    T.closePopover();
    updatePressed();
  };

  function changed() {
    if (!noteId) return;
    clearPipeline();
    setSync(noteId, 'saving');
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => { saveTimer = null; commitBody(); runPipeline(noteId); }, 450);
  }

  function mount(root) {
    const ed = root.querySelector('#edBody');
    const title = root.querySelector('#edTitle');
    root.querySelectorAll('[data-fmt]').forEach((b) => {
      b.addEventListener('mousedown', (e) => e.preventDefault());
      b.addEventListener('click', () => format(b.dataset.fmt));
    });
    if (title) {
      title.addEventListener('input', () => {
        const v = title.value;
        root.querySelectorAll('[data-title-for="' + noteId + '"]').forEach((e) => { e.textContent = v || 'Untitled note'; });
        store.update((s) => { const n = s.notes.find((x) => x.id === noteId); if (n) { n.title = v.trim(); n.updated = T.nowStamp(); } }, { silent: true });
        changedTitle();
      });
      title.addEventListener('keydown', (e) => { if (e.key === 'Enter' && ed) { e.preventDefault(); ed.focus(); } });
      if (focusNew && !mount.done) { mount.done = true; title.focus(); title.select(); }
    }
    if (!ed) return;
    ed.addEventListener('input', changed);
    ed.addEventListener('blur', flush);
    ed.addEventListener('keyup', updatePressed);
    ed.addEventListener('mouseup', updatePressed);
    ed.addEventListener('paste', (e) => {
      e.preventDefault();
      const html = e.clipboardData.getData('text/html');
      if (html) document.execCommand('insertHTML', false, T.util.sanitize(html));
      else document.execCommand('insertText', false, e.clipboardData.getData('text/plain'));
    });
    ed.addEventListener('click', (e) => {
      const li = e.target.closest('ul.checklist > li');
      if (!li) return;
      const r = li.getBoundingClientRect();
      if (e.clientX - r.left > 30) return;
      li.setAttribute('data-checked', li.getAttribute('data-checked') === 'true' ? 'false' : 'true');
      changed();
    });
    ed.addEventListener('keydown', (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
        const node = document.getSelection().anchorNode;
        const el = node && (node.nodeType === 1 ? node : node.parentElement);
        const li = el && el.closest('ul.checklist > li');
        if (li) { e.preventDefault(); li.setAttribute('data-checked', li.getAttribute('data-checked') === 'true' ? 'false' : 'true'); changed(); }
      }
    });
  }
  let titleTimer = null;
  function changedTitle() { clearTimeout(titleTimer); titleTimer = setTimeout(() => runPipeline(noteId), 500); }

  /* ---------- actions ---------- */
  T.act.noteFilter = (el) => { filter = el.dataset.value; T.render(); };
  T.act.noteFilterTopic = (el) => { filter = el.value || 'all'; T.render(); };
  T.act.newNote = () => {
    const id = T.util.uid('n');
    const topicIds = filter !== 'all' && filter !== 'standalone' && filter !== 'inbox' ? [filter] : [];
    store.update((s) => s.notes.push({ id: id, title: '', topicIds: topicIds, inbox: false, updated: T.nowStamp(), sync: 'synced', html: '' }));
    T.go('notes.html?id=' + id + '&new=1');
  };
  T.act.noteLinkTopic = (el) => {
    const t = el.value;
    if (!t) return;
    flush();
    store.update((s) => { const n = s.notes.find((x) => x.id === noteId); if (n.topicIds.indexOf(t) === -1) n.topicIds.push(t); n.inbox = false; });
    T.toast('Linked to ' + S.topicById(t).name);
  };
  T.act.noteUnlinkTopic = (el) => {
    const t = el.dataset.topic;
    flush();
    store.update((s) => { const n = s.notes.find((x) => x.id === noteId); n.topicIds = n.topicIds.filter((x) => x !== t); });
    T.toast('Unlinked from ' + S.topicById(t).name + '. The note stays here.', { undo: () => store.update((s) => { const n = s.notes.find((x) => x.id === noteId); if (n && n.topicIds.indexOf(t) === -1) n.topicIds.push(t); }) });
  };
  T.act.noteNet = (el) => {
    const prev = net();
    store.update((s) => { s.sim.notesNet = el.value; }, { silent: true });
    patchSync(noteId);
    if (prev === 'offline' && el.value !== 'offline') {
      T.state().notes.filter((n) => n.sync === 'local').forEach((n) => runPipelineFromLocal(n.id));
      T.toast('Back online. Saved changes are syncing.');
    }
  };
  function runPipelineFromLocal(id) {
    setSync(id, 'syncing');
    setTimeout(() => setSync(id, net() === 'remotefail' ? 'remotefail' : 'synced'), 1100);
  }
  T.act.noteReplay = () => { flush(); runPipeline(noteId); };
  T.act.noteRetry = () => { flush(); runPipeline(noteId); };
  T.act.noteCopy = () => {
    const text = T.util.noteText(editor() ? editor().innerHTML : '');
    if (navigator.clipboard) navigator.clipboard.writeText(text).then(() => T.toast('Note text copied'), () => T.toast('Copy is blocked in this browser. Select the text instead.'));
  };
  T.act.noteMakeConflict = () => {
    flush();
    store.update((s) => {
      const n = s.notes.find((x) => x.id === noteId);
      n.conflict = { at: TM.DEMO_DATE + 'T10:48', device: 'Office laptop (demo)', html: (n.html || '') + '<p>Added on the other device while this one was offline.</p>' };
      n.sync = 'conflict';
    });
  };
  T.act.resolveConflict = (el) => {
    const n = S.noteById(noteId);
    if (!n || !n.conflict) return;
    const how = el.dataset.value;
    const copyId = T.util.uid('n');
    store.update((s) => {
      const note = s.notes.find((x) => x.id === noteId);
      const other = { id: copyId, title: note.title + ', ' + note.conflict.device.replace(/ \(demo\)$/, '') + ' copy', topicIds: note.topicIds.slice(), inbox: false, updated: note.conflict.at, sync: 'synced', html: note.conflict.html };
      const mine = { id: copyId, title: note.title + ' (this device)', topicIds: note.topicIds.slice(), inbox: false, updated: note.updated, sync: 'synced', html: note.html };
      if (how === 'both') s.notes.push(other);
      if (how === 'mine') s.trash.unshift({ id: T.util.uid('tr'), kind: 'note', deletedAt: T.nowStamp(), context: 'Version not kept in a conflict', item: other, reminders: [] });
      if (how === 'theirs') { s.trash.unshift({ id: T.util.uid('tr'), kind: 'note', deletedAt: T.nowStamp(), context: 'Version not kept in a conflict', item: mine, reminders: [] }); note.html = note.conflict.html; }
      delete note.conflict;
      note.sync = 'synced';
      note.updated = T.nowStamp();
    });
    if (how === 'both') T.toast('Kept both. The other version is a new note.', { href: 'notes.html?id=' + copyId, linkLabel: 'Open it' });
    else T.toast('Resolved. The other version is in Trash.', { href: 'trash.html', linkLabel: 'Open Trash' });
  };
  T.act.deleteNote = () => { flush(); clearPipeline(); T.trash('note', noteId); };

  document.addEventListener('selectionchange', () => {
    const ed = editor();
    const s = document.getSelection();
    if (ed && s.rangeCount && ed.contains(s.anchorNode)) lastRange = s.getRangeAt(0).cloneRange();
    updatePressed();
  });
  window.addEventListener('beforeunload', flush);

  T.views.notes = { title: 'Notes', bleed: true, render: render, mount: mount };
})();
