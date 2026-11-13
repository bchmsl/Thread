/* Topic Details: the opened folder. Goal and continuation context first, then collapsible sections.
   Jira and PR detail opens in a side panel on wide screens and a routed page on narrow ones. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const TM = T.time;
  const ui = T.ui;
  const esc = T.esc;
  const icon = T.icon;
  const store = T.store;

  const tid = T.param('id');
  let panel = null;
  let showDoneTasks = false;

  function section(key, title, count, body, iconName) {
    const collapsed = !!T.state().prefs.sections[tid + ':' + key];
    return '<section class="sec" aria-labelledby="sh-' + key + '"><div class="sec-head"><h2 id="sh-' + key + '" style="flex:1;display:flex;font-size:inherit">' +
      '<button type="button" class="sec-toggle" aria-expanded="' + !collapsed + '" aria-controls="sb-' + key + '" data-act="toggleSec" data-key="' + key + '">' + icon('chevronDown') +
      (iconName ? icon(iconName) : '') + '<span>' + esc(title) + '</span><span class="count-num">' + count + '</span></button></h2></div>' +
      '<div class="sec-body" id="sb-' + key + '"' + (collapsed ? ' hidden' : '') + '>' + body + '</div></section>';
  }

  function tasksSec(t) {
    const all = S.topicTasks(t.id);
    const open = all.filter((k) => !k.done);
    const done = all.filter((k) => k.done);
    const body = '<form class="inline-add" data-submit="topicAddTask"><label class="vh" for="addTask">New task in ' + esc(t.name) + '</label>' +
      '<input class="input" id="addTask" name="title" placeholder="Add a task to ' + esc(t.name) + '" autocomplete="off" maxlength="140"><button type="submit" class="btn">' + icon('plus') + 'Add</button></form>' +
      (open.length ? open.map((k) => ui.taskRow(k, { topic: false })).join('') : '<p class="tray-empty">No open tasks.</p>') +
      (done.length ? '<button type="button" class="btn btn-sm btn-ghost done-toggle" data-act="toggleDoneTasks" aria-expanded="' + showDoneTasks + '">' + icon(showDoneTasks ? 'chevronDown' : 'chevronRight') + (showDoneTasks ? 'Hide' : 'Show') + ' ' + done.length + ' done</button>' +
        (showDoneTasks ? done.map((k) => ui.taskRow(k, { topic: false })).join('') : '') : '');
    return section('tasks', 'Tasks', open.length, body, 'check');
  }

  function notesSec(t) {
    const notes = S.topicNotes(t.id);
    const free = T.state().notes.filter((n) => n.topicIds.indexOf(t.id) === -1);
    const body = notes.map((n) => {
      const others = n.topicIds.filter((x) => x !== t.id).map((x) => S.topicById(x)).filter(Boolean);
      return '<div class="notewrap"><a class="notecard" href="notes.html?id=' + n.id + '"><h3>' + esc(n.title) + '</h3><p>' + esc(T.util.noteText(n.html) || 'Empty note') + '</p>' +
        '<span class="meta"><span>Edited ' + esc(TM.fmtStamp(n.updated)) + '</span>' + (others.length ? '<span>Also in</span>' + others.map((o) => ui.ttab(o, { size: 'sm', link: false })).join('') : '') + '</span></a>' +
        '<button type="button" class="iconbtn iconbtn-sm" data-act="unlinkNote" data-id="' + n.id + '" aria-label="Unlink ' + esc(n.title) + ' from ' + esc(t.name) + '">' + icon('unlink') + '</button></div>';
    }).join('') +
      '<div class="linker"><button type="button" class="btn btn-sm" data-act="topicNewNote">' + icon('plus') + 'New note here</button>' +
      (free.length ? '<form class="linker" data-submit="topicLinkNote" style="margin:0;flex:1"><label class="vh" for="linkNote">Existing note to link</label><select class="select select-sm" id="linkNote" name="note"><option value="">Link an existing note</option>' +
        free.map((n) => '<option value="' + n.id + '">' + esc(n.title) + '</option>').join('') + '</select><button type="submit" class="btn btn-sm">' + icon('link') + 'Link</button></form>' : '') + '</div>';
    return section('notes', 'Notes', notes.length, (notes.length ? '' : '<p class="tray-empty">No notes yet.</p>') + body, 'note');
  }

  function extSec(kind, t) {
    const s = T.state();
    const items = kind === 'jira' ? s.jira.filter((j) => j.topicIds.indexOf(t.id) !== -1) : s.prs.filter((p) => p.topicIds.indexOf(t.id) !== -1);
    const free = kind === 'jira' ? s.jira.filter((j) => !j.remoteOnly && j.topicIds.indexOf(t.id) === -1) : s.prs.filter((p) => p.topicIds.indexOf(t.id) === -1);
    const rows = items.map((it) => {
      const id = kind === 'jira' ? it.key : it.id;
      const isSel = panel && panel.kind === kind && panel.id === id;
      const label = kind === 'jira' ? it.key : '#' + it.number;
      const open = kind === 'jira' ?
        '<span class="line1"><span class="jkey">' + esc(it.key) + '</span><span class="t">' + esc(it.title) + '</span></span><span class="line2"><span class="jstatus">' + esc(it.status) + '</span><span>' + esc(it.priority) + '</span><span>Updated ' + esc(TM.fmtStamp(it.updated)) + '</span></span>' :
        '<span class="line1">' + ui.prPill(it) + '<span class="t">' + esc(it.title) + '</span><span class="pr-num" style="margin-left:auto">#' + it.number + '</span></span><span class="line2"><span>' + esc(it.repo) + '</span>' + ui.ci(it.ci) + ui.review(it) + '</span>';
      return '<div class="xrow' + (isSel ? ' selected' : '') + '"><button type="button" class="xrow-open" data-act="openExt" data-kind="' + kind + '" data-id="' + esc(id) + '" aria-expanded="' + !!isSel + '">' + open + '</button>' +
        '<button type="button" class="iconbtn iconbtn-sm" data-act="extUnlink" data-kind="' + kind + '" data-id="' + esc(id) + '" data-topic="' + t.id + '" aria-label="Unlink ' + esc(label) + ' from ' + esc(t.name) + '">' + icon('unlink') + '</button></div>';
    }).join('');
    const linker = free.length ? '<form class="linker" data-submit="extLink" data-kind="' + kind + '" data-id=""><label class="vh" for="lk-' + kind + '">' + (kind === 'jira' ? 'Downloaded Jira issue to link' : 'Pull request to link') + '</label>' +
      '<select class="select select-sm" id="lk-' + kind + '" name="item"><option value="">' + (kind === 'jira' ? 'Link a downloaded issue' : 'Link a pull request') + '</option>' +
      free.map((it) => '<option value="' + esc(kind === 'jira' ? it.key : it.id) + '">' + esc(kind === 'jira' ? it.key + '  ' + it.title : '#' + it.number + '  ' + it.title) + '</option>').join('') + '</select>' +
      '<input type="hidden" name="topic" value="' + t.id + '"><button type="submit" class="btn btn-sm">' + icon('link') + 'Link</button></form>' : '';
    const title = kind === 'jira' ? 'Jira' : 'Pull requests';
    return section(kind, title, items.length, (items.length ? rows : '<p class="tray-empty">Nothing linked. Linking keeps a reference; the ' + (kind === 'jira' ? 'issue' : 'pull request') + ' stays read-only.</p>') + linker, kind === 'jira' ? 'ticket' : 'pr');
  }

  function remindersSec(t) {
    const list = S.topicReminders(t.id).slice().sort((a, b) => (a.at < b.at ? -1 : 1));
    const now = TM.now();
    const rows = list.map((r) => {
      const due = !r.dismissed && TM.parse(r.at) <= now;
      return '<div class="remrow' + (due ? ' due' : '') + '"><span class="when num">' + esc(TM.fmtTime(r.at)) + '<small>' + esc(TM.fmtDay(r.at)) + '</small></span>' +
        '<div style="min-width:0"><div class="what">' + esc(r.text) + (r.dismissed ? ' <span class="faint">(dismissed)</span>' : '') + '</div><div class="tgt">' + (r.target.kind === 'topic' ? 'This topic' : 'Task: ' + esc(S.reminderTargetLabel(r))) + '</div></div>' +
        '<button type="button" class="iconbtn iconbtn-sm" data-act="removeReminder" data-id="' + r.id + '" aria-label="Remove reminder ' + esc(r.text) + '">' + icon('x') + '</button></div>';
    }).join('');
    return section('reminders', 'Reminders', list.length, (rows || '<p class="tray-empty">No reminders.</p>') +
      '<button type="button" class="btn btn-sm" style="margin-top:8px" data-act="remind" data-kind="topic" data-id="' + t.id + '">' + icon('bell') + 'Remind me about this topic</button>', 'bell');
  }

  function sidePanel(t) {
    if (!panel || T.narrow()) return '';
    const item = panel.kind === 'jira' ? S.jiraByKey(panel.id) : S.prById(panel.id);
    if (!item) return '';
    const label = panel.kind === 'jira' ? 'Jira issue ' + item.key : 'Pull request #' + item.number;
    const st = T.ext.sourceState(panel.kind === 'jira' ? 'jira' : 'github', panel.kind === 'jira' ? 'issues' : 'pull requests');
    const href = 'detail.html?kind=' + panel.kind + (panel.kind === 'jira' ? '&key=' : '&id=') + encodeURIComponent(panel.id) + '&from=topic:' + encodeURIComponent(t.id);
    return '<aside class="xpanel" id="xpanel" aria-label="' + esc(label) + '">' +
      '<div class="xpanel-top"><span class="ctx">Opened from ' + esc(t.name) + '</span><a class="btn btn-sm btn-ghost" href="' + href + '">' + icon('external') + 'Open as page</a>' +
      '<button type="button" class="iconbtn" data-act="closeExt" aria-label="Close ' + esc(label) + '">' + icon('x') + '</button></div>' +
      (st.blocked ? st.banner : (st.banner ? '<div style="margin-bottom:14px">' + st.banner + '</div>' : '') + (panel.kind === 'jira' ? T.ext.jiraDetail(item, { idPrefix: 'pn' }) : T.ext.prDetail(item, { idPrefix: 'pn' }))) +
    '</aside>';
  }

  function render() {
    const t = S.topicById(tid);
    T.ctxTopicId = t ? t.id : null;
    if (!t) {
      const inTrash = T.state().trash.find((x) => x.kind === 'topic' && x.item.id === tid);
      return '<a class="backlink" href="topics.html">' + icon('chevronLeft') + 'All topics</a>' +
        (inTrash ? ui.empty('This topic is in Trash', 'Restore it to bring back its links to tasks, notes, issues and pull requests.', '<a class="btn" href="trash.html">' + icon('trash') + 'Open Trash</a>') :
          ui.empty('Topic not found', 'It may have been deleted on another device.', '<a class="btn" href="topics.html">All topics</a>'));
    }
    document.title = t.name + ' | Thread v2 prototype';
    const s = T.state();
    const st = S.statusById(t.statusId);
    const c = S.topicColor(t);
    const prog = S.topicProgress(t.id);
    return '<a class="backlink" href="topics.html">' + icon('chevronLeft') + 'All topics</a>' +
      '<section class="folder" style="--c:' + c + '" aria-labelledby="topicName">' +
        '<div class="folder-tab"><h1 id="topicName">' + esc(t.name) + '</h1></div>' +
        '<div class="folder-body">' +
          '<div class="folder-meta">' +
            '<div class="status-ctl">' + ui.status(st, { lg: true, noName: true }) + '<label class="vh" for="topicStatus">Status</label><select class="select" id="topicStatus" data-change="topicStatus" data-id="' + t.id + '">' +
              s.statuses.map((x) => '<option value="' + x.id + '"' + (x.id === t.statusId ? ' selected' : '') + '>' + esc(x.name) + '</option>').join('') + '</select></div>' +
            '<span class="faint" style="font-size:12.5px;max-width:30ch">' + esc(T.CATEGORIES[st.cat].hint) + '</span>' +
            ui.pips(prog, c) +
            '<span class="spacer"></span>' + ui.keycap('Add to this topic', 'N', 'data-act="capture" data-topic="' + t.id + '"') +
          '</div>' +
          '<div class="folder-goal"><label class="vh" for="topicGoal">Goal</label><textarea class="goal-input grow oneline" rows="1" id="topicGoal" placeholder="What does done look like?" data-change="topicField" data-field="goal" data-id="' + t.id + '" maxlength="200">' + esc(t.goal) + '</textarea></div>' +
          '<div class="resume">' +
            '<div class="resume-box"><label class="label" for="topicLeft">' + icon('edit') + 'Where I left off</label><textarea id="topicLeft" rows="3" data-change="topicField" data-field="leftOff" data-id="' + t.id + '" placeholder="A sentence for future you">' + esc(t.leftOff) + '</textarea></div>' +
            '<div class="resume-box next-box"><label class="label" for="topicNext">' + icon('play') + 'Next step</label><textarea class="grow oneline" rows="1" id="topicNext" data-change="topicField" data-field="next" data-id="' + t.id + '" placeholder="The very next action" maxlength="140">' + esc(t.next) + '</textarea><span class="saved-hint">Saved on this device as you leave each field.</span></div>' +
          '</div>' +
        '</div></section>' +
      '<div class="topic-cols"><div>' + tasksSec(t) + notesSec(t) + '</div><div>' + extSec('jira', t) + extSec('pr', t) + remindersSec(t) + '</div></div>' +
      '<div style="margin-top:28px"><button type="button" class="btn btn-sm btn-ghost btn-quiet-danger" data-act="deleteTopic">' + icon('trash') + 'Move topic to Trash</button></div>' +
      sidePanel(t);
  }

  function mount(root) {
    const p = root.querySelector('#xpanel');
    if (p && panel && panel.focus) { panel.focus = false; const h = p.querySelector('.xd-title'); if (h) h.focus(); }
  }

  function closePanel() {
    if (!panel) return;
    const was = panel;
    panel = null;
    T.render();
    const btn = document.querySelector('.xrow-open[data-kind="' + was.kind + '"][data-id="' + CSS.escape(was.id) + '"]');
    if (btn) btn.focus();
  }

  T.act.toggleSec = (el) => store.update((s) => { const k = tid + ':' + el.dataset.key; s.prefs.sections[k] = !s.prefs.sections[k]; });
  T.act.toggleDoneTasks = () => { showDoneTasks = !showDoneTasks; T.render(); };
  T.act.topicStatus = (el) => {
    const before = S.topicCat(S.topicById(tid));
    store.update((s) => { s.topics.find((x) => x.id === tid).statusId = el.value; });
    const st = S.statusById(el.value);
    let note = '';
    if (before !== 'active' && st.cat === 'active') note = ' It now shows in Today.';
    if (before === 'active' && st.cat !== 'active') note = ' It leaves Today.';
    T.toast('Status: ' + st.name + '.' + note);
  };
  T.act.topicField = (el) => store.update((s) => { s.topics.find((x) => x.id === tid)[el.dataset.field] = el.value.trim(); }, { silent: true });
  T.act.topicAddTask = (form) => {
    const title = String(new FormData(form).get('title') || '').trim();
    if (!title) return;
    store.update((s) => s.tasks.push({ id: T.util.uid('k'), title: title, topicId: tid, done: false, planned: null, deadline: null, inbox: false }));
    setTimeout(() => { const i = document.getElementById('addTask'); if (i) { i.value = ''; i.focus(); } }, 30);
  };
  T.act.topicNewNote = () => {
    const id = T.util.uid('n');
    store.update((s) => s.notes.push({ id: id, title: 'Untitled note', topicIds: [tid], inbox: false, updated: T.nowStamp(), sync: 'synced', html: '' }));
    T.go('notes.html?id=' + id + '&new=1');
  };
  T.act.topicLinkNote = (form) => {
    const nid = new FormData(form).get('note');
    if (!nid) { form.querySelector('select').focus(); return; }
    store.update((s) => { const n = s.notes.find((x) => x.id === nid); if (n.topicIds.indexOf(tid) === -1) n.topicIds.push(tid); n.inbox = false; });
    T.toast('Note linked. It keeps its other topics.');
  };
  T.act.unlinkNote = (el) => {
    const nid = el.dataset.id;
    store.update((s) => { const n = s.notes.find((x) => x.id === nid); n.topicIds = n.topicIds.filter((x) => x !== tid); });
    T.toast('Unlinked. The note is still in Notes.', { undo: () => store.update((s) => { const n = s.notes.find((x) => x.id === nid); if (n.topicIds.indexOf(tid) === -1) n.topicIds.push(tid); }) });
  };
  /* The Topic page's link forms pass the item in a select; reuse the shared action. */
  const sharedLink = T.act.extLink;
  T.act.extLink = (form) => {
    const fd = new FormData(form);
    if (fd.get('item') !== null) {
      const item = fd.get('item');
      if (!item) { form.querySelector('select').focus(); return; }
      form.dataset.id = item;
    }
    sharedLink(form);
  };
  T.act.openExt = (el) => {
    const kind = el.dataset.kind;
    const id = el.dataset.id;
    if (T.narrow()) { T.go('detail.html?kind=' + kind + (kind === 'jira' ? '&key=' : '&id=') + encodeURIComponent(id) + '&from=topic:' + encodeURIComponent(tid)); return; }
    if (panel && panel.kind === kind && panel.id === id) { closePanel(); return; }
    panel = { kind: kind, id: id, focus: true };
    T.render();
  };
  T.act.closeExt = () => closePanel();
  T.act.deleteTopic = () => { panel = null; T.trash('topic', tid); };

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && panel && !document.querySelector('dialog[open]') && !document.querySelector('.popover')) { e.preventDefault(); closePanel(); }
  });

  T.views.topic = { title: 'Topic', render: render, mount: mount };
})();
