/* Topic details: one page. Identity and status on top, "Pick up here", then collapsible sections.
   Jira and PR rows open in a side panel on wide screens, or as their own page on narrow ones. */
(function () {
  const T = window.T;
  const S = T.sel;
  const D = T.D;
  const html = T.html;
  const I = T.I;
  const X = T.ext;

  const id = T.params.get('id') || (S.topicsInOrder()[0] || {}).id;
  T.captureTopic = id || null;
  const page = { open: { tasks: true, notes: true, jira: true, prs: true, reminders: false }, showDone: false, panel: null };
  const COLORS = ['lavender', 'mint', 'sky', 'peach', 'butter'];
  const itemHref = (kind, key) => 'item.html?kind=' + kind + '&id=' + encodeURIComponent(key) + '&from=topic:' + id;

  function section(key, title, count, acts, body) {
    const open = page.open[key];
    return html`<section class="sect" aria-labelledby="sh-${key}">
      <div class="sect__head"><h2 id="sh-${key}"><button class="sect__toggle" type="button" aria-expanded="${open}" aria-controls="sb-${key}"
        data-act="td-sect" data-s="${key}" data-k="sect-${key}"><span class="chev">${I('chevDown', 18)}</span>${title} <span class="n">${count}</span></button></h2>
        ${acts ? html`<div class="sect__acts">${acts}</div>` : ''}</div>
      <div class="sect__body" id="sb-${key}" ${open ? '' : 'hidden'}>${body}</div>
    </section>`;
  }

  function tasksSection(tp) {
    const ts = S.topicTasks(tp.id);
    const open = ts.filter((t) => !t.done);
    const done = ts.filter((t) => t.done);
    return section('tasks', 'Tasks', open.length + ' open', '', html`
      <form class="addtask" data-submit="td-add-task" autocomplete="off">
        <label class="vh" for="td-new-task">New task in ${tp.name}</label>
        <input class="input" id="td-new-task" name="title" placeholder="Add a task to this Topic" data-k="td-new-task" required>
        <button class="btn btn--line" type="submit">Add</button>
      </form>
      ${open.length ? html`<ul class="tlist">${open.map((t) => T.taskRow(t, { hideTopic: true, showPlanned: true }))}</ul>`
        : html`<p class="sect__empty">${done.length ? 'All tasks are done.' : 'No tasks yet.'}</p>`}
      ${done.length ? html`<button class="btn btn--text btn--sm" type="button" data-act="td-done" aria-expanded="${page.showDone}" data-k="td-done">
        ${I(page.showDone ? 'chevDown' : 'chevRight', 15)} Completed <span class="n">${done.length}</span></button>
        ${page.showDone ? html`<ul class="tlist">${done.map((t) => T.taskRow(t, { hideTopic: true }))}</ul>` : ''}` : ''}`);
  }

  function notesSection(tp) {
    const ns = S.notesForTopic(tp.id);
    const acts = html`<button class="btn btn--text btn--sm" type="button" data-act="td-new-note">${I('plus', 15)} New note</button>
      <button class="btn btn--text btn--sm" type="button" data-act="td-link-note">${I('link', 15)} Link existing</button>`;
    return section('notes', 'Notes', ns.length, acts, ns.length ? html`<ul class="tlist">${ns.map((n) => {
      const others = n.topicIds.filter((x) => x !== tp.id).map(S.topic).filter(Boolean);
      return html`<li class="xrow"><span class="xrow__icon">${I('notes', 18)}</span>
        <a class="xrow__main" href="notes.html?id=${n.id}"><span class="xrow__title">${n.title || 'Untitled note'}</span>
          <span class="xrow__snip">${T.plain(n.body) || 'Empty note'}</span>
          ${others.length ? html`<span class="xrow__also">Also in ${others.map((o) => T.topicTag(o, { plain: true }))}</span>` : ''}</a>
        <button class="iconbtn iconbtn--sm" type="button" data-act="td-unlink-note" data-id="${n.id}" aria-label="Unlink note ${n.title || 'Untitled note'} from this Topic">${I('unlink', 16)}</button></li>`;
    })}</ul>` : html`<p class="sect__empty">No notes linked. A note can belong to several Topics.</p>`);
  }

  function extSection(tp, kind) {
    const sys = kind === 'jira' ? 'jira' : 'github';
    const key = kind === 'jira' ? 'jira' : 'prs';
    const title = kind === 'jira' ? 'Jira' : 'Pull requests';
    const ids = S.externalsForTopic(tp.id, kind);
    if (!X.connected(sys)) {
      return section(key, title, ids.length, '', html`<p class="sect__empty">${kind === 'jira' ? 'Jira' : 'GitHub'} is not connected. <a class="lnk" href="settings.html#connections">Connect in Settings</a></p>`);
    }
    const acts = html`<button class="btn btn--text btn--sm" type="button" data-act="td-link-ext" data-kind="${kind}">${I('link', 15)} Link</button>`;
    const rows = ids.map((x) => {
      const it = kind === 'jira' ? S.jira(x) : S.pr(x);
      if (!it) return '';
      const isOpen = page.panel && page.panel.kind === kind && page.panel.id === x;
      const label = kind === 'jira' ? it.key : '#' + it.number;
      return html`<li class="xrow ${isOpen ? 'is-open' : ''}"><span class="xrow__icon">${I(kind === 'jira' ? 'jira' : 'pr', 18)}</span>
        <a class="xrow__main" href="${itemHref(kind, x)}" data-act="td-open-ext" data-kind="${kind}" data-id="${x}" ${isOpen ? T.raw('aria-current="true"') : ''}>
          <span class="xrow__key">${kind === 'jira' ? html`<b>${it.key}</b><span class="jst">${it.status}</span>` : html`<b>#${it.number}</b>${X.ci(it)}${X.review(it)}`}</span>
          <span class="xrow__title">${it.title}</span></a>
        <button class="iconbtn iconbtn--sm" type="button" data-act="ext-unlink" data-kind="${kind}" data-id="${x}" data-topic="${tp.id}" aria-label="Unlink ${label} from this Topic">${I('unlink', 16)}</button></li>`;
    });
    return section(key, title, ids.length, acts, ids.length ? html`<ul class="tlist">${rows}</ul>`
      : html`<p class="sect__empty">Nothing linked. Link ${kind === 'jira' ? 'a ticket' : 'a pull request'} here or from its page.</p>`);
  }

  function remindersSection(tp) {
    const rs = S.remindersForTopic(tp.id);
    const acts = html`<button class="btn btn--text btn--sm" type="button" data-act="td-add-rem">${I('plus', 15)} Add</button>`;
    return section('reminders', 'Reminders', rs.length, acts, rs.length ? html`<ul class="tlist">${rs.map((r) => {
      const tg = S.targetOf(r);
      return html`<li class="xrow"><span class="xrow__icon">${I('bell', 18)}</span>
        <button class="xrow__main" type="button" data-act="open-rem-target" data-id="${r.id}">
          <span class="xrow__key"><b>${D.when(r.at)}</b>${tg && tg.kind !== 'topic' ? html`<span>${tg.kind === 'task' ? 'Task' : 'Note'}: ${tg.label}</span>` : ''}</span>
          <span class="xrow__title">${r.text}</span></button>
        <button class="iconbtn iconbtn--sm" type="button" data-act="delete-reminder" data-id="${r.id}" aria-label="Move reminder ${D.when(r.at)} to Trash">${I('trash', 16)}</button></li>`;
    })}</ul>` : html`<p class="sect__empty">No reminders for this Topic or its tasks and notes.</p>`);
  }

  /* A deleted Topic keeps its page: it says what went to Trash with it and offers Restore. */
  const trashEntry = () => T.state.trash.find((e) => e.kind === 'topic' && e.item.id === id);
  const count = (n, one, many) => n + ' ' + (n === 1 ? one : many);
  function trashedState(e) {
    const tasks = e.bundle.tasks.length;
    const rems = e.bundle.reminders.length;
    const notes = e.context.noteIds.length;
    const ext = e.context.linkKeys.length;
    const went = tasks || rems ? 'Its ' + [tasks ? count(tasks, 'task', 'tasks') : '', rems ? count(rems, 'reminder', 'reminders') : ''].filter(Boolean).join(' and ') + ' went with it.' : 'It had no tasks or reminders.';
    const kept = [notes ? count(notes, 'note', 'notes') + ' stay in Notes, unlinked from it.' : '',
      ext ? count(ext, 'Jira or pull request link was', 'Jira and pull request links were') + ' removed. The tickets and pull requests are not changed.' : ''].filter(Boolean).join(' ');
    return html`<a class="back" href="topics.html">${I('chevLeft', 16)} Topics</a>
      <section class="gone" style="${T.topicVar(e.item)}" aria-labelledby="gone-h">
        ${T.hex(e.item, 'lg')}
        <h1 class="gone__h" id="gone-h" tabindex="-1">${e.item.name} is in Trash</h1>
        <p class="gone__p">${went} ${kept}</p>
        <p class="gone__p">Restore brings back the Topic, its tasks and reminders, and its links to notes, tickets and pull requests.</p>
        <div class="gone__acts"><button class="btn btn--primary" type="button" data-act="td-restore" data-id="${e.id}" data-k="td-restore">Restore Topic</button>
          <a class="btn btn--line" href="trash.html?id=${e.id}">Open in Trash</a></div>
      </section>`.s;
  }

  function panel() {
    const p = page.panel;
    const detail = p.kind === 'jira'
      ? X.jiraDetail(S.jira(p.id), { relHref: (k) => itemHref('jira', k), relAct: 'td-open-rel' })
      : X.prDetail(S.pr(p.id));
    return html`<div class="side-panel" role="region" aria-label="Linked item">
      <div class="side-panel__bar"><button class="btn btn--text btn--sm" type="button" data-act="td-panel-close" data-k="td-panel-close">${I('chevLeft', 15)} Back to sections</button>
        <a class="btn btn--text btn--sm" href="${itemHref(p.kind, p.id)}">Open as page ${I('external', 14)}</a></div>
      ${detail}</div>`;
  }

  T.definePage({
    skip: (source) => source === 'sync',
    render(main) {
      const tp = S.topic(id);
      if (!tp) {
        const tr = trashEntry();
        main.innerHTML = tr ? trashedState(tr)
          : T.empty({ icon: 'topics', title: 'Topic not found', text: 'It may have been removed in another tab.',
            action: html`<a class="btn btn--line" href="topics.html">Open Topics</a>` }).s;
        return;
      }
      document.title = tp.name + ' | Thread v3';
      const st = S.statusOf(tp);
      if (page.panel && window.innerWidth < 1000) page.panel = null;
      main.innerHTML = html`
        <a class="back" href="topics.html">${I('chevLeft', 16)} Topics</a>
        <header class="td-head" style="${T.topicVar(tp)}">
          ${T.hex(tp, 'lg')}
          <h1 class="td-name">${tp.name}</h1>
          <div class="td-meta">
            <span class="st st--${st.category}">${T.swatch(st.category)}</span>
            <label class="vh" for="td-status">Status</label>
            <select class="input input--sm input--auto" id="td-status" data-change="td-status" data-k="td-status">
              ${T.state.statuses.map((s) => html`<option value="${s.id}" ${s.id === tp.statusId ? 'selected' : ''}>${s.name === T.CAT_LABEL[s.category] ? s.name : s.name + ' (' + T.CAT_LABEL[s.category] + ')'}</option>`)}</select>
            ${T.progress(tp)}
          </div>
          <p class="td-goal">${tp.goal || html`<span class="muted">No goal yet. Use Edit to add one.</span>`}</p>
          <div class="td-acts">
            <button class="btn btn--line" type="button" data-act="td-edit">${I('edit', 16)} Edit</button>
            <button class="btn btn--primary" type="button" data-act="td-add-task-focus">Add task</button>
            <button class="btn btn--text btn--danger-text" type="button" data-act="td-trash" aria-describedby="td-trash-hint">${I('trash', 16)} Move to Trash</button>
            <span class="vh" id="td-trash-hint">Its tasks and reminders go with it. Notes stay in Notes. You can undo or restore it from Trash.</span>
          </div>
        </header>
        <section class="pickup" style="${T.topicVar(tp)}" aria-labelledby="pu-h">
          <h2 class="pickup__h" id="pu-h">Pick up here</h2>
          <label class="field"><span class="field__label">Where I left off</span>
            <textarea class="input" rows="3" data-change="td-field" data-f="leftOff" data-k="td-left" placeholder="What were you in the middle of?">${tp.leftOff}</textarea></label>
          <label class="field"><span class="field__label">Next step</span>
            <textarea class="input" rows="3" data-change="td-field" data-f="nextStep" data-k="td-next" placeholder="The first thing to do when you come back">${tp.nextStep}</textarea></label>
        </section>
        <div class="td-grid">
          <div class="td-main">${tasksSection(tp)}${notesSection(tp)}</div>
          <aside class="td-side" aria-label="Linked work and reminders">
            ${page.panel ? panel() : html`${extSection(tp, 'jira')}${extSection(tp, 'pr')}${remindersSection(tp)}`}
          </aside>
        </div>`.s;
    }
  });

  T.on('td-sect', (el) => { page.open[el.dataset.s] = !page.open[el.dataset.s]; T.rerender('local'); });
  T.on('td-done', () => { page.showDone = !page.showDone; T.rerender('local'); });
  T.on('td-status', (el) => {
    const tp = S.topic(id);
    const prev = tp.statusId;
    T.act.setTopicStatus(id, el.value);
    T.toast('Status changed to ' + S.status(el.value).name, { undo: () => T.act.setTopicStatus(id, prev) });
  });
  T.on('td-field', (el) => T.act.updateTopic(id, { [el.dataset.f]: el.value.trim() }));
  T.on('td-add-task-focus', () => {
    page.open.tasks = true;
    T.rerender('local');
    const el = document.getElementById('td-new-task');
    if (el) { el.focus(); el.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
  });
  T.on('td-add-task', (form) => {
    const title = form.title.value.trim();
    if (!title) return;
    const t = T.act.createTask({ title, topicId: id });
    T.toast('Task added', { undo: () => T.act.discardTask(t.id) });
    const el = document.getElementById('td-new-task');
    if (el) { el.value = ''; el.focus(); }
  });
  T.on('td-new-note', () => {
    const n = T.act.createNote({ title: '', topicIds: [id] });
    location.href = 'notes.html?id=' + n.id;
  });
  T.on('td-link-note', async () => {
    const items = T.state.notes.filter((n) => !n.topicIds.includes(id)).map((n) => ({
      id: n.id, icon: 'notes', label: n.title || 'Untitled note',
      sub: n.topicIds.length ? n.topicIds.map((x) => (S.topic(x) || {}).name).join(', ') : n.inbox ? 'In Inbox' : 'Standalone'
    }));
    const nid = await T.pickItem({ title: 'Link a note', items, empty: 'Every note is already linked here.' });
    if (!nid) return;
    T.act.linkNote(nid, id);
    T.toast('Note linked', { undo: () => T.act.unlinkNote(nid, id) });
  });
  T.on('td-unlink-note', (el) => {
    const nid = el.dataset.id;
    T.act.unlinkNote(nid, id);
    T.toast('Note unlinked. It still exists in Notes.', { undo: () => T.act.linkNote(nid, id) });
  });
  T.on('td-link-ext', async (el) => {
    const kind = el.dataset.kind;
    const linked = S.externalsForTopic(id, kind);
    const items = kind === 'jira'
      ? T.jiraIssues.filter((j) => !linked.includes(j.key) && X.jiraAvailable(j)).map((j) => ({ id: j.key, icon: 'jira', label: j.key + ' ' + j.title, sub: j.status }))
      : T.pullRequests.filter((p) => !linked.includes(p.id) && X.prAvailable(p)).map((p) => ({ id: p.id, icon: 'pr', label: p.title + ' #' + p.number, sub: p.repo }));
    const x = await T.pickItem({ title: kind === 'jira' ? 'Link a Jira ticket' : 'Link a pull request', items,
      empty: 'Nothing else is available on this device. Search Jira or open Pull requests to find more.' });
    if (!x) return;
    T.act.linkExternal(kind, x, id);
    T.toast('Linked', { undo: () => T.act.unlinkExternal(kind, x, id) });
  });
  T.on('td-open-ext', (el, e) => {
    if (window.innerWidth < 1000) return; /* narrow: follow the link to item.html */
    e.preventDefault();
    page.panel = { kind: el.dataset.kind, id: el.dataset.id };
    if (page.panel.kind === 'jira') X.markDownloaded(S.jira(page.panel.id));
    T.rerender('local');
    const h = document.getElementById('xd-title');
    if (h) h.focus();
  });
  T.on('td-open-rel', (el, e) => {
    if (window.innerWidth < 1000) return;
    e.preventDefault();
    page.panel = { kind: 'jira', id: el.dataset.key };
    X.markDownloaded(S.jira(page.panel.id));
    T.rerender('local');
    const h = document.getElementById('xd-title');
    if (h) h.focus();
  });
  T.on('td-panel-close', () => {
    const was = page.panel;
    page.panel = null;
    T.rerender('local');
    const back = was && document.querySelector('[data-act="td-open-ext"][data-id="' + was.id + '"]');
    if (back) back.focus();
  });
  T.on('td-add-rem', () => { const tp = S.topic(id); T.addReminderFor({ kind: 'topic', id }, 'Check ' + tp.name); });

  /* Move to Trash is instant with Undo (owner decision, 2026-11-13). The page then shows what went to Trash. */
  T.on('td-trash', () => {
    const tp = S.topic(id);
    page.panel = null;
    const entry = T.act.trashTopic(id);
    if (!entry) return;
    T.toast('Moved to Trash: ' + tp.name, { undo: () => restoreHere(entry.id) });
    const h = document.getElementById('gone-h');
    if (h) h.focus();
  });
  function restoreHere(trashId) {
    const r = T.act.restore(trashId);
    if (!r) return;
    const h = document.querySelector('.td-name');
    if (h) { h.tabIndex = -1; h.focus(); }
    return r;
  }
  T.on('td-restore', (el) => {
    const r = restoreHere(el.dataset.id);
    if (r) T.toast('Restored: ' + r.entry.item.name);
  });
  T.on('open-rem-target', (el) => { const r = S.reminder(el.dataset.id); if (r) T.openTarget(r.target); });

  T.on('td-edit', () => {
    const tp = S.topic(id);
    const d = T.dialog(html`<form method="dialog" class="dlg__in">
      <h2 class="dlg__title" id="dlg-title">Edit Topic</h2>
      <label class="field"><span class="field__label">Name</span><input class="input" name="name" value="${tp.name}" required></label>
      <label class="field"><span class="field__label">Goal</span><textarea class="input" name="goal" rows="3">${tp.goal}</textarea></label>
      <fieldset class="field colors"><legend class="field__label">Sticker color</legend>
        ${COLORS.map((c) => html`<label title="${c}"><input type="radio" name="color" value="${c}" ${c === tp.color ? 'checked' : ''} aria-label="${c}">
          ${T.hex({ name: tp.name, color: c }, 'md')}</label>`)}</fieldset>
      <div class="dlg__acts"><button class="btn btn--line" value="cancel" formnovalidate>Cancel</button>
        <button class="btn btn--primary" value="ok">Save changes</button></div></form>`,
    { onClose: (v) => {
      if (v !== 'ok') return;
      const f = d.querySelector('form');
      const name = f.elements.name.value.trim();
      if (!name) return;
      T.act.updateTopic(id, { name, goal: f.elements.goal.value.trim(), color: f.elements.color.value });
      T.toast('Topic saved');
    } });
    d.querySelector('input[name="name"]').focus();
  });
  matchMedia('(min-width: 1000px)').addEventListener('change', (m) => { if (page.panel && !m.matches) T.rerender('local'); });
})();
