/* Interactions: actions, forms, dialogs, the rich-text editor, global search,
   board drag and drop, the focus timer concept and the demo controls. */
(function () {
  'use strict';
  const T = window.Thread;
  const { html, raw, icon, glyph, Q, ui } = T;
  const A = T.actions;
  const CH = T.changes;
  const IN = T.inputs;
  const F = T.forms;
  const C = T.c;
  const S = () => T.state;
  const $ = (sel, root) => (root || document).querySelector(sel);
  const DATA_KEYS = ['topics', 'tasks', 'notes', 'reminders', 'links', 'trash', 'statuses'];

  /* ---------------------------------------------------------------- helpers */
  function snapshot() { const o = {}; DATA_KEYS.forEach((k) => { o[k] = S()[k]; }); return JSON.stringify(o); }
  function undoable(msg, fn, extra) {
    const snap = snapshot();
    T.commit(fn);
    T.toast(msg, Object.assign({ action: { label: 'Undo', fn: () => T.commit((s) => Object.assign(s, JSON.parse(snap))) } }, extra || {}));
  }
  /* Selectors separated by commas are tried in order, so the first one that exists wins. */
  function focusSoon(sel) {
    setTimeout(() => {
      let el = null;
      if (typeof sel !== 'string') el = sel;
      else sel.split(/\s*,\s*/).some((s) => { el = $(s); return !!el; });
      if (el) el.focus();
    }, 30);
  }
  function short(s, n) { s = String(s || ''); return s.length > n ? s.slice(0, n - 1) + '…' : s; }
  function paint(id, fn) {
    const el = document.getElementById(id);
    if (!el) return;
    const a = document.activeElement;
    const aid = a && el.contains(a) ? a.id : null;
    let sel = null;
    try { if (aid && typeof a.selectionStart === 'number') sel = [a.selectionStart, a.selectionEnd]; } catch (e) { sel = null; }
    el.innerHTML = T.flat(fn());
    if (aid) { const n = document.getElementById(aid); if (n) { n.focus(); if (sel && n.setSelectionRange) { try { n.setSelectionRange(sel[0], sel[1]); } catch (e) { /* ignore */ } } } }
  }
  function closeBtn(id) { return html`<button class="btn btn-ghost btn-sm btn-icon" type="button" data-action="close-dialog" data-id="${id}" aria-label="Close">${icon('x')}</button>`; }
  A['close-dialog'] = (a) => T.closeDialog(a.dataset.id);

  /* -------------------------------------------------------------- navigation */
  A['toggle-nav'] = () => {
    ui.navOpen = !ui.navOpen;
    T.render();
    if (ui.navOpen) focusSoon('.sidebar .nav-item[aria-current="page"], .sidebar .nav-item');
  };
  A['close-nav'] = () => { ui.navOpen = false; T.render(); focusSoon('#nav-toggle'); };
  A['open-external'] = (a) => T.toast('Demo only. The real app opens this in ' + a.dataset.what + ' in a new tab. No link is followed here.');

  /* -------------------------------------------------------------- Welcome */
  A['welcome-signin'] = () => {
    ui.welcomeStep = 'setup';
    T.state.session.signedIn = true;
    T.persist();
    T.render();
    focusSoon('.choice input:checked');
  };
  F['welcome-setup'] = (f) => {
    const ws = new FormData(f).get('ws') || 'example';
    T.resetState(ws);
    T.state.session.signedIn = true;
    T.persistNow();
    ui.welcomeStep = null;
    T.go('/today');
    T.toast(ws === 'empty' ? 'Empty workspace ready. Press C to capture your first Task.' : 'Example workspace loaded. All data is synthetic.');
  };

  /* ------------------------------------------------------------------ Tasks */
  CH['toggle-task'] = (el) => {
    const t = Q.task(el.dataset.id);
    if (!t) return;
    const done = el.checked;
    undoable(done ? 'Completed “' + short(t.title, 40) + '”' : 'Marked “' + short(t.title, 40) + '” as not done', (s) => {
      const x = s.tasks.find((k) => k.id === t.id);
      x.done = done;
      if (done) x.completedAt = s.sim.now; else delete x.completedAt;
    });
  };
  A['plan-today'] = (a) => {
    const t = Q.task(a.dataset.id);
    undoable('Planned “' + short(t.title, 40) + '” for today', (s) => { const x = s.tasks.find((k) => k.id === t.id); x.planned = T.today(); x.inbox = false; });
  };
  F['today-add'] = (f) => {
    const d = new FormData(f);
    const title = String(d.get('title') || '').trim();
    if (!title) return;
    const topic = String(d.get('topic') || '') || null;
    ui.todayTopic = topic || '';
    T.commit((s) => s.tasks.push({ id: T.uid('k'), title, topicId: topic, done: false, planned: T.today(), deadline: null, inbox: false, createdAt: s.sim.now }));
    focusSoon('#today-add-title');
  };
  F['topic-add'] = (f) => {
    const d = new FormData(f);
    const title = String(d.get('title') || '').trim();
    if (!title) return;
    const plan = d.get('plan') === 'today';
    T.commit((s) => s.tasks.push({ id: T.uid('k'), title, topicId: f.dataset.topic, done: false, planned: plan ? T.today() : null, deadline: null, inbox: false, createdAt: s.sim.now }));
    focusSoon('#topic-add-title');
  };

  /* Task details drawer */
  function taskDrawer(id) {
    const t = Q.task(id);
    if (!t) return html`<div class="dlg-body"><p>This Task no longer exists.</p></div>`;
    const topic = t.topicId ? Q.topic(t.topicId) : null;
    const b = Q.bucket(t);
    const doneToday = t.done && t.completedAt && t.completedAt.slice(0, 10) === T.today();
    const effects = [];
    if (t.done) effects.push([icon('check', 'icon-sm'), doneToday ? 'Completed today. Stays visible on Today, struck through, until the day ends.' : 'Completed ' + T.fmtWhen(t.completedAt) + '.']);
    if (!t.done || doneToday) {
      if (b === 'overdue') effects.push([icon('alert', 'icon-sm'), 'On Today, under Overdue, because the deadline passed.']);
      else if (b === 'planned') effects.push([icon('today', 'icon-sm'), t.planned === T.today() ? 'On Today, under Planned for today.' : 'On Today, because it is due later today.']);
      else if (b === 'earlier') effects.push([icon('today', 'icon-sm'), 'On Today, under Planned earlier, until you finish or replan it.']);
      else if (!t.done) effects.push([icon('today', 'icon-sm'), t.planned ? 'Not on Today yet. Shows on ' + T.fmtDay(t.planned) + '.' : t.deadline ? 'Not on Today yet. Shows on the day it is due.' : 'Not on Today. It has no planned day or deadline.']);
    }
    if (topic) effects.push([html`<span class="thread" style="${C.tc(topic)}"></span>`, 'In ' + topic.name + ', under Tasks.']);
    else if (t.inbox) effects.push([icon('inbox', 'icon-sm'), 'In the Inbox until it gets a Topic or a plan.']);
    else effects.push([icon('search', 'icon-sm'), 'Standalone. Find it with search.']);
    const rems = Q.remindersFor('task', t.id).sort((a, c) => a.at.localeCompare(c.at));
    const dl = t.deadline || '';
    return html`
      <div class="td-head">
        <input class="check" type="checkbox" ${t.done ? 'checked' : ''} data-change="toggle-task" data-id="${t.id}" id="td-check" aria-label="${t.done ? 'Mark as not done' : 'Complete this Task'}">
        <label class="sr-only" for="td-title">Task title</label>
        <textarea class="td-title" id="td-title" rows="2" data-input="task-title" data-change="task-title-done" data-id="${t.id}">${t.title}</textarea>
        ${closeBtn('dlg-task')}
      </div>
      <div class="dlg-body">
        <label class="field"><span>Topic</span>
          <select class="select" id="td-topic" data-change="task-topic" data-id="${t.id}">${T.topicOptions(t.topicId, { none: t.inbox ? 'Inbox, no Topic yet' : 'No Topic (standalone)' })}</select>
        </label>
        <div class="date-row">
          <label class="field"><span>Planned day</span><input class="input" type="date" id="td-planned" value="${t.planned || ''}" data-change="task-planned" data-id="${t.id}"></label>
          ${t.planned === T.today() ? html`<button class="btn" type="button" data-action="task-plan" data-v="clear" data-id="${t.id}" id="td-plan-btn">Clear</button>` : html`<button class="btn" type="button" data-action="task-plan" data-v="today" data-id="${t.id}" id="td-plan-btn">Today</button>`}
        </div>
        <div class="field">
          <span class="field-label" id="td-dl-l">Deadline</span>
          <div class="date-pair">
            <input class="input" type="date" id="td-dl-date" aria-labelledby="td-dl-l" value="${dl.slice(0, 10)}" data-change="task-deadline" data-id="${t.id}">
            <input class="input" type="time" id="td-dl-time" aria-label="Deadline time" value="${dl.slice(11, 16)}" data-change="task-deadline" data-id="${t.id}" ${dl ? '' : 'disabled'}>
          </div>
          <small>Planned day is when you mean to work on it. Deadline is when it must be done. A deadline without a time uses 18:00.</small>
        </div>
        <section class="effect" aria-live="polite"><h3>${icon('eye', 'icon-sm')}Where this Task shows</h3><ul>${effects.map((e) => html`<li>${e[0]}<span>${e[1]}</span></li>`)}</ul></section>
        <div class="field">
          <span class="field-label">Reminders</span>
          ${rems.length ? rems.map((r) => html`<div class="rem-box"><span class="rem-time">${T.fmtTime(r.at)}<small>${T.fmtDay(r.at.slice(0, 10))}</small></span><span style="flex:1;min-width:0">${r.text || 'Reminder'}</span><button class="btn btn-ghost btn-sm" type="button" data-action="edit-rem" data-id="${r.id}" id="tdr-${r.id}">Edit</button></div>`) : html`<p class="help">No reminders. A reminder is separate from the planned day and deadline.</p>`}
          <div><button class="btn btn-sm" type="button" data-action="new-rem" data-type="task" data-id="${t.id}" id="td-add-rem">${icon('bell', 'icon-sm')}Add reminder</button></div>
        </div>
      </div>
      <div class="dlg-foot">
        <button class="btn btn-ghost btn-danger" type="button" data-action="task-trash" data-id="${t.id}">${icon('trash', 'icon-sm')}Move to Trash</button>
        <span class="spacer"></span>
        <button class="btn btn-primary" type="button" data-action="close-dialog" data-id="dlg-task">Done</button>
      </div>`;
  }
  T.openTask = (id) => {
    if (!Q.task(id)) { T.toast('That Task no longer exists.'); return; }
    T.openDialog('dlg-task', () => taskDrawer(id), { className: 'drawer', labelledby: 'td-title' });
  };
  A['open-task'] = (a) => T.openTask(a.dataset.id);
  IN['task-title'] = (el) => { const t = Q.task(el.dataset.id); if (t) T.commit((s) => { s.tasks.find((k) => k.id === t.id).title = el.value.replace(/\n/g, ' '); }, { silent: true }); };
  CH['task-title-done'] = (el) => { const v = el.value.replace(/\n/g, ' ').trim(); T.commit((s) => { const x = s.tasks.find((k) => k.id === el.dataset.id); if (x) x.title = v || 'Untitled Task'; }); };
  CH['task-topic'] = (el) => T.commit((s) => { const x = s.tasks.find((k) => k.id === el.dataset.id); x.topicId = el.value || null; x.inbox = false; });
  CH['task-planned'] = (el) => T.commit((s) => { const x = s.tasks.find((k) => k.id === el.dataset.id); x.planned = el.value || null; if (x.planned) x.inbox = false; });
  A['task-plan'] = (a) => T.commit((s) => { const x = s.tasks.find((k) => k.id === a.dataset.id); x.planned = a.dataset.v === 'today' ? T.today() : null; if (x.planned) x.inbox = false; });
  CH['task-deadline'] = (el) => {
    const d = $('#td-dl-date').value;
    const tm = $('#td-dl-time').value || '18:00';
    T.commit((s) => { const x = s.tasks.find((k) => k.id === el.dataset.id); x.deadline = d ? d + 'T' + tm : null; });
  };
  A['task-trash'] = (a) => {
    const t = Q.task(a.dataset.id);
    T.closeDialog('dlg-task');
    undoable('Moved “' + short(t.title, 40) + '” to Trash.', (s) => trashItem(s, 'task', t.id));
  };

  /* ---------------------------------------------------------------- Trash */
  function trashItem(s, kind, id) {
    const list = kind === 'task' ? s.tasks : s.notes;
    const i = list.findIndex((x) => x.id === id);
    if (i < 0) return;
    const item = list.splice(i, 1)[0];
    s.trash.push({ id: T.uid('tr'), type: kind, deletedAt: s.sim.now, item });
  }
  A.restore = (a) => {
    const e = S().trash.find((x) => x.id === a.dataset.id);
    if (!e) return;
    let msg = '';
    let open = null;
    T.commit((s) => {
      s.trash = s.trash.filter((x) => x.id !== e.id);
      if (e.type === 'task') {
        const t = e.item;
        if (t.topicId && !s.topics.find((x) => x.id === t.topicId)) { t.topicId = null; msg = 'Restored “' + short(t.title, 40) + '” as a standalone Task. Its Topic is gone.'; }
        else msg = 'Restored “' + short(t.title, 40) + '”' + (t.topicId ? ' to ' + Q.topic(t.topicId).name : '') + '.';
        s.tasks.push(t);
        open = () => T.openTask(t.id);
      } else if (e.type === 'note') {
        const n = e.item;
        const before = n.topicIds.length;
        n.topicIds = n.topicIds.filter((id) => s.topics.find((x) => x.id === id));
        msg = before && !n.topicIds.length ? 'Restored “' + short(n.title, 40) + '” as a standalone Note. Its Topic is gone.' : 'Restored “' + short(n.title || 'Untitled note', 40) + '”' + (n.topicIds.length ? ' to ' + Q.topic(n.topicIds[0]).name : '') + '.';
        s.notes.push(n);
        open = () => T.go('/notes/' + n.id);
      } else {
        const t = e.item;
        if (!s.statuses.find((x) => x.id === t.statusId)) t.statusId = Q.defaultStatusId();
        s.topics.push(t);
        e.tasks.forEach((k) => s.tasks.push(k));
        e.notes.forEach((n) => s.notes.push(n));
        e.sharedNoteIds.forEach((nid) => { const n = s.notes.find((x) => x.id === nid); if (n && n.topicIds.indexOf(t.id) < 0) n.topicIds.push(t.id); });
        e.links.jira.forEach((k) => { (s.links.jira[k] = s.links.jira[k] || []).push(t.id); });
        e.links.prs.forEach((k) => { (s.links.prs[k] = s.links.prs[k] || []).push(t.id); });
        msg = 'Restored Topic “' + t.name + '” with its Tasks, Notes and links';
        open = () => T.go('/topic/' + t.id);
      }
    });
    T.toast(msg, { action: { label: 'Open', fn: open } });
    focusSoon('.trash-row .btn, #empty-trash');
  };
  A.purge = (a) => {
    const e = S().trash.find((x) => x.id === a.dataset.id);
    const title = e.type === 'topic' ? e.item.name : (e.item.title || 'Untitled note');
    confirmDialog({ title: 'Delete “' + short(title, 40) + '” forever?', body: 'This cannot be undone.', confirm: 'Delete forever', danger: true,
      run: () => { T.commit((s) => { s.trash = s.trash.filter((x) => x.id !== e.id); }); T.toast('Deleted forever.'); } });
  };
  A['empty-trash'] = () => {
    const n = S().trash.length;
    confirmDialog({ title: 'Empty Trash?', body: n + ' item' + (n === 1 ? '' : 's') + ' will be deleted forever. This cannot be undone. Jira tickets and pull requests are not affected.', confirm: 'Empty Trash', danger: true,
      run: () => { T.commit((s) => { s.trash = []; }); T.toast('Trash emptied.'); } });
  };

  /* ---------------------------------------------------------------- Inbox */
  const parseKey = (k) => { const i = k.indexOf(':'); return { kind: k.slice(0, i), id: k.slice(i + 1) }; };
  function placeItem(s, key, topicId) {
    const p = parseKey(key);
    if (p.kind === 'task') { const t = s.tasks.find((x) => x.id === p.id); if (t) { t.topicId = topicId; t.inbox = false; } }
    else { const n = s.notes.find((x) => x.id === p.id); if (n) { if (n.topicIds.indexOf(topicId) < 0) n.topicIds.push(topicId); n.inbox = false; } }
  }
  CH['inbox-all'] = (el) => { ui.inboxChecked = el.checked ? Q.inbox().map((i) => i.kind + ':' + i.id) : []; T.render(); };
  CH['inbox-check'] = (el) => {
    const k = el.dataset.key;
    ui.inboxChecked = el.checked ? ui.inboxChecked.concat([k]) : ui.inboxChecked.filter((x) => x !== k);
    T.render();
  };
  A['inbox-select'] = (a) => { ui.inboxSel = a.dataset.key; T.render(); };
  CH['bulk-move'] = (el) => {
    const topic = Q.topic(el.value);
    if (!topic) return;
    const keys = ui.inboxChecked.slice();
    ui.inboxChecked = [];
    undoable('Moved ' + keys.length + ' item' + (keys.length === 1 ? '' : 's') + ' to ' + topic.name, (s) => keys.forEach((k) => placeItem(s, k, topic.id)));
  };
  A['bulk-today'] = () => {
    const keys = ui.inboxChecked.filter((k) => k.indexOf('task:') === 0);
    const skipped = ui.inboxChecked.length - keys.length;
    ui.inboxChecked = ui.inboxChecked.filter((k) => k.indexOf('task:') !== 0);
    undoable('Planned ' + keys.length + ' Task' + (keys.length === 1 ? '' : 's') + ' for today' + (skipped ? '. Notes stay in the Inbox.' : ''), (s) => keys.forEach((k) => { const t = s.tasks.find((x) => x.id === parseKey(k).id); t.planned = T.today(); t.inbox = false; }));
  };
  A['bulk-trash'] = () => {
    const keys = ui.inboxChecked.slice();
    ui.inboxChecked = [];
    undoable('Moved ' + keys.length + ' item' + (keys.length === 1 ? '' : 's') + ' to Trash.', (s) => keys.forEach((k) => { const p = parseKey(k); trashItem(s, p.kind, p.id); }));
  };
  function afterTriage() { focusSoon('.inbox-open[aria-pressed="true"], #inbox-capture'); }
  F['triage-move'] = (f) => {
    const topic = Q.topic(new FormData(f).get('topic'));
    if (!topic) return;
    ui.triageTopic = topic.id;
    undoable('Moved to ' + topic.name, (s) => placeItem(s, f.dataset.key, topic.id));
    afterTriage();
  };
  A['triage-today'] = (a) => { undoable('Planned for today, without a Topic', (s) => { const t = s.tasks.find((x) => x.id === a.dataset.id); t.planned = T.today(); t.inbox = false; }); afterTriage(); };
  A['triage-keep'] = (a) => {
    const p = parseKey(a.dataset.key);
    undoable('Kept as a standalone ' + (p.kind === 'task' ? 'Task' : 'Note'), (s) => { const l = p.kind === 'task' ? s.tasks : s.notes; l.find((x) => x.id === p.id).inbox = false; });
    afterTriage();
  };
  A['triage-open'] = (a) => { const p = parseKey(a.dataset.key); if (p.kind === 'task') T.openTask(p.id); else T.go('/notes/' + p.id); };
  A['triage-trash'] = (a) => { const p = parseKey(a.dataset.key); undoable('Moved to Trash.', (s) => trashItem(s, p.kind, p.id)); afterTriage(); };

  /* ------------------------------------------------------- Quick capture */
  let cap = null;
  function captureBody() {
    const isTask = cap.type === 'task';
    const dest = cap.dest ? Q.topic(cap.dest) : null;
    return html`<div class="dlg-head"><h2 id="cap-h">Quick capture</h2>
        <div class="seg" role="group" aria-label="Capture type">
          <button type="button" data-action="cap-type" data-v="task" aria-pressed="${isTask}" id="cap-type-task">${icon('check', 'icon-sm')}Task</button>
          <button type="button" data-action="cap-type" data-v="note" aria-pressed="${!isTask}" id="cap-type-note">${icon('note', 'icon-sm')}Note</button>
        </div></div>
      <form data-form="capture" id="cap-form">
        <div class="dlg-body">
          <label class="sr-only" for="cap-title">${isTask ? 'Task title' : 'Note title'}</label>
          <input class="cap-title" id="cap-title" name="title" data-input="cap-field" autocomplete="off" placeholder="${isTask ? 'What needs doing?' : 'Note title'}" value="${cap.title}" required>
          ${isTask ? '' : html`<label class="sr-only" for="cap-body">Note text</label><textarea class="textarea" id="cap-body" name="body" data-input="cap-field" placeholder="Write a few lines. Format it later in the editor.">${cap.body}</textarea>`}
          <div class="cap-dest">
            <label for="cap-dest">Save to</label>
            <select class="select" id="cap-dest" name="dest" data-change="cap-dest"><option value="">Inbox, sort later</option>${T.topicOptions(cap.dest)}</select>
            ${isTask ? html`<label style="display:inline-flex;gap:8px;align-items:center"><input class="sel-box" type="checkbox" id="cap-today" data-change="cap-today" ${cap.today ? 'checked' : ''}>Plan for today</label>` : ''}
          </div>
        </div>
        <div class="dlg-foot">
          <span class="help">${isTask ? 'Enter saves.' : 'Cmd or Ctrl + Enter saves.'} Esc closes.</span>
          <button class="btn btn-ghost" type="button" data-action="close-dialog" data-id="dlg-capture">Cancel</button>
          <button class="btn btn-primary" type="submit">${dest ? 'Add to ' + short(dest.name, 22) : cap.today && isTask ? 'Add to Today' : 'Save to Inbox'}</button>
        </div>
      </form>`;
  }
  A['open-capture'] = (a, e, preset) => {
    if (T.isDialogOpen('dlg-capture')) { focusSoon('#cap-title'); return; }
    cap = Object.assign({ type: S().prefs.captureDefault || 'task', title: '', body: '', dest: '', today: false }, preset || {});
    T.openDialog('dlg-capture', captureBody, { className: 'dlg', labelledby: 'cap-h', manual: true });
    focusSoon('#cap-title');
  };
  A['cap-type'] = (a) => { cap.type = a.dataset.v; paint('dlg-capture', captureBody); focusSoon('#cap-title'); };
  IN['cap-field'] = (el) => { cap[el.name] = el.value; };
  CH['cap-dest'] = (el) => { cap.dest = el.value; paint('dlg-capture', captureBody); };
  CH['cap-today'] = (el) => { cap.today = el.checked; paint('dlg-capture', captureBody); };
  F.capture = () => {
    const title = cap.title.trim();
    if (!title) { focusSoon('#cap-title'); return; }
    const c = cap;
    let msg, open;
    T.commit((s) => {
      if (c.type === 'task') {
        const id = T.uid('k');
        s.tasks.push({ id, title, topicId: c.dest || null, done: false, planned: c.today ? T.today() : null, deadline: null, inbox: !c.dest && !c.today, createdAt: s.sim.now });
        msg = c.dest ? 'Task added to ' + Q.topic(c.dest).name : c.today ? 'Task added to Today' : 'Task saved to Inbox';
        open = () => T.openTask(id);
      } else {
        const id = T.uid('n');
        const body = c.body.trim() ? c.body.trim().split(/\n+/).map((p) => '<p>' + T.esc(p) + '</p>').join('') : '';
        s.notes.push({ id, title, topicIds: c.dest ? [c.dest] : [], inbox: !c.dest, updatedAt: s.sim.now, html: body });
        s.noteSync[id] = { state: 'local' };
        setTimeout(() => T.startSync(id), 300);
        msg = c.dest ? 'Note added to ' + Q.topic(c.dest).name : 'Note saved to Inbox';
        open = () => T.go('/notes/' + id);
      }
    });
    T.closeDialog('dlg-capture');
    T.toast(msg, { action: { label: 'Open', fn: open } });
  };

  /* --------------------------------------------------------------- Topics */
  A['topics-view'] = (a) => {
    T.state.prefs.topicsView = a.dataset.v;
    T.persist();
    T.replaceQuery((q) => q.set('view', a.dataset.v));
    T.render();
  };
  function setTopicStatus(id, statusId) {
    const t = Q.topic(id);
    if (!t || t.statusId === statusId) return;
    const st = Q.status(statusId);
    const was = Q.cat(t);
    let note = '';
    if (was === 'active' && st.category !== 'active') note = ' It leaves Today.';
    if (was !== 'active' && st.category === 'active') note = ' It now shows on Today.';
    undoable(t.name + ' moved to ' + st.name + '.' + note, (s) => { s.topics.find((x) => x.id === id).statusId = statusId; });
  }
  CH['topic-status'] = (el) => setTopicStatus(el.dataset.id, el.value);
  A['edit-continue'] = (a) => { ui.topicEdit = a.dataset.id; T.render(); focusSoon('#ce-left'); };
  A['cancel-continue'] = () => { ui.topicEdit = null; T.render(); focusSoon('#edit-continue'); };
  F.continue = (f) => {
    const d = new FormData(f);
    ui.topicEdit = null;
    T.commit((s) => { const t = s.topics.find((x) => x.id === f.dataset.id); t.leftOff = String(d.get('leftOff') || '').trim(); t.nextStep = String(d.get('nextStep') || '').trim(); });
    T.toast('Saved. Next step updated on Today and Topics.');
    focusSoon('#edit-continue');
  };
  A['toggle-done'] = (a) => { const k = 'done-' + a.dataset.id; ui.sections[k] = !ui.sections[k]; T.render(); };

  let topicForm = null;
  function topicBody() {
    const isNew = !topicForm.id;
    return html`<div class="dlg-head"><h2 id="topic-h">${isNew ? 'New Topic' : 'Edit Topic'}</h2>${closeBtn('dlg-topic')}</div>
      <form data-form="topic">
        <div class="dlg-body">
          <label class="field"><span>Name</span><input class="input" id="tf-name" name="name" value="${topicForm.name}" required maxlength="60" autocomplete="off"></label>
          <fieldset class="swatches"><legend>Thread color</legend>
            ${T.PALETTE.map((c) => html`<label class="swatch"><input type="radio" name="color" value="${c}" ${topicForm.color === c ? 'checked' : ''}><span class="thread" style="--tc:var(--t-${c})"></span>${c.charAt(0).toUpperCase() + c.slice(1)}</label>`)}
          </fieldset>
          ${isNew ? html`<label class="field"><span>Status</span><select class="select" name="status">${T.statusOptions(topicForm.statusId)}</select></label>` : ''}
          <label class="field"><span>Goal</span><textarea class="textarea" name="goal" placeholder="What does done look like?">${topicForm.goal}</textarea></label>
          ${isNew ? html`<label class="field"><span>Next step</span><input class="input" name="nextStep" autocomplete="off" placeholder="The first thing to do"></label>` : ''}
        </div>
        <div class="dlg-foot"><button class="btn btn-ghost" type="button" data-action="close-dialog" data-id="dlg-topic">Cancel</button><button class="btn btn-primary" type="submit">${isNew ? 'Create Topic' : 'Save'}</button></div>
      </form>`;
  }
  A['new-topic'] = () => {
    topicForm = { id: null, name: '', color: Q.nextColor(), statusId: Q.defaultStatusId(), goal: '' };
    T.openDialog('dlg-topic', topicBody, { labelledby: 'topic-h', manual: true });
    focusSoon('#tf-name');
  };
  A['edit-topic'] = (a) => {
    const t = Q.topic(a.dataset.id);
    topicForm = { id: t.id, name: t.name, color: t.color, statusId: t.statusId, goal: t.goal };
    T.openDialog('dlg-topic', topicBody, { labelledby: 'topic-h', manual: true });
    focusSoon('#tf-name');
  };
  F.topic = (f) => {
    const d = new FormData(f);
    const name = String(d.get('name') || '').trim();
    if (!name) return;
    if (!topicForm.id) {
      const id = T.uid('t-');
      T.commit((s) => s.topics.push({ id, name, color: d.get('color') || 'lavender', statusId: d.get('status') || Q.defaultStatusId(), goal: String(d.get('goal') || '').trim(), leftOff: '', nextStep: String(d.get('nextStep') || '').trim(), createdAt: s.sim.now }));
      T.closeDialog('dlg-topic');
      T.go('/topic/' + id);
      T.toast('Topic created.');
    } else {
      const id = topicForm.id;
      T.commit((s) => { const t = s.topics.find((x) => x.id === id); t.name = name; t.color = d.get('color') || t.color; t.goal = String(d.get('goal') || '').trim(); });
      T.closeDialog('dlg-topic');
    }
  };
  A['trash-topic'] = (a) => {
    const t = Q.topic(a.dataset.id);
    const tasks = Q.topicTasks(t.id);
    const notes = Q.topicNotes(t.id);
    const own = notes.filter((n) => n.topicIds.length === 1);
    const shared = notes.filter((n) => n.topicIds.length > 1);
    const jira = Q.topicJira(t.id);
    const prs = Q.topicPrs(t.id);
    confirmDialog({
      title: 'Move “' + t.name + '” to Trash?',
      body: html`<ul class="effect" style="list-style:none">
        <li>${tasks.length} Task${tasks.length === 1 ? '' : 's'} and ${own.length} Note${own.length === 1 ? '' : 's'} that belong only to this Topic go to Trash with it.</li>
        ${shared.length ? html`<li>${shared.length} Note${shared.length === 1 ? '' : 's'} also in other Topics stay there.</li>` : ''}
        <li>Links to ${jira.length} Jira ticket${jira.length === 1 ? '' : 's'} and ${prs.length} pull request${prs.length === 1 ? '' : 's'} are removed. The tickets and pull requests are not changed.</li>
        <li>Restoring brings all of it back.</li></ul>`,
      confirm: 'Move to Trash', danger: true,
      run: () => {
        T.commit((s) => {
          s.topics = s.topics.filter((x) => x.id !== t.id);
          s.tasks = s.tasks.filter((x) => x.topicId !== t.id);
          s.notes = s.notes.filter((n) => !(n.topicIds.length === 1 && n.topicIds[0] === t.id));
          s.notes.forEach((n) => { n.topicIds = n.topicIds.filter((id) => id !== t.id); });
          ['jira', 'prs'].forEach((src) => Object.keys(s.links[src]).forEach((k) => { s.links[src][k] = s.links[src][k].filter((id) => id !== t.id); if (!s.links[src][k].length) delete s.links[src][k]; }));
          s.trash.push({ id: T.uid('tr'), type: 'topic', deletedAt: s.sim.now, item: t, tasks, notes: own, sharedNoteIds: shared.map((n) => n.id), links: { jira, prs } });
        });
        T.go('/topics');
        T.toast('Moved “' + t.name + '” to Trash.', { action: { label: 'Open Trash', fn: () => T.go('/trash') } });
      }
    });
  };

  /* Board drag and drop. The status menu on each card is the keyboard path. */
  let dragId = null;
  document.addEventListener('dragstart', (e) => {
    const c = e.target.closest && e.target.closest('[data-drag]');
    if (!c) return;
    dragId = c.dataset.drag;
    c.classList.add('is-dragging');
    e.dataTransfer.effectAllowed = 'move';
    try { e.dataTransfer.setData('text/plain', dragId); } catch (err) { /* old browsers */ }
  });
  document.addEventListener('dragover', (e) => {
    const z = e.target.closest && e.target.closest('[data-drop]');
    if (!z || !dragId) return;
    e.preventDefault();
    document.querySelectorAll('.lane-body.is-over').forEach((n) => { if (n !== z) n.classList.remove('is-over'); });
    z.classList.add('is-over');
  });
  document.addEventListener('dragleave', (e) => {
    const z = e.target.closest && e.target.closest('[data-drop]');
    if (z && !z.contains(e.relatedTarget)) z.classList.remove('is-over');
  });
  document.addEventListener('drop', (e) => {
    const z = e.target.closest && e.target.closest('[data-drop]');
    if (!z || !dragId) return;
    e.preventDefault();
    const id = dragId;
    dragId = null;
    setTopicStatus(id, z.dataset.drop);
  });
  document.addEventListener('dragend', () => {
    dragId = null;
    document.querySelectorAll('.is-dragging, .is-over').forEach((n) => n.classList.remove('is-dragging', 'is-over'));
  });

  /* ------------------------------------------------------- Linking, picker */
  A.unlink = (a) => {
    const src = a.dataset.source === 'jira' ? 'jira' : 'prs';
    const key = a.dataset.key;
    const t = Q.topic(a.dataset.topic);
    const label = src === 'jira' ? key : 'pull request #' + (Q.pr(key) ? Q.pr(key).number : key);
    undoable('Unlinked ' + label + ' from ' + t.name + '. The ' + (src === 'jira' ? 'ticket' : 'pull request') + ' is unchanged.', (s) => {
      s.links[src][key] = (s.links[src][key] || []).filter((id) => id !== t.id);
      if (!s.links[src][key].length) delete s.links[src][key];
    });
  };

  let pick = null;
  function pickBody() {
    const p = pick;
    let title, items;
    if (p.mode === 'topics') {
      const label = p.source === 'note' ? '“' + short((Q.note(p.key) || {}).title || 'Untitled note', 30) + '”' : p.source === 'jira' ? p.key : 'pull request #' + Q.pr(p.key).number;
      title = 'Topics for ' + label;
      const topics = S().topics.filter((t) => !p.filter || t.name.toLowerCase().indexOf(p.filter.toLowerCase()) >= 0);
      items = topics.map((t) => html`<label class="picker-item"><input type="checkbox" data-change="pick-toggle" data-key="${t.id}" ${p.sel.has(t.id) ? 'checked' : ''}><span class="pi-main"><span class="pi-title"><span class="thread" style="${C.tc(t)}"></span>${t.name}</span><span class="pi-sub">${Q.status(t.statusId).name}</span></span><span></span></label>`);
    } else {
      const isJira = p.source === 'jira';
      const topic = Q.topic(p.topicId);
      title = 'Link ' + (isJira ? 'Jira tickets' : 'pull requests') + ' to ' + topic.name;
      const can = T.canFetch(p.source);
      let keys = isJira ? Object.keys(T.D.jira).filter((k) => can || Q.jiraCached(k)) : Object.keys(T.D.prs).filter((k) => can || Q.prCached(k));
      const f = (p.filter || '').toLowerCase();
      if (f) keys = keys.filter((k) => { const x = isJira ? T.D.jira[k] : T.D.prs[k]; return (k + ' ' + (isJira ? x.summary : x.title + ' ' + x.number + ' ' + x.repo)).toLowerCase().indexOf(f) >= 0; });
      items = keys.map((k) => {
        const x = isJira ? T.D.jira[k] : T.D.prs[k];
        const others = (isJira ? Q.jiraTopics(k) : Q.prTopics(k)).filter((id) => id !== topic.id).map((id) => Q.topic(id).name);
        const cached = isJira ? Q.jiraCached(k) : Q.prCached(k);
        return html`<label class="picker-item"><input type="checkbox" data-change="pick-toggle" data-key="${k}" ${p.sel.has(k) ? 'checked' : ''}>
          <span class="pi-main"><span class="pi-title">${isJira ? html`<b>${k}</b>` : html`<b>#${x.number}</b>`}<span>${isJira ? x.summary : x.title}</span></span>
          <span class="pi-sub">${isJira ? x.status : x.repo}${others.length ? ', also in ' + others.join(', ') : ''}${cached ? '' : ', from ' + (isJira ? 'Jira' : 'GitHub') + ' search'}</span></span><span></span></label>`;
      });
      if (!can) items.unshift(html`<p class="help" style="padding:4px 6px 8px">${Q.source(p.source) === 'disconnected' ? 'Not connected. Only items saved on this device are listed.' : 'Search is unavailable right now. Only items saved on this device are listed.'}</p>`);
    }
    return html`<div class="dlg-head"><h2 id="pick-h">${title}</h2>${closeBtn('dlg-pick')}</div>
      <div class="dlg-body">
        <label class="sr-only" for="pick-filter">Filter</label>
        <input class="input" id="pick-filter" data-input="pick-filter" value="${p.filter || ''}" placeholder="Filter" autocomplete="off">
        <div class="picker-list" data-keep-scroll="pick" id="pick-list">${items.length ? items : html`<p class="empty-inline">Nothing matches.</p>`}</div>
        <p class="help">${p.source === 'note' ? 'A Note can belong to several Topics. It stays one Note.' : 'Linking never changes the ' + (p.source === 'jira' ? 'ticket' : 'pull request') + '. One item can be linked to several Topics.'}</p>
      </div>
      <div class="dlg-foot"><span class="help">${p.sel.size} selected</span><button class="btn btn-ghost" type="button" data-action="close-dialog" data-id="dlg-pick">Cancel</button><button class="btn btn-primary" type="button" data-action="pick-save" id="pick-save">Save links</button></div>`;
  }
  function openPicker(p) {
    pick = p;
    T.openDialog('dlg-pick', pickBody, { labelledby: 'pick-h', manual: true });
    focusSoon('#pick-filter');
  }
  A['pick-topics'] = (a) => {
    const src = a.dataset.source;
    const ids = src === 'jira' ? Q.jiraTopics(a.dataset.key) : Q.prTopics(a.dataset.key);
    openPicker({ mode: 'topics', source: src, key: a.dataset.key, sel: new Set(ids), orig: ids.slice() });
  };
  A['pick-note-topics'] = (a) => {
    const n = Q.note(a.dataset.id);
    openPicker({ mode: 'topics', source: 'note', key: n.id, sel: new Set(n.topicIds), orig: n.topicIds.slice() });
  };
  A['pick-items'] = (a) => {
    const src = a.dataset.source;
    const ids = src === 'jira' ? Q.topicJira(a.dataset.topic) : Q.topicPrs(a.dataset.topic);
    openPicker({ mode: 'items', source: src, topicId: a.dataset.topic, sel: new Set(ids), orig: ids.slice() });
  };
  CH['pick-toggle'] = (el) => { if (el.checked) pick.sel.add(el.dataset.key); else pick.sel.delete(el.dataset.key); paint('dlg-pick', pickBody); };
  IN['pick-filter'] = (el) => { pick.filter = el.value; paint('dlg-pick', pickBody); };
  A['pick-save'] = () => {
    const p = pick;
    const added = Array.from(p.sel).filter((k) => p.orig.indexOf(k) < 0);
    const removed = p.orig.filter((k) => !p.sel.has(k));
    T.closeDialog('dlg-pick');
    if (!added.length && !removed.length) return;
    undoable('Links saved: ' + added.length + ' added, ' + removed.length + ' removed.', (s) => {
      if (p.source === 'note') {
        const n = s.notes.find((x) => x.id === p.key);
        n.topicIds = n.topicIds.filter((id) => removed.indexOf(id) < 0).concat(added);
        if (n.topicIds.length) n.inbox = false;
        return;
      }
      const src = p.source === 'jira' ? 'jira' : 'prs';
      const link = (key, tid) => { const l = s.links[src][key] = s.links[src][key] || []; if (l.indexOf(tid) < 0) l.push(tid); };
      const unlink = (key, tid) => { if (!s.links[src][key]) return; s.links[src][key] = s.links[src][key].filter((x) => x !== tid); if (!s.links[src][key].length) delete s.links[src][key]; };
      if (p.mode === 'topics') { added.forEach((tid) => link(p.key, tid)); removed.forEach((tid) => unlink(p.key, tid)); }
      else { added.forEach((k) => link(k, p.topicId)); removed.forEach((k) => unlink(k, p.topicId)); }
    });
    if (p.source === 'note') T.noteChanged(p.key);
  };

  /* -------------------------------------------------------------- Reminders */
  let remForm = null;
  function remBody() {
    const r = remForm;
    const tg = Q.targetOf({ target: r.target });
    const label = r.target.type === 'topic' ? tg.name : (tg.title || 'Untitled note');
    return html`<div class="dlg-head"><h2 id="rem-h">${r.id ? 'Edit reminder' : 'New reminder'}</h2>${closeBtn('dlg-rem')}</div>
      <form data-form="reminder">
        <div class="dlg-body">
          <p class="muted" style="display:flex;gap:8px;align-items:center">${icon({ task: 'check', note: 'note', topic: 'topics' }[r.target.type], 'icon-sm')}<span>For the ${r.target.type === 'topic' ? 'Topic' : r.target.type === 'task' ? 'Task' : 'Note'} “${short(label, 50)}”</span></p>
          <div class="field"><span class="field-label">Quick picks</span><div class="demo-row">
            <button class="btn btn-sm" type="button" data-action="rem-pick" data-v="60">In 1 hour</button>
            <button class="btn btn-sm" type="button" data-action="rem-pick" data-v="evening">Today 18:00</button>
            <button class="btn btn-sm" type="button" data-action="rem-pick" data-v="tomorrow">Tomorrow 09:00</button>
          </div></div>
          <div class="date-pair">
            <label class="field"><span>Date</span><input class="input" type="date" id="rem-date" name="date" value="${r.at.slice(0, 10)}" required></label>
            <label class="field"><span>Time</span><input class="input" type="time" id="rem-time" name="time" value="${r.at.slice(11, 16)}" required></label>
          </div>
          <p class="field-error" id="rem-err" role="alert"></p>
          <label class="field"><span>Text (optional)</span><input class="input" name="text" value="${r.text || ''}" maxlength="80" autocomplete="off" placeholder="What to remember"></label>
          <p class="help">Reminders appear inside Thread while it is open. Notifications for a closed app come later.</p>
        </div>
        <div class="dlg-foot">${r.id ? html`<button class="btn btn-ghost btn-danger" type="button" data-action="rem-delete">Delete</button>` : ''}<span class="spacer"></span><button class="btn btn-ghost" type="button" data-action="close-dialog" data-id="dlg-rem">Cancel</button><button class="btn btn-primary" type="submit">Save reminder</button></div>
      </form>`;
  }
  function openRem(form) {
    remForm = form;
    T.openDialog('dlg-rem', remBody, { labelledby: 'rem-h', manual: true });
    focusSoon('#rem-date');
  }
  A['new-rem'] = (a) => {
    const n = T.now();
    const next = T.addMinutes(n.slice(0, 13) + ':00', 60);
    openRem({ id: null, target: { type: a.dataset.type, id: a.dataset.id }, at: next, text: '' });
  };
  A['edit-rem'] = (a) => { const r = S().reminders.find((x) => x.id === a.dataset.id); openRem({ id: r.id, target: r.target, at: r.at < T.now() ? T.addMinutes(T.now().slice(0, 13) + ':00', 60) : r.at, text: r.text }); };
  A['rem-pick'] = (a) => {
    const v = a.dataset.v;
    const at = v === '60' ? T.addMinutes(T.now(), 60) : v === 'evening' ? T.today() + 'T18:00' : T.addDays(T.today(), 1) + 'T09:00';
    $('#rem-date').value = at.slice(0, 10);
    $('#rem-time').value = at.slice(11, 16);
    $('#rem-err').textContent = '';
  };
  F.reminder = (f) => {
    const d = new FormData(f);
    const at = d.get('date') + 'T' + d.get('time');
    if (at <= T.now()) { $('#rem-err').textContent = 'Choose a time after now (' + T.fmtWhen(T.now()) + ').'; focusSoon('#rem-time'); return; }
    const text = String(d.get('text') || '').trim();
    const r = remForm;
    T.commit((s) => {
      if (r.id) { const x = s.reminders.find((y) => y.id === r.id); x.at = at; x.text = text; x.dismissed = false; delete s.alerted[r.id]; }
      else s.reminders.push({ id: T.uid('r'), target: r.target, at, text, dismissed: false });
    });
    T.closeDialog('dlg-rem');
    T.toast('Reminder set for ' + T.fmtWhen(at) + '.');
  };
  A['rem-delete'] = () => {
    const id = remForm.id;
    T.closeDialog('dlg-rem');
    undoable('Reminder deleted.', (s) => { s.reminders = s.reminders.filter((x) => x.id !== id); });
  };
  A['dismiss-rem'] = (a) => undoable('Reminder dismissed.', (s) => { s.reminders.find((x) => x.id === a.dataset.id).dismissed = true; });

  /* In-app reminder alert, shown when the demo clock passes a reminder. */
  function checkAlarms(from, to) {
    const due = S().reminders.filter((r) => !r.dismissed && !S().alerted[r.id] && r.at > from && r.at <= to && Q.targetOf(r)).sort((a, b) => a.at.localeCompare(b.at));
    if (due.length) showAlarm(due[0].id);
  }
  function showAlarm(id) {
    const r = S().reminders.find((x) => x.id === id);
    if (!r) return;
    S().alerted[id] = true;
    T.persist();
    const host = document.getElementById('alarm-host');
    host.innerHTML = T.flat(html`<div class="alarm" role="alertdialog" aria-labelledby="alarm-h" aria-describedby="alarm-d">
      <span class="rem-time">${T.fmtTime(r.at)}<small>Reminder</small></span>
      <div><h2 id="alarm-h" style="font-size:var(--fs-15);font-weight:650">${r.text || 'Reminder'}</h2><div id="alarm-d">${C.remTarget(r)}</div></div>
      <div class="alarm-actions">
        <button class="btn btn-sm btn-ghost" type="button" data-action="alarm-snooze" data-id="${id}">Snooze 10 min</button>
        <button class="btn btn-sm" type="button" data-action="alarm-open" data-id="${id}">Open</button>
        <button class="btn btn-sm btn-primary" type="button" data-action="alarm-done" data-id="${id}" id="alarm-done">Done</button>
      </div></div>`);
    focusSoon('#alarm-done');
  }
  function closeAlarm() { document.getElementById('alarm-host').innerHTML = ''; }
  A['alarm-snooze'] = (a) => { closeAlarm(); T.commit((s) => { const r = s.reminders.find((x) => x.id === a.dataset.id); r.at = T.addMinutes(s.sim.now, 10); delete s.alerted[r.id]; }); T.toast('Snoozed for 10 minutes.'); };
  A['alarm-done'] = (a) => { closeAlarm(); T.commit((s) => { s.reminders.find((x) => x.id === a.dataset.id).dismissed = true; }); };
  A['alarm-open'] = (a) => {
    const r = S().reminders.find((x) => x.id === a.dataset.id);
    closeAlarm();
    if (r.target.type === 'task') T.openTask(r.target.id);
    else T.go(r.target.type === 'note' ? '/notes/' + r.target.id : '/topic/' + r.target.id);
  };

  /* ------------------------------------------------------- Confirm dialog */
  let confirmCfg = null;
  function confirmBody() {
    const c = confirmCfg;
    return html`<div class="dlg-head"><h2 id="cf-h">${c.title}</h2></div>
      <div class="dlg-body">${typeof c.body === 'string' ? html`<p class="muted">${c.body}</p>` : c.body}</div>
      <div class="dlg-foot"><button class="btn btn-ghost" type="button" data-action="close-dialog" data-id="dlg-confirm" id="cf-cancel">Cancel</button><button class="btn ${c.danger ? 'btn-danger' : 'btn-primary'}" type="button" data-action="confirm-ok" id="cf-ok">${c.confirm}</button></div>`;
  }
  function confirmDialog(cfg) {
    confirmCfg = cfg;
    T.openDialog('dlg-confirm', confirmBody, { labelledby: 'cf-h', manual: true });
    focusSoon(cfg.danger ? '#cf-cancel' : '#cf-ok');
  }
  T.confirmDialog = confirmDialog;
  A['confirm-ok'] = () => { const c = confirmCfg; T.closeDialog('dlg-confirm'); c.run(); };

  /* ---------------------------------------------------------- Notes editor */
  A['new-note'] = (a) => {
    const topic = a.dataset.topic || null;
    const id = T.uid('n');
    T.commit((s) => {
      s.notes.push({ id, title: '', topicIds: topic ? [topic] : [], inbox: false, updatedAt: s.sim.now, html: '' });
      s.noteSync[id] = { state: 'local' };
    }, { silent: true });
    T.go('/notes/' + id);
    focusSoon('#note-title');
    setTimeout(() => T.startSync(id), 500);
  };
  CH['note-filter'] = (el) => { ui.noteFilter = el.value; T.render(); };
  IN['note-title'] = (el) => {
    const id = el.dataset.id;
    T.commit((s) => { const n = s.notes.find((x) => x.id === id); n.title = el.value; n.updatedAt = s.sim.now; }, { silent: true });
    T.noteChanged(id);
  };
  A['note-unlink-topic'] = (a) => {
    const t = Q.topic(a.dataset.topic);
    undoable('Removed from ' + t.name + '. The Note itself stays.', (s) => { const n = s.notes.find((x) => x.id === a.dataset.id); n.topicIds = n.topicIds.filter((x) => x !== t.id); });
    T.noteChanged(a.dataset.id);
    focusSoon('#note-add-topic');
  };
  A['note-trash'] = (a) => {
    const n = Q.note(a.dataset.id);
    T.go('/notes');
    undoable('Moved “' + short(n.title || 'Untitled note', 40) + '” to Trash.', (s) => trashItem(s, 'note', n.id));
  };
  A['retry-save'] = (a) => {
    if (S().sim.saveFail) { T.toast('Still failing. Turn off “Device save fails” in Demo controls to recover.'); return; }
    T.saveNote(a.dataset.id);
    T.toast('Saved on this device.');
  };
  A['retry-sync'] = (a) => {
    if (S().sim.syncFail) { T.toast('Sync still fails. Turn off “Cloud sync fails” in Demo controls to recover.'); return; }
    Q.sync(a.dataset.id).state = 'local';
    T.startSync(a.dataset.id);
  };
  const stripMarks = (h) => String(h).replace(/<\/?mark[^>]*>/g, '');
  A.resolve = (a) => {
    const n = Q.note(a.dataset.id);
    const c = Q.sync(n.id).conflict;
    const keep = a.dataset.keep;
    let msg = '';
    let newId = null;
    T.commit((s) => {
      const note = s.notes.find((x) => x.id === n.id);
      const local = { title: note.title, html: note.html, updatedAt: note.updatedAt };
      const remote = { title: c.title, html: stripMarks(c.html), updatedAt: c.at };
      const copy = (v, suffix) => ({ id: T.uid('n'), title: (v.title || 'Untitled note') + suffix, topicIds: note.topicIds.slice(), inbox: false, updatedAt: v.updatedAt, html: v.html });
      if (keep === 'local') { s.trash.push({ id: T.uid('tr'), type: 'note', deletedAt: s.sim.now, item: copy(remote, ' (' + c.device + ' version)') }); msg = 'Kept this device’s version. The other version is in Trash.'; }
      else if (keep === 'remote') { s.trash.push({ id: T.uid('tr'), type: 'note', deletedAt: s.sim.now, item: copy(local, ' (this device version)') }); note.title = remote.title; note.html = remote.html; note.updatedAt = s.sim.now; msg = 'Kept the ' + c.device + ' version. This device’s version is in Trash.'; }
      else { const cp = copy(remote, ' (' + c.device + ' version)'); s.notes.push(cp); s.noteSync[cp.id] = { state: 'local' }; newId = cp.id; msg = 'Kept both. The other version is now a separate Note.'; }
      s.noteSync[n.id].conflict = null;
      s.noteSync[n.id].state = 'local';
    });
    T.startSync(n.id);
    if (newId) setTimeout(() => T.startSync(newId), 300);
    T.toast(msg, newId ? { action: { label: 'Open copy', fn: () => T.go('/notes/' + newId) } } : { action: { label: 'Open Trash', fn: () => T.go('/trash') } });
    focusSoon('#note-body');
  };

  function makeConflict(id) {
    const n = Q.note(id);
    if (!n) return;
    const ns = Q.sync(id);
    ns.conflict = {
      device: 'Phone',
      at: T.addMinutes(T.now(), -4),
      title: n.title || 'Untitled note',
      html: (n.html || '') + '<p><mark class="diff">Added on the phone: ask backend whether an expired QR code returns 410 or 422.</mark></p>'
    };
    T.persist();
    T.render();
  }

  let savedRange = null;
  function editor() { return document.getElementById('note-body'); }
  function inEditor(node) { const ed = editor(); return ed && node && ed.contains(node.nodeType === 1 ? node : node.parentNode); }
  function closestInEditor(sel) {
    const s = window.getSelection();
    if (!s.rangeCount || !inEditor(s.anchorNode)) return null;
    let n = s.anchorNode.nodeType === 1 ? s.anchorNode : s.anchorNode.parentNode;
    const ed = editor();
    while (n && n !== ed) { if (n.matches && n.matches(sel)) return n; n = n.parentNode; }
    return null;
  }
  function onEditorInput() {
    const ed = editor();
    if (!ed) return;
    const id = ed.dataset.note;
    T.commit((s) => { const n = s.notes.find((x) => x.id === id); if (n) { n.html = ed.innerHTML; n.updatedAt = s.sim.now; } }, { silent: true });
    T.noteChanged(id);
  }
  function updateToolbar() {
    const tb = document.getElementById('note-toolbar');
    if (!tb) return;
    const s = window.getSelection();
    const inside = s.rangeCount && inEditor(s.anchorNode);
    const set = (k, v) => { const b = tb.querySelector('[data-tool="' + k + '"]'); if (b) b.setAttribute('aria-pressed', inside && v ? 'true' : 'false'); };
    let bold = false, italic = false;
    try { bold = document.queryCommandState('bold'); italic = document.queryCommandState('italic'); } catch (e) { /* unsupported */ }
    set('bold', bold);
    set('italic', italic);
    const cl = closestInEditor('ul.checklist');
    set('check', !!cl);
    set('ul', !cl && !!closestInEditor('ul'));
    set('ol', !!closestInEditor('ol'));
  }
  function runTool(tool) {
    const ed = editor();
    if (!ed) return;
    if (tool === 'link') {
      const s = window.getSelection();
      savedRange = s.rangeCount && inEditor(s.anchorNode) ? s.getRangeAt(0).cloneRange() : null;
      ui.linkForm = true;
      T.render();
      focusSoon('#link-url');
      return;
    }
    ed.focus();
    if (tool === 'bold' || tool === 'italic') document.execCommand(tool);
    else if (tool === 'p') document.execCommand('formatBlock', false, '<p>');
    else if (tool === 'h') document.execCommand('formatBlock', false, closestInEditor('h2') ? '<p>' : '<h2>');
    else if (tool === 'ol') document.execCommand('insertOrderedList');
    else if (tool === 'ul') {
      const cl = closestInEditor('ul.checklist');
      if (cl) { cl.removeAttribute('class'); cl.querySelectorAll('li').forEach((li) => li.removeAttribute('data-checked')); }
      else document.execCommand('insertUnorderedList');
    } else if (tool === 'check') {
      const cl = closestInEditor('ul.checklist');
      if (cl) document.execCommand('insertUnorderedList');
      else {
        let ul = closestInEditor('ul');
        if (!ul) { document.execCommand('insertUnorderedList'); ul = closestInEditor('ul'); }
        if (ul) { ul.className = 'checklist'; ul.querySelectorAll('li').forEach((li) => { if (!li.hasAttribute('data-checked')) li.setAttribute('data-checked', 'false'); }); }
      }
    }
    unwrapLists(ed);
    onEditorInput();
    updateToolbar();
  }
  /* Chrome can nest a new list inside a <p>. Move it out and keep the caret where it was. */
  function unwrapLists(ed) {
    const bad = ed.querySelectorAll('p > ul, p > ol');
    if (!bad.length) return;
    const s = window.getSelection();
    const r = s.rangeCount ? s.getRangeAt(0) : null;
    const keep = r ? [r.startContainer, r.startOffset, r.endContainer, r.endOffset] : null;
    bad.forEach((list) => {
      const p = list.parentNode;
      p.parentNode.insertBefore(list, p);
      if (!p.textContent.trim()) p.remove();
    });
    if (keep) { try { const nr = document.createRange(); nr.setStart(keep[0], keep[1]); nr.setEnd(keep[2], keep[3]); s.removeAllRanges(); s.addRange(nr); } catch (e) { /* caret lost, editor keeps focus */ } }
  }
  function toggleCheckItem(li) {
    li.setAttribute('data-checked', li.getAttribute('data-checked') === 'true' ? 'false' : 'true');
    onEditorInput();
  }
  function bindEditor() {
    const ed = editor();
    if (!ed || ed.dataset.bound) return;
    ed.dataset.bound = '1';
    try { document.execCommand('defaultParagraphSeparator', false, 'p'); } catch (e) { /* ignore */ }
    ed.addEventListener('input', onEditorInput);
    ed.addEventListener('paste', (e) => {
      e.preventDefault();
      const text = (e.clipboardData || window.clipboardData).getData('text/plain');
      document.execCommand('insertText', false, text);
    });
    ed.addEventListener('click', (e) => {
      const link = e.target.closest('a[href]');
      if (link && (e.metaKey || e.ctrlKey)) { e.preventDefault(); T.toast('Demo only. The real app opens ' + link.getAttribute('href') + ' in a new tab.'); return; }
      if (link) e.preventDefault();
      const li = e.target.closest('ul.checklist > li');
      if (li && e.clientX - li.getBoundingClientRect().left < 26) { e.preventDefault(); toggleCheckItem(li); }
    });
    ed.addEventListener('keydown', (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
        const li = closestInEditor('ul.checklist > li');
        if (li) { e.preventDefault(); toggleCheckItem(li); }
        return;
      }
      if (e.key === 'Enter' && closestInEditor('ul.checklist')) {
        setTimeout(() => { const li = closestInEditor('ul.checklist > li'); if (li && !li.textContent.trim()) li.setAttribute('data-checked', 'false'); }, 0);
      }
    });
  }
  document.addEventListener('selectionchange', () => { if (editor()) updateToolbar(); });
  document.addEventListener('mousedown', (e) => { if (e.target.closest && e.target.closest('[data-tool]')) e.preventDefault(); });
  document.addEventListener('click', (e) => { const b = e.target.closest && e.target.closest('[data-tool]'); if (b) runTool(b.dataset.tool); });
  document.addEventListener('keydown', (e) => {
    const b = e.target.closest && e.target.closest('#note-toolbar .tool');
    if (!b || !/^(ArrowLeft|ArrowRight|Home|End)$/.test(e.key)) return;
    e.preventDefault();
    const tools = Array.from(document.querySelectorAll('#note-toolbar .tool'));
    let i = tools.indexOf(b);
    i = e.key === 'Home' ? 0 : e.key === 'End' ? tools.length - 1 : (i + (e.key === 'ArrowRight' ? 1 : -1) + tools.length) % tools.length;
    tools.forEach((t, j) => t.setAttribute('tabindex', j === i ? '0' : '-1'));
    tools[i].focus();
  });
  F['note-link'] = (f) => {
    const url = String(new FormData(f).get('url') || '').trim();
    if (!/^(https?:\/\/|mailto:)/i.test(url)) { T.toast('Use a link that starts with https://, http:// or mailto:'); return; }
    ui.linkForm = false;
    T.render();
    const ed = editor();
    ed.focus();
    const s = window.getSelection();
    if (savedRange) { s.removeAllRanges(); s.addRange(savedRange); }
    if (!savedRange || savedRange.collapsed) document.execCommand('insertHTML', false, '<a href="' + T.esc(url) + '">' + T.esc(url) + '</a>');
    else document.execCommand('createLink', false, url);
    savedRange = null;
    onEditorInput();
  };
  A['link-cancel'] = () => { ui.linkForm = false; savedRange = null; T.render(); focusSoon('#note-body'); };

  /* ------------------------------------------------------------- Jira search */
  function runJiraSearch(q) {
    q = String(q || '').trim();
    if (!q) return;
    const match = (k) => { const j = T.D.jira[k]; return (k + ' ' + j.summary + ' ' + j.labels.join(' ') + ' ' + j.status).toLowerCase().indexOf(q.toLowerCase()) >= 0; };
    if (!T.canFetch('jira')) {
      ui.jiraSearch = { q, status: 'done', local: true, results: Object.keys(T.D.jira).filter((k) => Q.jiraCached(k) && match(k)) };
      T.render();
      return;
    }
    ui.jiraSearch = { q, status: 'loading', results: [] };
    T.render();
    setTimeout(() => { ui.jiraSearch = { q, status: 'done', local: false, results: Object.keys(T.D.jira).filter(match) }; T.render(); }, 650);
  }
  F['jira-search'] = (f) => runJiraSearch(new FormData(f).get('q'));
  A['jira-sample'] = (a) => runJiraSearch(a.dataset.q);
  A.refresh = (a) => {
    const src = a.dataset.source;
    const r = T.route();
    T.refresh(src);
    if (r.id) T.refresh(src, r.id);
    T.render();
  };
  A.reconnect = (a) => {
    const src = a.dataset.source;
    T.commit((s) => { s.sim[src] = 'ok'; delete s.sim[src + 'RetryAt']; });
    T.toast((src === 'jira' ? 'Jira' : 'GitHub') + ' reconnected (demo). Refreshing.');
    T.refresh(src);
    const r = T.route();
    const key = r.id || r.q.get(src === 'jira' ? 'jira' : 'pr');
    if (key) T.refresh(src, key);
  };

  /* --------------------------------------------------------------- Settings */
  A.connect = (a) => {
    const src = a.dataset.source;
    const name = src === 'jira' ? 'Jira' : 'GitHub';
    confirmDialog({
      title: 'Connect ' + name + ' (demo)?',
      body: 'No sign-in window opens and nothing is sent. The real app asks you to sign in to ' + name + ' and requests read-only access.',
      confirm: 'Connect (demo)',
      run: () => { T.commit((s) => { s.conn[src] = true; s.sim[src] = 'ok'; }); T.toast(name + ' connected (demo). Loading your list.'); T.refresh(src); }
    });
  };
  A.disconnect = (a) => {
    const src = a.dataset.source;
    const name = src === 'jira' ? 'Jira' : 'GitHub';
    confirmDialog({
      title: 'Disconnect ' + name + '?',
      body: 'Saved ' + (src === 'jira' ? 'ticket' : 'pull request') + ' details are removed from this device. Links on your Topics stay and show only the ' + (src === 'jira' ? 'ticket key' : 'pull request number') + ' until you connect again.',
      confirm: 'Disconnect', danger: true,
      run: () => {
        T.commit((s) => { s.conn[src] = false; if (src === 'jira') { s.cache.jira = {}; s.cache.lists.jira = null; } else { s.cache.prs = {}; s.cache.lists.github = null; } });
        T.toast(name + ' disconnected. Links kept.');
      }
    });
  };
  CH.pref = (el) => { T.commit((s) => { s.prefs[el.dataset.key] = el.value; }); T.toast('Saved.'); };
  A['settings-jump'] = (a) => {
    const el = document.getElementById('set-' + a.dataset.sec);
    if (!el) return;
    T.replaceQuery((q) => q.set('sec', a.dataset.sec));
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    const h = el.querySelector('h2');
    if (h) { h.setAttribute('tabindex', '-1'); h.focus({ preventScroll: true }); }
  };
  const countCat = (cat) => S().statuses.filter((s) => s.category === cat).length;
  CH['status-name'] = (el) => {
    const v = el.value.trim();
    const st = Q.status(el.dataset.id);
    if (!v) { el.value = st.name; T.toast('A status needs a name.'); return; }
    if (S().statuses.some((s) => s.id !== st.id && s.name.toLowerCase() === v.toLowerCase())) { el.value = st.name; T.toast('Another status already uses that name.'); return; }
    T.commit((s) => { s.statuses.find((x) => x.id === st.id).name = v; });
    T.toast('Renamed. Topics, the Board and Today show the new name.');
  };
  CH['status-cat'] = (el) => {
    const st = Q.status(el.dataset.id);
    if (countCat(st.category) === 1) { el.value = st.category; T.toast('Each category needs at least one status.'); return; }
    const n = Q.usage(st.id);
    T.commit((s) => { s.statuses.find((x) => x.id === st.id).category = el.value; });
    T.toast(st.name + ' is now ' + T.CAT_LABEL[el.value] + '.' + (n ? ' ' + n + ' Topic' + (n === 1 ? '' : 's') + ' follow' + (n === 1 ? 's' : '') + ' the new behavior.' : ''));
  };
  A['status-move'] = (a) => {
    const dir = Number(a.dataset.dir);
    T.commit((s) => {
      const i = s.statuses.findIndex((x) => x.id === a.dataset.id);
      const j = i + dir;
      if (j < 0 || j >= s.statuses.length) return;
      const tmp = s.statuses[i]; s.statuses[i] = s.statuses[j]; s.statuses[j] = tmp;
    });
    const btn = document.getElementById((dir < 0 ? 'su-' : 'sd-') + a.dataset.id);
    if (btn && btn.disabled) focusSoon('#' + (dir < 0 ? 'sd-' : 'su-') + a.dataset.id);
  };
  F['status-add'] = (f) => {
    const d = new FormData(f);
    const name = String(d.get('name') || '').trim();
    if (!name) return;
    if (S().statuses.some((s) => s.name.toLowerCase() === name.toLowerCase())) { T.toast('A status with that name exists.'); return; }
    T.commit((s) => s.statuses.push({ id: T.uid('st-'), name, category: d.get('cat') }));
    focusSoon('#status-new');
  };
  let delSt = null;
  function statusDelBody() {
    const st = Q.status(delSt.id);
    const users = S().topics.filter((t) => t.statusId === st.id);
    const others = S().statuses.filter((x) => x.id !== st.id).sort((a, b) => (a.category === st.category ? -1 : 0) - (b.category === st.category ? -1 : 0));
    const to = Q.status(delSt.to || others[0].id);
    return html`<div class="dlg-head"><h2 id="sd-h">Delete the status “${st.name}”?</h2>${closeBtn('dlg-status')}</div>
      <div class="dlg-body">
        ${users.length ? html`
          <p class="muted">${users.length} Topic${users.length === 1 ? ' uses' : 's use'} this status. Choose a replacement before deleting.</p>
          <label class="field"><span>Move those Topics to</span><select class="select" id="sd-to" data-change="sd-to">${others.map((o) => html`<option value="${o.id}" ${o.id === to.id ? 'selected' : ''}>${o.name}${o.name !== T.CAT_LABEL[o.category] ? ' (' + T.CAT_LABEL[o.category] + ')' : ''}</option>`)}</select></label>
          <section class="effect"><h3>${icon('eye', 'icon-sm')}What changes</h3><ul>${users.map((t) => html`<li><span class="thread" style="${C.tc(t)}"></span><span>${t.name} moves to ${to.name}${to.category !== st.category ? ', category ' + T.CAT_LABEL[to.category] + '. ' + T.CAT_HELP[to.category] : ''}</span></li>`)}</ul></section>`
          : html`<p class="muted">No Topics use this status.</p>`}
      </div>
      <div class="dlg-foot"><button class="btn btn-ghost" type="button" data-action="close-dialog" data-id="dlg-status">Cancel</button><button class="btn btn-danger" type="button" data-action="status-del-ok" id="sd-ok">${users.length ? 'Move Topics and delete' : 'Delete status'}</button></div>`;
  }
  A['status-delete'] = (a) => {
    const st = Q.status(a.dataset.id);
    if (countCat(st.category) === 1) { T.toast('Each category needs at least one status.'); return; }
    delSt = { id: st.id, to: null };
    T.openDialog('dlg-status', statusDelBody, { labelledby: 'sd-h', manual: true });
    focusSoon('#sd-to, #sd-ok');
  };
  CH['sd-to'] = (el) => { delSt.to = el.value; paint('dlg-status', statusDelBody); };
  A['status-del-ok'] = () => {
    const st = Q.status(delSt.id);
    const others = S().statuses.filter((x) => x.id !== st.id).sort((a, b) => (a.category === st.category ? -1 : 0) - (b.category === st.category ? -1 : 0));
    const to = delSt.to || others[0].id;
    const n = Q.usage(st.id);
    T.closeDialog('dlg-status');
    undoable('Deleted “' + st.name + '”.' + (n ? ' ' + n + ' Topic' + (n === 1 ? '' : 's') + ' moved to ' + Q.status(to).name + '.' : ''), (s) => {
      s.topics.forEach((t) => { if (t.statusId === st.id) t.statusId = to; });
      s.statuses = s.statuses.filter((x) => x.id !== st.id);
    });
    focusSoon('#sn-' + to);
  };
  A['reset-demo'] = (a) => {
    const kind = a.dataset.kind;
    confirmDialog({
      title: kind === 'empty' ? 'Switch to an empty workspace?' : 'Reset the example workspace?',
      body: 'Every change made in this prototype is replaced. Nothing outside this browser is affected.',
      confirm: kind === 'empty' ? 'Switch' : 'Reset', danger: true,
      run: () => { resetAll(kind); T.toast(kind === 'empty' ? 'Empty workspace ready.' : 'Example workspace restored.'); }
    });
  };
  function resetAll(kind) {
    T.closeAllDialogs();
    closeAlarm();
    T.closePopover();
    T.resetState(kind);
    T.state.session.signedIn = true;
    T.persistNow();
    Object.assign(ui, { refreshing: {}, fresh: {}, loading: {}, sections: {}, topicEdit: null, inboxSel: null, inboxChecked: [], noteFilter: 'all', linkForm: false, jiraSearch: { q: '', status: 'idle', results: [] } });
    T.go('/today');
  }

  /* ------------------------------------------------------------ Global search */
  const PAGES = [['today', 'Today', 'today'], ['inbox', 'Inbox', 'inbox'], ['topics', 'Topics', 'topics'], ['notes', 'Notes', 'note'], ['jira', 'Jira', 'ticket'], ['prs', 'Pull requests', 'pr'], ['settings', 'Settings', 'settings'], ['trash', 'Trash', 'trash']];
  let pal = null;
  function markHit(text, q) {
    text = String(text || '');
    if (!q) return text;
    const i = text.toLowerCase().indexOf(q.toLowerCase());
    if (i < 0) return text;
    return html`${text.slice(0, i)}<mark>${text.slice(i, i + q.length)}</mark>${text.slice(i + q.length)}`;
  }
  function excerpt(text, q) {
    const i = text.toLowerCase().indexOf(q.toLowerCase());
    if (i < 0) return text.slice(0, 80);
    const st = Math.max(0, i - 30);
    return (st ? '…' : '') + text.slice(st, i + 60);
  }
  function searchGroups(q) {
    const s = S();
    const ql = q.trim().toLowerCase();
    const has = (v) => !!v && String(v).toLowerCase().indexOf(ql) >= 0;
    const groups = [];
    const action = (title, ico, run, sub) => ({ icon: ico, title, sub: sub || '', run });
    if (!ql) {
      groups.push({ h: 'Go to', items: PAGES.map((p) => action(p[1], p[2], () => T.go('/' + p[0]))) });
      groups.push({ h: 'Create', items: [
        action('Capture a Task', 'plus', () => A['open-capture'](null, null, { type: 'task' }), 'Shortcut C'),
        action('Capture a Note', 'note', () => A['open-capture'](null, null, { type: 'note' })),
        action('New Topic', 'topics', () => A['new-topic']())
      ] });
      return { groups, empty: false };
    }
    const topics = s.topics.filter((t) => has(t.name) || has(t.goal) || has(t.nextStep)).slice(0, 5).map((t) => ({ icon: 'topics', thread: t, title: markHit(t.name, ql), sub: Q.status(t.statusId).name + (t.nextStep ? '. Next: ' + t.nextStep : ''), run: () => T.go('/topic/' + t.id) }));
    const tasks = s.tasks.filter((t) => has(t.title)).slice(0, 5).map((t) => ({ icon: 'check', title: markHit(t.title, ql), sub: (t.topicId && Q.topic(t.topicId) ? Q.topic(t.topicId).name : t.inbox ? 'Inbox' : 'No Topic') + (t.done ? ', done' : ''), run: () => T.openTask(t.id) }));
    const notes = s.notes.filter((n) => has(n.title) || has(Q.noteText(n))).slice(0, 5).map((n) => ({ icon: 'note', title: markHit(n.title || 'Untitled note', ql), sub: excerpt(Q.noteText(n), ql), run: () => T.go('/notes/' + n.id) }));
    const canJ = T.canFetch('jira');
    const jira = (s.conn.jira ? Object.keys(T.D.jira) : []).filter((k) => (canJ || Q.jiraCached(k)) && (has(k) || has(T.D.jira[k].summary))).slice(0, 5).map((k) => ({ icon: 'ticket', title: markHit(T.D.jira[k].summary, ql), sub: k + ', ' + T.D.jira[k].status + (Q.jiraCached(k) ? '' : ', from Jira search'), tail: k, run: () => T.go('/jira/' + k) }));
    const canG = T.canFetch('github');
    const prs = (s.conn.github ? Object.keys(T.D.prs) : []).filter((k) => (canG || Q.prCached(k)) && (has(T.D.prs[k].title) || has('#' + T.D.prs[k].number) || has(T.D.prs[k].repo))).slice(0, 5).map((k) => ({ icon: 'pr', title: markHit(T.D.prs[k].title, ql), sub: T.D.prs[k].repo + (T.D.prs[k].draft ? ', draft' : ', open'), tail: '#' + T.D.prs[k].number, run: () => T.go('/prs/' + k) }));
    const pages = PAGES.filter((p) => has(p[1])).map((p) => action(markHit(p[1], ql), p[2], () => T.go('/' + p[0]), 'Page'));
    [['Topics', topics], ['Tasks', tasks], ['Notes', notes], ['Jira tickets', jira], ['Pull requests', prs], ['Pages', pages]].forEach((g) => { if (g[1].length) groups.push({ h: g[0], items: g[1] }); });
    const empty = !groups.length;
    groups.push({ h: empty ? 'Do something with “' + short(q.trim(), 30) + '”' : 'Actions', items: [
      action('Capture “' + short(q.trim(), 40) + '” as a Task', 'plus', () => A['open-capture'](null, null, { type: 'task', title: q.trim() })),
      s.conn.jira ? action('Search Jira for “' + short(q.trim(), 40) + '”', 'ticket', () => { T.go('/jira?tab=search'); setTimeout(() => runJiraSearch(q.trim()), 40); }) : null
    ].filter(Boolean) });
    return { groups, empty };
  }
  function palList() {
    const res = searchGroups(pal.q);
    pal.flat = [];
    let i = 0;
    const out = res.groups.map((g) => html`<div role="group" aria-labelledby="pg-${g.h.replace(/\W+/g, '')}"><div class="pal-group-h" id="pg-${g.h.replace(/\W+/g, '')}">${g.h}</div>${g.items.map((it) => {
      const idx = i++;
      pal.flat.push(it);
      return html`<div class="pal-opt" role="option" id="po-${idx}" data-action="pal-run" data-idx="${idx}" aria-selected="${idx === pal.sel}">
        ${it.thread ? html`<span class="thread" style="${C.tc(it.thread)};height:18px;justify-self:center"></span>` : icon(it.icon)}
        <span class="po-main"><span class="po-title">${it.title}</span>${it.sub ? html`<span class="po-sub">${it.sub}</span>` : ''}</span>
        <span class="po-tail">${it.tail || ''}</span>
      </div>`; })}</div>`);
    if (pal.sel >= pal.flat.length) pal.sel = Math.max(0, pal.flat.length - 1);
    return html`${res.empty ? html`<div class="pal-empty"><b>No results for “${pal.q.trim()}”.</b><span>Search covers Topics, Tasks, Notes, saved Jira tickets and pull requests${T.canFetch('jira') ? ', and Jira search' : ''}.</span></div>` : ''}${out}`;
  }
  function palBody() {
    return html`<div class="pal-input">${icon('search')}
        <label class="sr-only" for="pal-q">Search Thread</label>
        <input id="pal-q" type="text" role="combobox" aria-expanded="true" aria-controls="pal-list" aria-autocomplete="list" aria-activedescendant="po-${pal.sel}" autocomplete="off" spellcheck="false" placeholder="Search Topics, Tasks, Notes, tickets, pull requests" value="${pal.q}">
        <kbd aria-hidden="true">Esc</kbd></div>
      <div class="pal-list" id="pal-list" role="listbox" aria-label="Results">${palList()}</div>
      <div class="pal-foot"><span><kbd>↑</kbd> <kbd>↓</kbd> move</span><span><kbd>Enter</kbd> open</span><span><kbd>Esc</kbd> close</span>${S().sim.online ? '' : html`<span style="color:var(--warning)">Offline: results from this device only</span>`}</div>`;
  }
  function palRefresh() {
    const list = document.getElementById('pal-list');
    if (!list) return;
    list.innerHTML = T.flat(palList());
    const inp = document.getElementById('pal-q');
    inp.setAttribute('aria-activedescendant', 'po-' + pal.sel);
    const cur = document.getElementById('po-' + pal.sel);
    if (cur) cur.scrollIntoView({ block: 'nearest' });
  }
  A['open-search'] = () => {
    if (T.isDialogOpen('dlg-search')) { focusSoon('#pal-q'); return; }
    ui.navOpen = false;
    pal = { q: '', sel: 0, flat: [] };
    const el = T.openDialog('dlg-search', palBody, { className: 'palette', label: 'Search', manual: true });
    const inp = document.getElementById('pal-q');
    inp.addEventListener('input', () => { pal.q = inp.value; pal.sel = 0; palRefresh(); });
    inp.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        const n = pal.flat.length;
        if (!n) return;
        pal.sel = (pal.sel + (e.key === 'ArrowDown' ? 1 : -1) + n) % n;
        el.querySelectorAll('.pal-opt').forEach((o) => o.setAttribute('aria-selected', o.id === 'po-' + pal.sel ? 'true' : 'false'));
        inp.setAttribute('aria-activedescendant', 'po-' + pal.sel);
        const cur = document.getElementById('po-' + pal.sel);
        if (cur) cur.scrollIntoView({ block: 'nearest' });
      } else if (e.key === 'Enter') {
        e.preventDefault();
        runPal(pal.sel);
      }
    });
    focusSoon('#pal-q');
  };
  function runPal(idx) {
    const it = pal.flat[idx];
    if (!it) return;
    const dlg = document.getElementById('dlg-search');
    const rec = dlg;
    if (rec) rec.close();
    setTimeout(() => it.run(), 10);
  }
  A['pal-run'] = (a) => runPal(Number(a.dataset.idx));

  /* ------------------------------------------------------------ Focus timer */
  const fmtMs = (ms) => { const s = Math.max(0, Math.ceil(ms / 1000)); return T.pad(Math.floor(s / 60)) + ':' + T.pad(s % 60); };
  function remainingMs() {
    const f = S().focus;
    if (f.status === 'running') return f.endsAt - Date.now();
    if (f.status === 'paused') return f.remainingMs;
    return f.workMin * 60000;
  }
  T.updateFocusClock = () => {
    const txt = fmtMs(remainingMs());
    const a = document.getElementById('focus-time');
    if (a) a.textContent = txt;
    const b = document.getElementById('pop-time');
    if (b) b.textContent = txt;
  };
  function focusTargets() {
    const tasks = S().tasks.filter((t) => !t.done && (Q.bucket(t) === 'planned' || Q.bucket(t) === 'overdue'));
    const topics = Q.activeTopics();
    return { tasks, topics };
  }
  function targetLabel(v) {
    if (!v) return '';
    const p = parseKey(v);
    if (p.kind === 'task') { const t = Q.task(p.id); return t ? t.title : ''; }
    const t = Q.topic(p.id); return t ? t.name : '';
  }
  function popBody() {
    const f = S().focus;
    const tg = focusTargets();
    const idle = f.status === 'idle';
    const hist = f.history.slice(0, 4);
    return html`<div class="pop-head"><h2 id="pop-h">Focus timer</h2><span class="concept-tag">Post-pilot concept</span><button class="btn btn-ghost btn-sm btn-icon" type="button" data-action="toggle-focus" aria-label="Close focus timer" style="margin-left:auto">${icon('x', 'icon-sm')}</button></div>
      <div class="timer-face"><span class="timer-time" id="pop-time" role="timer">${fmtMs(remainingMs())}</span><span class="timer-mode">${f.mode === 'work' ? 'Work' : 'Break'}${f.status === 'paused' ? ', paused' : ''}</span></div>
      <div class="timer-controls">
        ${idle ? html`<button class="btn btn-primary" type="button" data-action="focus-start" id="focus-start">${icon('play', 'icon-sm')}Start</button>`
          : f.status === 'running' ? html`<button class="btn" type="button" data-action="focus-pause" id="focus-pause">${icon('pause', 'icon-sm')}Pause</button><button class="btn btn-ghost" type="button" data-action="focus-stop" id="focus-stop">${icon('stop', 'icon-sm')}Stop</button>`
          : html`<button class="btn btn-primary" type="button" data-action="focus-resume" id="focus-resume">${icon('play', 'icon-sm')}Resume</button><button class="btn btn-ghost" type="button" data-action="focus-stop" id="focus-stop">${icon('stop', 'icon-sm')}Stop</button>`}
      </div>
      ${idle ? html`<div class="dur-row">
        <div class="field"><span class="field-label" id="dw-l">Work</span><div class="stepper" role="group" aria-labelledby="dw-l"><button type="button" data-action="focus-dur" data-k="workMin" data-d="-5" aria-label="Shorter work">−</button><output aria-live="polite">${f.workMin} min</output><button type="button" data-action="focus-dur" data-k="workMin" data-d="5" aria-label="Longer work">+</button></div></div>
        <div class="field"><span class="field-label" id="db-l">Break</span><div class="stepper" role="group" aria-labelledby="db-l"><button type="button" data-action="focus-dur" data-k="breakMin" data-d="-1" aria-label="Shorter break">−</button><output aria-live="polite">${f.breakMin} min</output><button type="button" data-action="focus-dur" data-k="breakMin" data-d="1" aria-label="Longer break">+</button></div></div>
      </div>` : ''}
      <label class="field"><span>Focus on (optional)</span><select class="select" id="focus-target" data-change="focus-target" ${idle ? '' : 'disabled'}>
        <option value="">Nothing specific</option>
        ${tg.tasks.length ? html`<optgroup label="Tasks for today">${tg.tasks.map((t) => html`<option value="task:${t.id}" ${f.target === 'task:' + t.id ? 'selected' : ''}>${short(t.title, 40)}</option>`)}</optgroup>` : ''}
        ${tg.topics.length ? html`<optgroup label="Active Topics">${tg.topics.map((t) => html`<option value="topic:${t.id}" ${f.target === 'topic:' + t.id ? 'selected' : ''}>${t.name}</option>`)}</optgroup>` : ''}
      </select></label>
      <div><span class="field-label">Recent sessions</span>${hist.length ? html`<ul class="history" style="margin-top:6px">${hist.map((h) => html`<li><span>${T.fmtTime(h.at)}, ${h.minutes} min</span><span>${short(h.label || 'No target', 26)}</span></li>`)}</ul>` : html`<p class="help">None yet.</p>`}</div>`;
  }
  T.popoverOpen = false;
  function placePopover() {
    const pop = document.getElementById('focus-pop');
    const btn = document.getElementById('focus-btn');
    if (!pop || !btn) return;
    const r = btn.getBoundingClientRect();
    const w = pop.offsetWidth, h = pop.offsetHeight;
    let left = r.right + 10;
    if (left + w > window.innerWidth - 12) left = Math.max(12, window.innerWidth - w - 12);
    const top = Math.max(12, Math.min(r.bottom - h, window.innerHeight - h - 12));
    pop.style.left = left + 'px';
    pop.style.top = top + 'px';
  }
  function paintPopover() {
    const host = document.getElementById('popover-host');
    if (!host) return;
    if (!T.popoverOpen) { host.innerHTML = ''; return; }
    let pop = document.getElementById('focus-pop');
    if (!pop) { host.innerHTML = '<div class="popover" role="dialog" aria-labelledby="pop-h" id="focus-pop"></div>'; pop = document.getElementById('focus-pop'); }
    paint('focus-pop', popBody);
    placePopover();
  }
  T.closePopover = () => {
    if (!T.popoverOpen) return false;
    T.popoverOpen = false;
    paintPopover();
    const b = document.getElementById('focus-btn');
    if (b) { b.setAttribute('aria-expanded', 'false'); if (document.activeElement === document.body || !document.activeElement) b.focus(); }
    return true;
  };
  A['toggle-focus'] = () => {
    if (T.popoverOpen) { T.closePopover(); focusSoon('#focus-btn'); return; }
    T.popoverOpen = true;
    paintPopover();
    const b = document.getElementById('focus-btn');
    if (b) b.setAttribute('aria-expanded', 'true');
    focusSoon('#focus-start, #focus-pause, #focus-resume');
  };
  document.addEventListener('mousedown', (e) => {
    if (!T.popoverOpen) return;
    if (e.target.closest('#focus-pop') || e.target.closest('#focus-btn')) return;
    T.closePopover();
  });
  window.addEventListener('resize', () => { if (T.popoverOpen) placePopover(); });
  function focusCommit(fn) { T.commit(fn, { silent: true }); T.render(); paintPopover(); }
  A['focus-start'] = () => { focusCommit((s) => { const f = s.focus; f.status = 'running'; f.mode = 'work'; f.endsAt = Date.now() + f.workMin * 60000; f.label = targetLabel(f.target); }); focusSoon('#focus-pause'); };
  A['focus-pause'] = () => { focusCommit((s) => { const f = s.focus; f.remainingMs = f.endsAt - Date.now(); f.status = 'paused'; f.endsAt = null; }); focusSoon('#focus-resume'); };
  A['focus-resume'] = () => { focusCommit((s) => { const f = s.focus; f.endsAt = Date.now() + f.remainingMs; f.status = 'running'; }); focusSoon('#focus-pause'); };
  A['focus-stop'] = () => {
    focusCommit((s) => {
      const f = s.focus;
      const total = (f.mode === 'work' ? f.workMin : f.breakMin) * 60000;
      const left = f.status === 'running' ? f.endsAt - Date.now() : f.remainingMs;
      const mins = Math.floor((total - left) / 60000);
      if (f.mode === 'work' && mins >= 1) f.history.unshift({ at: s.sim.now, mode: 'work', minutes: mins, label: f.label || '' });
      f.status = 'idle'; f.mode = 'work'; f.endsAt = null; f.remainingMs = f.workMin * 60000;
    });
    focusSoon('#focus-start');
  };
  A['focus-dur'] = (a) => {
    const k = a.dataset.k;
    const d = Number(a.dataset.d);
    focusCommit((s) => { const f = s.focus; f[k] = Math.min(k === 'workMin' ? 90 : 30, Math.max(k === 'workMin' ? 5 : 1, f[k] + d)); f.remainingMs = f.workMin * 60000; });
  };
  CH['focus-target'] = (el) => focusCommit((s) => { s.focus.target = el.value; });
  T.tick = () => {
    const f = S().focus;
    if (f.status === 'running' && Date.now() >= f.endsAt) {
      if (f.mode === 'work') {
        focusCommit((s) => { const x = s.focus; x.history.unshift({ at: s.sim.now, mode: 'work', minutes: x.workMin, label: x.label || '' }); x.mode = 'break'; x.endsAt = Date.now() + x.breakMin * 60000; });
        T.toast('Work session done. A ' + f.breakMin + ' minute break started.');
      } else {
        focusCommit((s) => { const x = s.focus; x.status = 'idle'; x.mode = 'work'; x.endsAt = null; x.remainingMs = x.workMin * 60000; });
        T.toast('Break over. Start another session when you are ready.');
      }
    }
    T.updateFocusClock();
  };

  /* ------------------------------------------------------------ Demo panel */
  function demoBody() {
    const s = S();
    const r = T.route();
    const onNote = r.name === 'notes' && r.id && Q.note(r.id);
    const seg = (k, opts) => html`<div class="seg" role="group" aria-label="${k}">${opts.map((o) => html`<button type="button" data-action="sim" data-k="${k}" data-v="${o[0]}" aria-pressed="${String(s.sim[k]) === o[0]}" id="sim-${k}-${o[0]}">${o[1]}</button>`)}</div>`;
    const tog = (k, label) => html`<button class="btn btn-sm" type="button" data-action="sim-toggle" data-k="${k}" aria-pressed="${!!s.sim[k]}" id="sim-${k}">${s.sim[k] ? icon('check', 'icon-sm') : ''}${label}</button>`;
    return html`<div class="dlg-head"><h2 id="demo-h">Demo controls</h2>${closeBtn('dlg-demo')}</div>
      <div class="dlg-body">
        <p class="demo-note">${icon('flask', 'icon-sm')}<span>These controls simulate states for review. Nothing leaves this browser.</span></p>
        <div class="demo-group"><h3>Network</h3>${seg('online', [['true', 'Online'], ['false', 'Offline']])}</div>
        <div class="demo-group"><h3>Jira${s.conn.jira ? '' : ' (not connected)'}</h3>${seg('jira', [['ok', 'Working'], ['expired', 'Access expired'], ['ratelimited', 'Rate limited']])}</div>
        <div class="demo-group"><h3>GitHub${s.conn.github ? '' : ' (not connected)'}</h3>${seg('github', [['ok', 'Working'], ['expired', 'Access expired'], ['ratelimited', 'Rate limited']])}</div>
        <div class="demo-group"><h3>Note saving</h3>
          <div class="demo-row">${tog('saveFail', 'Device save fails')}${tog('syncFail', 'Cloud sync fails')}</div>
          <div class="demo-row"><button class="btn btn-sm" type="button" data-action="sim-conflict" ${onNote ? '' : 'disabled'} id="sim-conflict">Create a conflict on this note</button></div>
          ${onNote ? '' : html`<p class="help">Open a note to create a conflict. Failures apply to the next edit.</p>`}
        </div>
        <div class="demo-group"><h3>Clock</h3>
          <div class="demo-row" style="align-items:center"><span class="tnum" style="min-width:120px">${T.fmtWhen(s.sim.now)}</span><button class="btn btn-sm" type="button" data-action="sim-clock" data-v="60" id="sim-clock">Advance 1 hour</button><button class="btn btn-sm btn-ghost" type="button" data-action="sim-clock" data-v="reset">Reset</button></div>
          <p class="help">Advancing shows due reminders, overdue Tasks and the end of a rate limit.</p>
        </div>
        <div class="demo-group"><h3>Workspace</h3>
          <div class="demo-row"><button class="btn btn-sm" type="button" data-action="reset-demo" data-kind="example">Reset example data</button><button class="btn btn-sm" type="button" data-action="reset-demo" data-kind="empty">Empty workspace</button><a class="btn btn-sm btn-ghost" href="index.html">Prototype map</a></div>
        </div>
      </div>`;
  }
  A['open-demo'] = () => {
    ui.navOpen = false;
    T.openDialog('dlg-demo', demoBody, { className: 'dlg demo-panel', labelledby: 'demo-h' });
    focusSoon('#sim-online-' + String(S().sim.online));
  };
  function applySim(k, v) {
    const s = S();
    if (k === 'online') {
      const on = v === 'true' || v === true;
      if (s.sim.online === on) return;
      s.sim.online = on;
      T.persist();
      if (on) {
        const pend = C.pendingSync();
        T.syncPending();
        T.toast('Back online.' + (pend ? ' Syncing changes made offline.' : ''));
        T.refresh('jira'); T.refresh('github');
      } else T.toast('Offline (simulated). Edits keep saving on this device.');
    } else if (k === 'jira' || k === 'github') {
      s.sim[k] = v;
      if (v === 'ratelimited') s.sim[k + 'RetryAt'] = T.addMinutes(s.sim.now, 15); else delete s.sim[k + 'RetryAt'];
      T.persist();
    }
  }
  A.sim = (a) => { applySim(a.dataset.k, a.dataset.v); T.render(); };
  A['sim-toggle'] = (a) => { T.commit((s) => { s.sim[a.dataset.k] = !s.sim[a.dataset.k]; }); };
  A['sim-conflict'] = () => { const r = T.route(); T.closeDialog('dlg-demo'); makeConflict(r.id); };
  A['sim-clock'] = (a) => {
    const s = S();
    const from = s.sim.now;
    if (a.dataset.v === 'reset') {
      s.sim.now = T.D.DEMO_NOW;
      Object.keys(s.alerted).forEach((id) => { const r = s.reminders.find((x) => x.id === id); if (!r || r.at > s.sim.now) delete s.alerted[id]; });
    } else s.sim.now = T.addMinutes(from, Number(a.dataset.v));
    ['jira', 'github'].forEach((k) => {
      if (s.sim[k] === 'ratelimited' && s.sim[k + 'RetryAt'] && s.sim.now >= s.sim[k + 'RetryAt']) { s.sim[k] = 'ok'; delete s.sim[k + 'RetryAt']; T.toast((k === 'jira' ? 'Jira' : 'GitHub') + ' rate limit ended. Refresh works again.'); }
    });
    T.persist();
    T.render();
    if (a.dataset.v !== 'reset') checkAlarms(from, s.sim.now);
  };

  /* -------------------------------------------------- inbox list arrow keys */
  document.addEventListener('keydown', (e) => {
    const b = e.target.closest && e.target.closest('.inbox-open');
    if (!b || (e.key !== 'ArrowDown' && e.key !== 'ArrowUp')) return;
    e.preventDefault();
    const all = Array.from(document.querySelectorAll('.inbox-open'));
    const i = all.indexOf(b) + (e.key === 'ArrowDown' ? 1 : -1);
    if (i < 0 || i >= all.length) return;
    ui.inboxSel = all[i].dataset.key;
    T.render();
    focusSoon('.inbox-open[data-key="' + ui.inboxSel + '"]');
  });

  /* Cmd or Ctrl + Enter submits the form in an open dialog. Enter in the
     Task title saves instead of adding a line break. */
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    if (e.target.id === 'td-title') { e.preventDefault(); e.target.dispatchEvent(new Event('change', { bubbles: true })); return; }
    if ((e.metaKey || e.ctrlKey) && e.target.closest('dialog form')) { e.preventDefault(); e.target.closest('form').requestSubmit(); }
  });

  /* ------------------------------------------------------ route side effects */
  const SIMS = {
    offline: () => applySim('online', 'false'),
    online: () => applySim('online', 'true'),
    'jira-expired': () => applySim('jira', 'expired'),
    'jira-limited': () => applySim('jira', 'ratelimited'),
    'github-expired': () => applySim('github', 'expired'),
    'github-limited': () => applySim('github', 'ratelimited'),
    'save-fail': () => { S().sim.saveFail = true; T.persist(); },
    'sync-fail': () => { S().sim.syncFail = true; T.persist(); },
    conflict: (r) => { if (r.name === 'notes' && r.id) setTimeout(() => makeConflict(r.id), 20); },
    empty: () => resetAll('empty'),
    reset: () => resetAll('example')
  };
  T.onRoute = (r, pathChanged) => {
    const sim = r.q.get('sim');
    if (sim && SIMS[sim]) {
      T.replaceQuery((q) => q.delete('sim'));
      SIMS[sim](r);
      if (sim !== 'empty' && sim !== 'reset') T.render();
      if (sim === 'empty' || sim === 'reset') return;
    }
    const sec = r.q.get('sec');
    if (r.name === 'settings' && sec) setTimeout(() => { const el = document.getElementById('set-' + sec); if (el) el.scrollIntoView({ block: 'start' }); }, 30);
    if (pathChanged && r.name !== 'notes') ui.linkForm = false;
    const listName = (r.name === 'jira' || r.name === 'prs') ? r.name : null;
    if (listName && ui.lastList !== listName) T.refresh(listName === 'jira' ? 'jira' : 'github');
    ui.lastList = listName;
    let detail = null;
    if (r.name === 'jira' && r.id) detail = 'jira:' + r.id;
    else if (r.name === 'prs' && r.id) detail = 'github:' + r.id;
    else if (r.name === 'topic' && r.q.get('jira')) detail = 'jira:' + r.q.get('jira');
    else if (r.name === 'topic' && r.q.get('pr')) detail = 'github:' + r.q.get('pr');
    if (detail && detail !== ui.lastDetail) {
      const i = detail.indexOf(':');
      const src = detail.slice(0, i), key = detail.slice(i + 1);
      if (src === 'jira' ? Q.jiraCached(key) : Q.prCached(key)) T.refresh(src, key);
    }
    ui.lastDetail = detail;
  };
  T.afterRender = (r, name) => {
    if (name === 'notes') bindEditor();
    if (T.popoverOpen) paintPopover();
  };
})();
