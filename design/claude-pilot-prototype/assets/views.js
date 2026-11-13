/* Page views. Each returns markup for the main region, or null when it
   patched the DOM itself (the note editor must never re-render while typing). */
(function () {
  'use strict';
  const T = window.Thread;
  const { html, raw, icon, glyph, Q, ui } = T;
  const V = T.views;
  const C = T.c;

  const openFirst = (a, b) => (a.done === b.done ? 0 : a.done ? 1 : -1);
  const topicOptions = (selected, opts) => {
    opts = opts || {};
    const list = T.state.topics.slice().sort((a, b) => catRank(a) - catRank(b));
    return html`${opts.none ? html`<option value="" ${!selected ? 'selected' : ''}>${opts.none}</option>` : ''}${list.map((t) => html`<option value="${t.id}" ${selected === t.id ? 'selected' : ''}>${t.name}${Q.cat(t) === 'done' ? ' (done)' : ''}</option>`)}`;
  };
  const CAT_ORDER = { active: 0, planned: 1, paused: 2, done: 3 };
  function catRank(t) { return CAT_ORDER[Q.cat(t)] * 100 + T.state.statuses.findIndex((s) => s.id === t.statusId); }
  const clip = (str, n) => (str.length > n ? str.slice(0, n).replace(/\s+\S*$/, '').replace(/[\s.,;:]+$/, '') + '…' : str);
  const statusOptions = (selected) => T.state.statuses.map((s) => html`<option value="${s.id}" ${s.id === selected ? 'selected' : ''}>${s.name}${s.name !== T.CAT_LABEL[s.category] ? ' (' + T.CAT_LABEL[s.category] + ')' : ''}</option>`);
  T.topicOptions = topicOptions;
  T.statusOptions = statusOptions;

  /* ---------------------------------------------------------------- Welcome */
  V.welcome = () => {
    const step = ui.welcomeStep || 'signin';
    return html`<div class="welcome"><div class="welcome-card">
      <div class="welcome-mark"><svg viewBox="0 0 24 24" aria-hidden="true"><use href="#i-logo"></use></svg></div>
      <div>
        <h1>Thread</h1>
        <p class="welcome-lede">Your Topics, Tasks and Notes, with the Jira tickets and pull requests behind them, in one place.</p>
      </div>
      ${step === 'signin' ? html`
        <div class="welcome-panel">
          <button class="btn btn-primary" type="button" data-action="welcome-signin" id="welcome-signin">${icon('user')}Continue with the demo account</button>
          <p class="fine">${icon('lock')}<span>Demo sign-in. It asks for no password and sends nothing. Data stays in this browser.</span></p>
        </div>` : html`
        <form class="welcome-panel" data-form="welcome-setup">
          <div class="signed-as"><span class="avatar" aria-hidden="true">DE</span><span>Signed in as <b>Demo Engineer</b> <span class="faint">(demo account)</span></span></div>
          <fieldset class="choice">
            <legend>How do you want to start?</legend>
            <label><input type="radio" name="ws" value="example" checked><span><b>Example workspace</b><span>Six Topics with Tasks, Notes, and linked Jira and GitHub items. All synthetic.</span></span></label>
            <label><input type="radio" name="ws" value="empty"><span><b>Empty workspace</b><span>Nothing yet. Jira and GitHub start disconnected.</span></span></label>
          </fieldset>
          <button class="btn btn-primary" type="submit" id="welcome-open">Open Thread</button>
        </form>`}
      <a class="welcome-skip btn-link" href="#/today">Skip and keep the current demo data</a>
    </div></div>`;
  };

  /* ------------------------------------------------------------------ Today */
  V.today = () => {
    const s = T.state;
    const b = Q.todayBuckets();
    Object.keys(b).forEach((k) => b[k].sort(openFirst));
    const d = new Date(T.today() + 'T00:00');
    const open = (l) => l.filter((t) => !t.done).length;
    const overdueN = open(b.overdue);
    const todayN = open(b.planned) + open(b.earlier);
    const doneN = s.tasks.filter((t) => t.done && t.completedAt && t.completedAt.slice(0, 10) === T.today()).length;
    const rems = Q.todayReminders();
    const active = Q.activeTopics();
    const reviews = Q.reviewPrs();
    const hasDeadlineOnly = b.planned.some((t) => t.planned !== T.today());
    const empty = !b.overdue.length && !b.planned.length && !b.earlier.length;
    const lastTopic = ui.todayTopic || '';

    const group = (key, title, list, extra) => list.length ? html`<section class="group" aria-labelledby="g-${key}">
      <h3 class="group-h ${key === 'overdue' ? 'is-danger' : ''}" id="g-${key}">${key === 'overdue' ? icon('alert', 'icon-sm') : ''}${title}<span class="count">${open(list)}</span></h3>
      ${extra || ''}
      ${list.map((t) => C.taskRow(t, { side: key === 'earlier' && !t.done ? (x) => html`<button class="btn btn-ghost btn-sm" type="button" data-action="plan-today" data-id="${x.id}" data-fk="pt-${x.id}">Move to today</button>` : null }))}
    </section>` : '';

    return html`<div class="page">
      <header class="today-head">
        <div class="dayplate">
          <span class="day-num" aria-hidden="true">${d.getDate()}</span>
          <div class="day-words"><h1><span class="sr-only">Today, </span>${T.DOW[d.getDay()]}</h1><span>${T.MON[d.getMonth()]} ${d.getFullYear()}, ${T.fmtTime(T.now())}</span></div>
        </div>
        <p class="today-summary">
          ${overdueN ? html`<span class="is-danger"><b>${overdueN}</b> overdue</span>` : ''}
          <span><b>${todayN}</b> to do today</span>
          <span><b>${doneN}</b> done today</span>
        </p>
      </header>
      <div class="today-grid">
        <section class="sheet" aria-labelledby="today-tasks-h">
          <div class="sheet-head"><h2 id="today-tasks-h">Tasks</h2></div>
          <form class="inline-add" data-form="today-add">
            ${icon('plus')}
            <label class="sr-only" for="today-add-title">New Task for today</label>
            <input type="text" id="today-add-title" name="title" placeholder="Add a Task for today" autocomplete="off" required>
            <label class="sr-only" for="today-add-topic">Topic for the new Task</label>
            <select class="select" id="today-add-topic" name="topic">${topicOptions(lastTopic, { none: 'No Topic' })}</select>
            <button class="btn btn-primary btn-sm" type="submit">Add</button>
          </form>
          <p class="inline-add-note">Planned for today. Use Capture (C) for things to sort later.</p>
          ${empty ? html`<div class="empty-inline">Nothing planned or due today. Plan a Task from a Topic, or add one above.</div>` : ''}
          ${group('overdue', 'Overdue', b.overdue)}
          ${group('planned', 'Planned for today', b.planned, hasDeadlineOnly ? html`<p class="group-note">Includes Tasks due later today.</p>` : '')}
          ${group('earlier', 'Planned earlier, not done', b.earlier)}
        </section>
        <div class="side-stack">
          <section aria-labelledby="today-rem-h">
            <div class="section-h"><h2 id="today-rem-h">Reminders</h2><span class="count">${rems.length}</span></div>
            ${rems.length ? html`<ul class="rem-list">${rems.map(C.remRow)}</ul>` : html`<p class="empty-inline">No reminders today.</p>`}
          </section>
          <section aria-labelledby="today-rr-h">
            <div class="section-h"><h2 id="today-rr-h">Review requested</h2><a href="#/prs?tab=review">All pull requests</a></div>
            ${Q.source('github') === 'disconnected' ? html`<p class="empty-inline">GitHub is not connected. <a href="#/settings?sec=connections">Connect it in Settings</a>.</p>`
              : reviews.length ? html`<div class="pr-list">${reviews.map((id) => C.prRow(id, { href: '#/prs/' + id + '?tab=review' }))}</div>${Q.source('github') !== 'ok' ? html`<p class="help" style="margin-top:8px">${C.cacheLine('github')}</p>` : ''}`
              : html`<p class="empty-inline">No reviews waiting for you.</p>`}
          </section>
        </div>
      </div>
      <section class="active-topics" aria-labelledby="today-at-h">
        <div class="section-h"><h2 id="today-at-h">Active Topics <span class="count">${active.length}</span></h2><a href="#/topics">All Topics</a></div>
        ${active.length ? html`<div class="tile-grid">${active.map((t) => html`<a class="tile" href="#/topic/${t.id}" style="${C.tc(t)}" data-fk="tile-${t.id}">
          <span class="tile-top"><span class="tile-name">${t.name}</span>${C.status(t.statusId)}</span>
          <span class="tile-label">Next step</span>
          <span class="tile-next">${t.nextStep || 'No next step yet'}</span>
          <span class="tile-foot">${C.meter(t.id)}</span>
        </a>`)}</div>` : html`<div class="empty"><h3>No active Topics</h3><p>A Topic shows here when its status is in the Active category.</p><a class="btn" href="#/topics">Open Topics</a></div>`}
      </section>
    </div>`;
  };

  /* ------------------------------------------------------------------ Inbox */
  V.inbox = () => {
    const items = Q.inbox();
    const keyOf = (i) => i.kind + ':' + i.id;
    if (!items.find((i) => keyOf(i) === ui.inboxSel)) ui.inboxSel = items.length ? keyOf(items[0]) : null;
    ui.inboxChecked = ui.inboxChecked.filter((k) => items.find((i) => keyOf(i) === k));
    const sel = items.find((i) => keyOf(i) === ui.inboxSel);
    const nChecked = ui.inboxChecked.length;
    const anyTask = ui.inboxChecked.some((k) => k.indexOf('task:') === 0);

    const head = html`<div class="page-head"><div><h1>Inbox</h1><p class="page-sub">Captured items wait here until they get a Topic, a plan, or you keep them standalone.</p></div>
      <div class="head-actions"><button class="btn" type="button" data-action="open-capture" id="inbox-capture">${icon('plus')}Capture</button></div></div>`;
    if (!items.length) {
      return html`<div class="page">${head}<div class="empty">${icon('inbox', 'icon-lg')}<h3>Inbox is clear</h3><p>Press C anywhere to capture a Task or Note without deciding where it goes.</p><button class="btn btn-primary" type="button" data-action="open-capture">Capture something</button></div></div>`;
    }
    return html`<div class="page">${head}
      <div class="inbox-layout">
        <div>
          <div class="bulkbar" role="group" aria-label="Bulk actions">
            <label><input class="sel-box" type="checkbox" data-change="inbox-all" id="inbox-all" ${nChecked === items.length ? 'checked' : ''}>Select all</label>
            <span class="faint" aria-live="polite">${nChecked ? nChecked + ' selected' : items.length + ' to sort'}</span>
            <span class="spacer"></span>
            <label class="sr-only" for="bulk-topic">Move selected to Topic</label>
            <select class="select select-sm" id="bulk-topic" style="width:auto" ${nChecked ? '' : 'disabled'} data-change="bulk-move"><option value="">Move to Topic</option>${topicOptions('')}</select>
            <button class="btn btn-sm" type="button" data-action="bulk-today" ${nChecked && anyTask ? '' : 'disabled'}>Plan for today</button>
            <button class="btn btn-sm btn-ghost btn-danger" type="button" data-action="bulk-trash" ${nChecked ? '' : 'disabled'}>Move to Trash</button>
          </div>
          <ul class="inbox-list" aria-label="Inbox items">
            ${items.map((i) => { const k = keyOf(i); return html`<li class="inbox-row ${k === ui.inboxSel ? 'is-selected' : ''}">
              <input class="sel-box" type="checkbox" data-change="inbox-check" data-key="${k}" data-fk="ic-${k}" ${ui.inboxChecked.indexOf(k) >= 0 ? 'checked' : ''} aria-label="Select ${i.title}">
              <button class="inbox-open" type="button" data-action="inbox-select" data-key="${k}" data-fk="io-${k}" aria-pressed="${k === ui.inboxSel}">
                <span class="type-ico ${i.kind === 'note' ? 'is-note' : ''}">${icon(i.kind === 'note' ? 'note' : 'check', 'icon-sm')}</span>
                <span class="io-text"><span class="io-title">${i.title}</span><span class="io-sub">${i.kind === 'note' ? 'Note' : 'Task'}, captured ${T.fmtWhen(i.at)}</span></span>
              </button>
            </li>`; })}
          </ul>
        </div>
        ${sel ? triage(sel) : ''}
      </div>
    </div>`;
  };

  function triage(sel) {
    const isNote = sel.kind === 'note';
    return html`<aside class="triage" aria-labelledby="triage-h">
      <span class="triage-type">${icon(isNote ? 'note' : 'check', 'icon-sm')}${isNote ? 'Note' : 'Task'}, captured ${T.fmtWhen(sel.at)}</span>
      <h2 id="triage-h">${sel.title}</h2>
      ${isNote ? html`<div class="triage-preview">${clip(Q.noteText(sel.item), 220) || 'Empty note'}</div>` : ''}
      <form class="triage-actions" data-form="triage-move" data-key="${sel.kind + ':' + sel.id}">
        <label class="field"><span>Topic</span><select class="select" name="topic" id="triage-topic" required><option value="">Choose a Topic</option>${topicOptions(ui.triageTopic || '')}</select></label>
        <button class="btn btn-primary" type="submit">Move to Topic</button>
      </form>
      <div class="triage-actions">
        ${isNote ? '' : html`<button class="btn" type="button" data-action="triage-today" data-id="${sel.id}">${icon('calendar')}Plan for today without a Topic</button>`}
        <button class="btn" type="button" data-action="triage-keep" data-key="${sel.kind + ':' + sel.id}">Keep as standalone ${isNote ? 'Note' : 'Task'}</button>
        <button class="btn btn-ghost" type="button" data-action="triage-open" data-key="${sel.kind + ':' + sel.id}">Open ${isNote ? 'in editor' : 'details'}</button>
        <button class="btn btn-ghost btn-danger" type="button" data-action="triage-trash" data-key="${sel.kind + ':' + sel.id}">Move to Trash</button>
      </div>
      <p class="triage-hint">Up and Down arrows move through the list while it has focus.</p>
    </aside>`;
  }

  /* ----------------------------------------------------------------- Topics */
  V.topics = (r) => {
    const s = T.state;
    const qv = r.q.get('view');
    if ((qv === 'list' || qv === 'board') && qv !== s.prefs.topicsView) { s.prefs.topicsView = qv; T.persist(); }
    const view = s.prefs.topicsView;
    const topics = s.topics.slice().sort((a, b) => catRank(a) - catRank(b));
    const head = html`<div class="page-head">
      <div><h1>Topics</h1><p class="page-sub">${topics.length} Topic${topics.length === 1 ? '' : 's'}. ${view === 'board' ? 'Drag a card to change its status, or use the menu on the card.' : 'Sorted by status category.'}</p></div>
      <div class="head-actions">
        <div class="seg" role="group" aria-label="Layout">
          <button type="button" data-action="topics-view" data-v="list" aria-pressed="${view === 'list'}" id="tv-list">${icon('list', 'icon-sm')}List</button>
          <button type="button" data-action="topics-view" data-v="board" aria-pressed="${view === 'board'}" id="tv-board">${icon('board', 'icon-sm')}Board</button>
        </div>
        <button class="btn btn-primary" type="button" data-action="new-topic" id="new-topic">${icon('plus')}New Topic</button>
      </div></div>`;
    if (!topics.length) {
      return html`<div class="page">${head}<div class="empty">${icon('topics', 'icon-lg')}<h3>No Topics yet</h3><p>A Topic holds the Tasks, Notes, tickets and pull requests for one piece of work.</p><button class="btn btn-primary" type="button" data-action="new-topic">Create your first Topic</button></div></div>`;
    }
    if (view === 'board') return html`<div class="page" style="max-width:none">${head}${board()}</div>`;
    return html`<div class="page">${head}
      <table class="ttable">
        <thead><tr><th scope="col">Topic</th><th scope="col">Status</th><th scope="col">Next step</th><th scope="col">Progress</th></tr></thead>
        <tbody>${topics.map((t) => html`<tr style="${C.tc(t)}">
          <td class="t-title-cell"><div class="t-title"><span class="thread"></span><a href="#/topic/${t.id}" data-fk="tl-${t.id}">${t.name}</a></div></td>
          <td class="t-status">${C.status(t.statusId)}</td>
          <td class="t-next">${t.nextStep || html`<span class="faint">No next step</span>`}</td>
          <td class="t-progress">${C.meter(t.id)}</td>
        </tr>`)}</tbody>
      </table>
    </div>`;
  };

  function board() {
    const s = T.state;
    return html`<p class="board-hint">${icon('swap', 'icon-sm')}Swipe sideways for every status. Use the menu on a card to move it.</p>
      <div class="board-scroller" tabindex="0" role="region" aria-label="Topic board, scrolls sideways">
        <div class="board">${s.statuses.map((st) => {
          const ts = s.topics.filter((t) => t.statusId === st.id);
          return html`<section class="lane cat-${st.category}" aria-labelledby="lane-${st.id}">
            <div class="lane-head">${glyph(st.category)}<h2 id="lane-${st.id}">${st.name}</h2><span class="count">${ts.length}</span></div>
            <div class="lane-body" data-drop="${st.id}">
              ${ts.length ? ts.map((t) => html`<article class="tcard" draggable="true" data-drag="${t.id}" style="${C.tc(t)}">
                <a class="tcard-title" href="#/topic/${t.id}" data-fk="tc-${t.id}">${t.name}</a>
                ${t.nextStep ? html`<p class="tcard-next">${t.nextStep}</p>` : ''}
                <div class="tcard-foot">${C.meter(t.id)}
                  <select class="select select-sm" data-change="topic-status" data-id="${t.id}" data-fk="ts-${t.id}" aria-label="Status of ${t.name}">${statusOptions(t.statusId)}</select>
                </div>
              </article>`) : html`<p class="lane-empty">No Topics. Drop one here.</p>`}
            </div>
          </section>`;
        })}</div>
      </div>`;
  }

  /* ---------------------------------------------------------- Topic Details */
  V.topic = (r) => {
    const t = Q.topic(r.id);
    if (!t) return html`<div class="page"><div class="empty">${icon('topics', 'icon-lg')}<h3>Topic not found</h3><p>It may be in Trash.</p><div class="knot-actions"><a class="btn" href="#/trash">Open Trash</a><a class="btn btn-ghost" href="#/topics">All Topics</a></div></div></div>`;
    const base = '#/topic/' + t.id;
    const pj = r.q.get('jira');
    const pp = r.q.get('pr');
    const hasPanel = T.wide() && !!(pj || pp);
    const cat = Q.cat(t);
    const tasks = Q.topicTasks(t.id).slice().sort(openFirst);
    const openTasks = tasks.filter((x) => !x.done);
    const doneTasks = tasks.filter((x) => x.done);
    const notes = Q.topicNotes(t.id).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
    const jira = Q.topicJira(t.id);
    const prs = Q.topicPrs(t.id);
    const taskIds = tasks.map((x) => x.id);
    const noteIds = notes.map((x) => x.id);
    const rems = T.state.reminders.filter((x) => !x.dismissed && Q.targetOf(x) && (
      (x.target.type === 'topic' && x.target.id === t.id) || (x.target.type === 'task' && taskIds.indexOf(x.target.id) >= 0) || (x.target.type === 'note' && noteIds.indexOf(x.target.id) >= 0)
    )).sort((a, b) => a.at.localeCompare(b.at));
    const sec = (k) => ui.sections[k + '-' + t.id] !== false;
    const linkHref = (kind, key) => T.wide() ? base + '?' + kind + '=' + encodeURIComponent(key) : '#/' + (kind === 'jira' ? 'jira' : 'prs') + '/' + key + '?from=topic/' + t.id;
    const others = (ids) => ids.filter((id) => id !== t.id).map((id) => Q.topic(id).name);

    const knot = (k, title, count, body) => html`<details class="knot" data-sec="${k}-${t.id}" ${sec(k) ? 'open' : ''}>
      <summary data-fk="sum-${k}"><h2>${title}</h2><span class="count">${count}</span>${icon('chevDown', 'chev')}</summary>
      <div class="knot-body">${body}</div></details>`;

    const continueBlock = ui.topicEdit === t.id
      ? html`<form class="continue" data-form="continue" data-id="${t.id}" aria-label="Edit where you left off">
          <div class="continue-edit">
            <label class="field"><span>Where I left off</span><textarea class="textarea" name="leftOff" id="ce-left">${t.leftOff}</textarea></label>
            <label class="field"><span>Next step</span><input class="input" name="nextStep" id="ce-next" value="${t.nextStep}"></label>
          </div>
          <div class="continue-foot"><button class="btn btn-ghost btn-sm" type="button" data-action="cancel-continue">Cancel</button><button class="btn btn-sm" type="submit">Save</button></div>
        </form>`
      : html`<section class="continue" aria-label="Continue where you left off">
          <div class="continue-col"><h2>${icon('back', 'icon-sm')}Where I left off</h2><p>${t.leftOff || html`<span class="faint">Nothing noted yet.</span>`}</p></div>
          <div class="continue-col is-next"><h2>${icon('arrowRight', 'icon-sm')}Next step</h2><p>${t.nextStep || html`<span class="faint">No next step yet.</span>`}</p></div>
          <div class="continue-foot"><button class="btn btn-ghost btn-sm" type="button" data-action="edit-continue" data-id="${t.id}" id="edit-continue" aria-label="Edit where I left off and next step">Edit</button></div>
        </section>`;

    const tasksBody = html`
      <form class="inline-add" data-form="topic-add" data-topic="${t.id}">
        ${icon('plus')}
        <label class="sr-only" for="topic-add-title">New Task in ${t.name}</label>
        <input type="text" id="topic-add-title" name="title" placeholder="Add a Task" autocomplete="off" required>
        <label class="sr-only" for="topic-add-plan">Plan</label>
        <select class="select" id="topic-add-plan" name="plan"><option value="">Not planned</option><option value="today">Plan for today</option></select>
        <button class="btn btn-primary btn-sm" type="submit">Add Task</button>
      </form>
      ${openTasks.length ? html`<div>${openTasks.map((x) => C.taskRow(x, { hideTopic: true, showPlanned: true, side: (y) => y.planned === T.today() ? '' : html`<button class="btn btn-ghost btn-sm" type="button" data-action="plan-today" data-id="${y.id}" data-fk="pt-${y.id}">Plan today</button>` }))}</div>` : html`<p class="empty-inline">No open Tasks.</p>`}
      ${doneTasks.length ? html`<div><button class="btn btn-ghost btn-sm" type="button" data-action="toggle-done" data-id="${t.id}" aria-expanded="${!!ui.sections['done-' + t.id]}" id="toggle-done">${icon(ui.sections['done-' + t.id] ? 'chevDown' : 'chevRight', 'icon-sm')}${doneTasks.length} completed</button>
        ${ui.sections['done-' + t.id] ? html`<div>${doneTasks.map((x) => C.taskRow(x, { hideTopic: true }))}</div>` : ''}</div>` : ''}`;

    const notesBody = html`
      ${notes.length ? html`<div class="note-cards">${notes.map((n) => { const o = others(n.topicIds); return html`<a class="note-card" href="#/notes/${n.id}" data-fk="nc-${n.id}">
        <h3>${n.title || 'Untitled note'}</h3><p>${clip(Q.noteText(n), 140)}</p>
        <span class="note-card-meta"><span>Edited ${T.fmtWhen(n.updatedAt)}</span>${o.length ? html`<span>Also in ${o.join(', ')}</span>` : ''}</span></a>`; })}</div>` : html`<p class="empty-inline">No Notes yet.</p>`}
      <div class="knot-actions"><button class="btn" type="button" data-action="new-note" data-topic="${t.id}">${icon('plus', 'icon-sm')}New Note in this Topic</button></div>`;

    const jiraBody = html`${C.sourceBanner('jira')}
      ${jira.length ? html`<div class="linked-list">${jira.map((k) => { const j = Q.jira(k); const cached = Q.jiraCached(k) && j; const o = others(Q.jiraTopics(k)); return html`<div class="linked-row ${pj === k && hasPanel ? 'is-current' : ''}">
        <a class="linked-open" href="${linkHref('jira', k)}" data-fk="lo-${k}" ${pj === k && hasPanel ? raw('aria-current="true"') : ''}>
          <span class="lo-l1"><span class="lo-key">${k}</span>${cached ? C.jiraStatus(j) : ''}</span>
          <span class="lo-title">${cached ? j.summary : html`<span class="faint">Details not on this device yet</span>`}</span>
          <span class="lo-sub">${cached ? html`<span>${j.type}</span><span>${Q.person(j.assignee).name}</span>` : ''}${o.length ? html`<span>Also in ${o.join(', ')}</span>` : ''}</span>
        </a>
        <button class="btn btn-ghost btn-sm btn-icon" type="button" data-action="unlink" data-source="jira" data-key="${k}" data-topic="${t.id}" data-fk="un-${k}" aria-label="Unlink ${k} from ${t.name}" title="Unlink. The ticket itself is not changed.">${icon('unlink', 'icon-sm')}</button>
      </div>`; })}</div>` : html`<p class="empty-inline">No Jira tickets linked.</p>`}
      <div class="knot-actions"><button class="btn" type="button" data-action="pick-items" data-source="jira" data-topic="${t.id}">${icon('link', 'icon-sm')}Link Jira ticket</button></div>`;

    const prBody = html`${C.sourceBanner('github')}
      ${prs.length ? html`<div class="linked-list">${prs.map((id) => { const pr = Q.pr(id); const cached = Q.prCached(id) && pr; const o = others(Q.prTopics(id)); return html`<div class="linked-row ${pp === id && hasPanel ? 'is-current' : ''}">
        <a class="linked-open" href="${linkHref('pr', id)}" data-fk="lo-${id}" ${pp === id && hasPanel ? raw('aria-current="true"') : ''}>
          <span class="lo-l1">${cached ? C.prState(pr) : ''}<span class="lo-key">#${pr ? pr.number : id}</span></span>
          <span class="lo-title">${cached ? pr.title : html`<span class="faint">Details not on this device yet</span>`}</span>
          <span class="lo-sub">${cached ? html`<span>${pr.repo}</span>${C.ciInd(pr)}${C.reviewInd(pr)}` : ''}${o.length ? html`<span>Also in ${o.join(', ')}</span>` : ''}</span>
        </a>
        <button class="btn btn-ghost btn-sm btn-icon" type="button" data-action="unlink" data-source="github" data-key="${id}" data-topic="${t.id}" data-fk="un-${id}" aria-label="Unlink pull request ${pr ? pr.number : id} from ${t.name}" title="Unlink. The pull request itself is not changed.">${icon('unlink', 'icon-sm')}</button>
      </div>`; })}</div>` : html`<p class="empty-inline">No pull requests linked.</p>`}
      <div class="knot-actions"><button class="btn" type="button" data-action="pick-items" data-source="github" data-topic="${t.id}">${icon('link', 'icon-sm')}Link pull request</button></div>`;

    const remBody = html`${rems.length ? html`<ul class="rem-list">${rems.map(C.remRow)}</ul>` : html`<p class="empty-inline">No reminders for this Topic, its Tasks or its Notes.</p>`}
      <div class="knot-actions"><button class="btn" type="button" data-action="new-rem" data-type="topic" data-id="${t.id}">${icon('bell', 'icon-sm')}Add reminder</button></div>`;

    return html`<div class="topic-layout ${hasPanel ? 'has-panel' : ''}" style="${C.tc(t)}">
      <div class="topic-page">
        <nav class="crumbs" aria-label="Breadcrumb"><a href="#/topics">Topics</a><span aria-hidden="true">/</span><span aria-current="page">${t.name}</span></nav>
        <header class="topic-head">
          <div class="topic-title-row"><span class="thread" aria-hidden="true"></span><h1 class="topic-title">${t.name}</h1></div>
          <div class="topic-meta">
            <label class="sr-only" for="topic-status">Status</label>
            <select id="topic-status" class="status-select cat-${cat}" data-change="topic-status" data-id="${t.id}">${statusOptions(t.statusId)}</select>
            ${C.meter(t.id)}
            <button class="btn btn-ghost btn-sm" type="button" data-action="edit-topic" data-id="${t.id}" id="edit-topic">Edit name and goal</button>
          </div>
          <p class="topic-goal ${t.goal ? '' : 'is-empty'}">${t.goal || 'No goal yet.'}</p>
        </header>
        ${continueBlock}
        <div class="spine">
          ${knot('tasks', 'Tasks', openTasks.length + ' open', tasksBody)}
          ${knot('notes', 'Notes', notes.length, notesBody)}
          ${knot('jira', 'Jira tickets', jira.length, jiraBody)}
          ${knot('prs', 'Pull requests', prs.length, prBody)}
          ${knot('rems', 'Reminders', rems.length, remBody)}
        </div>
        <footer class="topic-foot"><span>Created ${T.fmtDay(t.createdAt.slice(0, 10))}</span><button class="btn btn-ghost btn-danger btn-sm" type="button" data-action="trash-topic" data-id="${t.id}">${icon('trash', 'icon-sm')}Move Topic to Trash</button></footer>
      </div>
      ${hasPanel ? html`<aside class="side-panel" aria-label="Linked item details">${pj ? C.jiraDetail(pj, { close: base, relHref: (k) => base + '?jira=' + k }) : C.prDetail(pp, { close: base })}</aside>` : ''}
    </div>`;
  };

  /* ------------------------------------------------------------------ Notes */
  const SYNC = {
    saving: ['refresh', 'Saving on this device', 'is-busy'],
    local: ['device', 'Saved on this device', ''],
    offline: ['device', 'Saved on this device. Offline', 'is-warn'],
    syncing: ['refresh', 'Syncing', 'is-busy'],
    synced: ['cloudCheck', 'Synced', 'is-ok'],
    'local-failed': ['alert', 'Not saved. Device storage failed', 'is-danger'],
    'sync-failed': ['alert', 'Saved on this device. Sync failed', 'is-danger'],
    conflict: ['alert', 'Conflict. Both versions kept', 'is-warn']
  };
  function syncKey(id) {
    const ns = Q.sync(id);
    if (ns.conflict) return 'conflict';
    if (ns.state === 'local' && !T.state.sim.online) return 'offline';
    return ns.state;
  }
  T.syncKey = syncKey;

  function saveState(n) {
    const m = SYNC[syncKey(n.id)];
    return html`<span class="save-state ${m[2]}" id="save-state" role="status" aria-live="polite">${icon(m[0])}<span>${m[1]}</span></span>`;
  }

  function noteIndex(curId) {
    const s = T.state;
    const f = ui.noteFilter;
    let notes = s.notes.slice().sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
    if (f === 'inbox') notes = notes.filter((n) => n.inbox);
    else if (f === 'standalone') notes = notes.filter((n) => !n.inbox && !n.topicIds.length);
    else if (f !== 'all') notes = notes.filter((n) => n.topicIds.indexOf(f) >= 0);
    return html`<div class="note-index-head"><h1>Notes</h1><button class="btn btn-primary btn-sm" type="button" data-action="new-note" id="new-note">${icon('plus', 'icon-sm')}New note</button></div>
      <div class="note-filter"><label class="sr-only" for="note-filter">Show notes</label>
        <select class="select select-sm" id="note-filter" data-change="note-filter">
          <option value="all" ${f === 'all' ? 'selected' : ''}>All notes</option>
          <option value="inbox" ${f === 'inbox' ? 'selected' : ''}>In Inbox</option>
          <option value="standalone" ${f === 'standalone' ? 'selected' : ''}>Standalone</option>
          ${s.topics.map((t) => html`<option value="${t.id}" ${f === t.id ? 'selected' : ''}>${t.name}</option>`)}
        </select></div>
      ${notes.length ? html`<nav class="note-list" aria-label="Notes">${notes.map((n) => {
        const k = syncKey(n.id);
        const flag = k === 'synced' ? '' : k === 'saving' || k === 'syncing' ? '' : html`<span class="sync-dot ${/failed/.test(k) ? 'is-danger' : (k === 'conflict' || k === 'offline') ? 'is-warn' : ''}">${icon(SYNC[k][0])}<span>${k === 'conflict' ? 'Conflict' : k === 'local-failed' ? 'Not saved' : k === 'sync-failed' ? 'Sync failed' : 'On this device'}</span></span>`;
        return html`<a class="note-item" href="#/notes/${n.id}" ${n.id === curId ? raw('aria-current="page"') : ''} data-fk="ni-${n.id}">
          <span class="ni-title">${n.title || 'Untitled note'}</span>
          <span class="ni-excerpt">${clip(Q.noteText(n), 120) || 'Empty'}</span>
          <span class="ni-meta">${n.topicIds.length ? html`<span class="ni-threads" aria-label="${n.topicIds.map((id) => Q.topic(id) ? Q.topic(id).name : '').join(', ')}">${n.topicIds.map((id) => html`<span class="thread" style="${C.tc(Q.topic(id))}"></span>`)}</span>` : html`<span>${n.inbox ? 'Inbox' : 'Standalone'}</span>`}<span>${T.fmtWhen(n.updatedAt)}</span>${flag}</span>
        </a>`; })}</nav>` : html`<p class="empty-inline" style="padding:6px">No notes here.</p>`}`;
  }

  function paperTopics(n) {
    return html`${n.topicIds.map((id) => { const t = Q.topic(id); if (!t) return ''; return html`<span class="paper-chip"><span class="thread" style="${C.tc(t)}"></span><a href="#/topic/${id}">${t.name}</a><button type="button" data-action="note-unlink-topic" data-id="${n.id}" data-topic="${id}" data-fk="nut-${id}" aria-label="Remove from ${t.name}">${icon('x', 'icon-sm')}</button></span>`; })}
      ${!n.topicIds.length ? html`<span>${n.inbox ? 'In Inbox' : 'Standalone note'}</span>` : ''}
      <button class="paper-add-topic" type="button" data-action="pick-note-topics" data-id="${n.id}" id="note-add-topic">${icon('link', 'icon-sm')}${n.topicIds.length ? 'Topics' : 'Add to Topic'}</button>`;
  }

  function paperBanners(n) {
    const k = syncKey(n.id);
    if (k === 'local-failed') return html`<div class="banner banner-danger">${icon('alert')}<div class="banner-body"><strong>Not saved on this device.</strong><p>Browser storage refused the save. Your text is still on screen. Keep this tab open and retry.</p></div><div class="banner-actions"><span class="sim-tag">Simulated</span><button class="btn btn-sm" type="button" data-action="retry-save" data-id="${n.id}">Retry save</button></div></div>`;
    if (k === 'sync-failed') return html`<div class="banner banner-danger">${icon('cloud')}<div class="banner-body"><strong>Saved on this device. Sync failed.</strong><p>The server returned an error. Your edits are safe here and sync again when you retry.</p></div><div class="banner-actions"><span class="sim-tag">Simulated</span><button class="btn btn-sm" type="button" data-action="retry-sync" data-id="${n.id}">Retry sync</button></div></div>`;
    return '';
  }

  function compare(n) {
    const c = Q.sync(n.id).conflict;
    return html`<section class="compare" aria-labelledby="cmp-h">
      <div class="compare-head"><h2 id="cmp-h">This note changed on two devices</h2><p>Both versions are kept until you choose. The editor below shows this device's version and stays editable.</p></div>
      <div class="compare-cols">
        <div class="version"><div class="version-head">${icon('device', 'icon-sm')}<b>This device</b><span>Edited ${T.fmtWhen(n.updatedAt)}</span></div>
          <div class="version-body"><h3>${n.title || 'Untitled note'}</h3>${raw(T.sanitize(n.html))}</div>
          <div class="version-foot"><button class="btn btn-sm" type="button" data-action="resolve" data-id="${n.id}" data-keep="local">Keep this version</button></div></div>
        <div class="version"><div class="version-head">${icon('phone', 'icon-sm')}<b>${c.device}</b><span>Edited ${T.fmtWhen(c.at)}</span></div>
          <div class="version-body"><h3>${c.title}</h3>${raw(T.sanitize(c.html))}</div>
          <div class="version-foot"><button class="btn btn-sm" type="button" data-action="resolve" data-id="${n.id}" data-keep="remote">Keep this version</button></div></div>
      </div>
      <div class="compare-actions"><button class="btn btn-primary btn-sm" type="button" data-action="resolve" data-id="${n.id}" data-keep="both">Keep both as separate notes</button><span class="help">The version you do not keep moves to Trash, so it can still be restored.</span></div>
    </section>`;
  }

  function linkForm() {
    return html`<form class="link-form" data-form="note-link"><label class="sr-only" for="link-url">Link address</label><input class="input" id="link-url" name="url" type="url" placeholder="https://" required autocomplete="off"><button class="btn btn-sm" type="submit">Add link</button><button class="btn btn-ghost btn-sm" type="button" data-action="link-cancel">Cancel</button></form>`;
  }

  function paperFoot(n) {
    return html`<span>Edited ${T.fmtWhen(n.updatedAt)}</span><span>Cmd or Ctrl + B, I. Cmd or Ctrl + Enter checks a checklist item.</span>`;
  }

  const TOOLS = [
    ['p', 'Normal text', null, 'Text'], ['h', 'Heading', null, 'H'], 'sep',
    ['bold', 'Bold', 'bold'], ['italic', 'Italic', 'italic'], 'sep',
    ['ul', 'Bulleted list', 'listUl'], ['ol', 'Numbered list', 'listOl'], ['check', 'Checklist', 'checklist'], 'sep',
    ['link', 'Link', 'link']
  ];

  V.notes = (r, host) => {
    const n = r.id ? Q.note(r.id) : null;
    const ns = n ? Q.sync(n.id) : null;
    const key = n ? 'note:' + n.id + (ns.conflict ? ':c' : '') : 'index';
    const layout = host.querySelector('.notes-layout');
    if (n && layout && layout.dataset.key === key && host.querySelector('#note-body')) {
      document.getElementById('note-index').innerHTML = T.flat(noteIndex(n.id));
      document.getElementById('save-state').outerHTML = T.flat(saveState(n));
      document.getElementById('paper-banners').innerHTML = T.flat(paperBanners(n));
      document.getElementById('paper-topics').innerHTML = T.flat(paperTopics(n));
      document.getElementById('paper-foot').innerHTML = T.flat(paperFoot(n));
      document.getElementById('link-slot').innerHTML = ui.linkForm ? T.flat(linkForm()) : '';
      const ti = document.getElementById('note-title');
      if (ti && document.activeElement !== ti && ti.value !== n.title) ti.value = n.title;
      return null;
    }
    if (r.id && !n) {
      return html`<div class="notes-layout" data-key="missing"><div class="note-index" id="note-index">${noteIndex(null)}</div><div class="note-stage"><div class="empty">${icon('note', 'icon-lg')}<h3>Note not found</h3><p>It may be in Trash.</p><a class="btn" href="#/trash">Open Trash</a></div></div></div>`;
    }
    return html`<div class="notes-layout ${n ? 'has-note' : ''}" data-key="${key}">
      <div class="note-index" id="note-index">${noteIndex(n ? n.id : null)}</div>
      <div class="note-stage">
        ${n ? html`<a class="routed-back mobile-only" href="#/notes">${icon('back', 'icon-sm')}All notes</a>
        <article class="paper" aria-label="Note editor">
          <div class="paper-bar">
            <div class="toolbar" role="toolbar" aria-label="Formatting" id="note-toolbar">
              ${TOOLS.map((tl, i) => tl === 'sep' ? html`<span class="tool-sep" aria-hidden="true"></span>` : html`<button class="tool ${tl[3] ? 'tool-text' : ''}" type="button" data-tool="${tl[0]}" tabindex="${i === 0 ? 0 : -1}" aria-label="${tl[1]}" title="${tl[1]}" ${/^(bold|italic|ul|ol|check)$/.test(tl[0]) ? raw('aria-pressed="false"') : ''}>${tl[3] ? tl[3] : icon(tl[2])}</button>`)}
            </div>
            ${saveState(n)}
            <div class="paper-more"><button class="tool" type="button" data-action="note-trash" data-id="${n.id}" aria-label="Move note to Trash" title="Move note to Trash">${icon('trash')}</button></div>
          </div>
          <div id="link-slot">${ui.linkForm ? linkForm() : ''}</div>
          <div class="paper-banners" id="paper-banners">${paperBanners(n)}</div>
          ${ns.conflict ? compare(n) : ''}
          <div class="paper-inner">
            <label class="sr-only" for="note-title">Note title</label>
            <input class="paper-title" id="note-title" data-input="note-title" data-id="${n.id}" value="${n.title}" placeholder="Untitled note" autocomplete="off">
            <div class="paper-topics" id="paper-topics">${paperTopics(n)}</div>
            <div class="paper-body" id="note-body" contenteditable="true" role="textbox" aria-multiline="true" aria-label="Note text" data-note="${n.id}" data-placeholder="Start writing">${raw(T.sanitize(n.html))}</div>
          </div>
          <footer class="paper-foot" id="paper-foot">${paperFoot(n)}</footer>
        </article>` : html`<div class="panel-empty">${icon('note', 'icon-lg')}<p>Choose a note, or start a new one.</p><button class="btn" type="button" data-action="new-note">New note</button></div>`}
      </div>
    </div>`;
  };

  /* ------------------------------------------------------------- Jira, PRs */
  function sourceHead(source) {
    const st = Q.source(source);
    const name = source === 'jira' ? 'Jira' : 'GitHub';
    const label = { ok: 'Connected as demo-dev (demo)', offline: 'Offline', expired: 'Access expired', ratelimited: 'Rate limited', disconnected: 'Not connected' }[st];
    const cls = st === 'ok' ? '' : st === 'disconnected' ? 'is-off' : 'is-warn';
    return html`<span class="source-state ${cls}"><span class="dot" aria-hidden="true"></span><span>${name}: ${label}. Read-only.</span></span>`;
  }

  function splitView(r, source) {
    const isJira = source === 'jira';
    const key = r.id;
    const from = r.q.get('from');
    const tab = r.q.get('tab') || (isJira ? 'assigned' : (key && Q.reviewPrs().indexOf(key) >= 0 ? 'review' : 'mine'));
    const route = isJira ? 'jira' : 'prs';
    const tabQ = tab === (isJira ? 'assigned' : 'mine') ? '' : '?tab=' + tab;
    const detail = (k, opts) => isJira ? C.jiraDetail(k, opts) : C.prDetail(k, opts);
    if (key && !T.wide()) {
      const ft = from && from.indexOf('topic/') === 0 ? Q.topic(from.slice(6)) : null;
      const back = from ? '#/' + from : '#/' + route + tabQ;
      return html`<div class="page" style="max-width:760px">
        <a class="routed-back" href="${back}" id="routed-back">${icon('back', 'icon-sm')}Back to ${ft ? ft.name : (isJira ? 'Jira' : 'Pull requests')}</a>
        ${detail(key, { relHref: (k) => '#/jira/' + k + (from ? '?from=' + from : '') })}
      </div>`;
    }
    const hrefFor = (k) => '#/' + route + '/' + k + tabQ;
    const canFetch = T.canFetch(source);
    let tabs, list;
    if (isJira) {
      const assigned = Q.assignedJira();
      tabs = html`<div class="seg" role="group" aria-label="Lists">
        <a href="#/jira" ${tab === 'assigned' ? raw('aria-current="page"') : ''}>Assigned to me <span class="seg-count">${assigned.length}</span></a>
        <a href="#/jira?tab=search" ${tab === 'search' ? raw('aria-current="page"') : ''}>${icon('search', 'icon-sm')}Search</a></div>`;
      if (tab === 'search') list = jiraSearch(hrefFor, key, canFetch);
      else if (assigned.length) list = html`<div class="xlist">${assigned.map((k) => C.jiraRow(k, { href: hrefFor(k), current: k === key }))}</div>`;
      else list = listEmpty(source);
    } else {
      const mine = Q.myPrs();
      const rev = Q.reviewPrs();
      const ids = tab === 'review' ? rev : mine;
      tabs = html`<div class="seg" role="group" aria-label="Lists">
        <a href="#/prs" ${tab === 'mine' ? raw('aria-current="page"') : ''}>My pull requests <span class="seg-count">${mine.length}</span></a>
        <a href="#/prs?tab=review" ${tab === 'review' ? raw('aria-current="page"') : ''}>Review requested <span class="seg-count">${rev.length}</span></a></div>`;
      list = ids.length ? html`<div class="pr-list">${ids.map((id) => C.prRow(id, { href: hrefFor(id), current: id === key }))}</div>` : listEmpty(source, tab);
    }
    return html`<div class="split ${T.wide() ? 'has-detail' : ''}">
      <div class="split-list"><div class="page">
        <div class="page-head"><div><h1>${isJira ? 'Jira' : 'Pull requests'}</h1><p class="page-sub">${sourceHead(source)}</p></div>
          <div class="head-actions">${C.cacheLine(source)}<button class="btn" type="button" data-action="refresh" data-source="${source}" id="refresh-${source}" ${canFetch ? '' : 'disabled'}>${icon('refresh', 'icon-sm')}Refresh</button></div></div>
        ${C.sourceBanner(source)}
        <div class="tabs-row">${tabs}</div>
        ${list}
      </div></div>
      ${T.wide() ? html`<aside class="side-panel" aria-label="${isJira ? 'Ticket' : 'Pull request'} details">${key ? detail(key, { close: '#/' + route + tabQ, relHref: (k) => '#/jira/' + k + tabQ }) : html`<div class="panel-empty">${icon(isJira ? 'ticket' : 'pr', 'icon-lg')}<p>Select ${isJira ? 'a ticket' : 'a pull request'} to see it here.<br>Opened items are saved on this device for offline reading.</p></div>`}</aside>` : ''}
    </div>`;
  }

  function listEmpty(source, tab) {
    const st = Q.source(source);
    if (st === 'disconnected') return html`<div class="empty">${icon('unlink', 'icon-lg')}<h3>${source === 'jira' ? 'Jira' : 'GitHub'} is not connected</h3><p>Connect it to see ${source === 'jira' ? 'tickets assigned to you' : 'your pull requests and review requests'}. Thread only reads.</p><a class="btn btn-primary" href="#/settings?sec=connections">Connect in Settings</a></div>`;
    if (!T.state.cache.lists[source === 'jira' ? 'jira' : 'github']) {
      if (st === 'ok') return html`<div class="empty" role="status">${icon('refresh', 'icon-lg')}<h3>Loading for the first time</h3><p>Nothing is saved on this device yet.</p></div>`;
      return html`<div class="empty">${icon('offline', 'icon-lg')}<h3>Nothing saved on this device</h3><p>This list has never loaded here, so there is no offline copy. It loads when the connection works again.</p></div>`;
    }
    return html`<div class="empty-inline">${tab === 'review' ? 'No reviews requested from you.' : 'Nothing here right now.'}</div>`;
  }

  function jiraSearch(hrefFor, key, canFetch) {
    const sq = ui.jiraSearch;
    let results = '';
    if (sq.status === 'loading') results = html`<p class="empty-inline" role="status">Searching Jira…</p>`;
    else if (sq.status === 'done') {
      results = sq.results.length
        ? html`${sq.local ? html`<p class="help" style="margin-bottom:8px">Search needs a working connection. These matches are tickets saved on this device.</p>` : ''}<div class="xlist">${sq.results.map((k) => C.jiraRow(k, { href: hrefFor(k), current: k === key }))}</div>`
        : html`<div class="empty-inline">No tickets match “${sq.q}”.${sq.local ? ' Only tickets saved on this device were searched.' : ''}</div>`;
    } else {
      results = html`<p class="empty-inline">${canFetch ? 'Searches Jira directly. Tickets you open are saved on this device.' : 'Search needs a working Jira connection. It falls back to tickets saved on this device.'}</p>`;
    }
    return html`<form class="jira-search" data-form="jira-search" role="search">
        <label class="sr-only" for="jira-q">Search Jira</label>
        <input class="input" id="jira-q" name="q" value="${sq.q}" placeholder="Key, words or label" autocomplete="off">
        <button class="btn btn-primary" type="submit">Search</button>
      </form>
      <div class="sample-queries"><span>Try</span>${['QR', 'NOVA-210', 'receipt'].map((q) => html`<button type="button" data-action="jira-sample" data-q="${q}">${q}</button>`)}</div>
      ${results}`;
  }

  V.jira = (r) => splitView(r, 'jira');
  V.prs = (r) => splitView(r, 'github');

  /* --------------------------------------------------------------- Settings */
  V.settings = () => {
    const s = T.state;
    const counts = {};
    s.statuses.forEach((st) => { counts[st.category] = (counts[st.category] || 0) + 1; });
    const conn = (source, name, ico, scopes) => {
      const on = s.conn[source];
      return html`<div class="conn-row"><span class="conn-ico">${icon(ico, 'icon-lg')}</span>
        <div><b>${name}</b><p>${on ? html`Connected as demo-dev (demo). ${scopes}` : 'Not connected. Links on your Topics are kept.'}</p></div>
        ${on ? html`<button class="btn btn-danger" type="button" data-action="disconnect" data-source="${source}" id="conn-${source}">Disconnect</button>`
          : html`<button class="btn btn-primary" type="button" data-action="connect" data-source="${source}" id="conn-${source}">Connect (demo)</button>`}
      </div>`;
    };
    const pref = (k, label, opts) => html`<label class="field"><span>${label}</span><select class="select" data-change="pref" data-key="${k}" id="pref-${k}">${opts.map((o) => html`<option value="${o[0]}" ${s.prefs[k] === o[0] ? 'selected' : ''}>${o[1]}</option>`)}</select></label>`;
    return html`<div class="page">
      <div class="page-head"><div><h1>Settings</h1><p class="page-sub">Connections, workspace preferences and Topic statuses.</p></div></div>
      <div class="settings-layout">
        <nav class="settings-nav" aria-label="Settings sections">
          ${[['connections', 'Connections'], ['workspace', 'Workspace'], ['statuses', 'Topic statuses'], ['data', 'Demo data']].map((x) => html`<a href="#/settings?sec=${x[0]}" data-action="settings-jump" data-sec="${x[0]}">${x[1]}</a>`)}
        </nav>
        <div>
          <section class="set-section" id="set-connections" aria-labelledby="sh-conn">
            <h2 id="sh-conn">Connections</h2>
            <p class="page-sub">Read-only in v1. Thread never changes a ticket or a pull request. The demo connects without any sign-in.</p>
            <div class="conn-list">
              ${conn('jira', 'Jira', 'ticket', 'Reads assigned tickets, search and comments.')}
              ${conn('github', 'GitHub', 'pr', 'Reads your pull requests and review requests.')}
            </div>
          </section>
          <section class="set-section" id="set-workspace" aria-labelledby="sh-ws">
            <h2 id="sh-ws">Workspace</h2>
            <p class="page-sub">Saved for this workspace.</p>
            <div class="pref-grid">
              ${pref('startPage', 'Start page', [['today', 'Today'], ['inbox', 'Inbox'], ['topics', 'Topics']])}
              ${pref('topicsView', 'Topics layout', [['list', 'List'], ['board', 'Board']])}
              ${pref('timeFormat', 'Time format', [['24h', '24 hour (14:10)'], ['12h', '12 hour (2:10 PM)']])}
              ${pref('captureDefault', 'Quick capture creates', [['task', 'A Task'], ['note', 'A Note']])}
              <div class="field"><span>Theme</span><p class="muted" style="font-size:var(--fs-14);padding-top:6px">Dark. Thread v1 has no light theme.</p></div>
            </div>
          </section>
          <section class="set-section" id="set-statuses" aria-labelledby="sh-st">
            <h2 id="sh-st">Topic statuses</h2>
            <p class="page-sub">Name and order are yours. The category decides behavior across Today, Topics and the Board.</p>
            <ol class="status-list">${s.statuses.map((st, i) => {
              const used = Q.usage(st.id);
              const lastOfCat = counts[st.category] === 1;
              return html`<li class="status-item cat-${st.category}">
                <span class="glyph-wrap">${glyph(st.category)}</span>
                <input class="input" value="${st.name}" aria-label="Status name" data-change="status-name" data-id="${st.id}" id="sn-${st.id}" maxlength="24">
                <select class="select cat-field" data-change="status-cat" data-id="${st.id}" id="sc-${st.id}" aria-label="Category of ${st.name}" ${lastOfCat ? raw('title="This is the only status in its category"') : ''}>
                  ${['planned', 'active', 'paused', 'done'].map((c) => html`<option value="${c}" ${c === st.category ? 'selected' : ''} ${lastOfCat && c !== st.category ? 'disabled' : ''}>${T.CAT_LABEL[c]}</option>`)}
                </select>
                <span class="usage">${used} Topic${used === 1 ? '' : 's'}</span>
                <span class="row-actions">
                  <button class="btn btn-ghost btn-sm btn-icon" type="button" data-action="status-move" data-id="${st.id}" data-dir="-1" id="su-${st.id}" aria-label="Move ${st.name} up" ${i === 0 ? 'disabled' : ''}>${icon('up', 'icon-sm')}</button>
                  <button class="btn btn-ghost btn-sm btn-icon" type="button" data-action="status-move" data-id="${st.id}" data-dir="1" id="sd-${st.id}" aria-label="Move ${st.name} down" ${i === s.statuses.length - 1 ? 'disabled' : ''}>${icon('down', 'icon-sm')}</button>
                  <button class="btn btn-ghost btn-sm btn-icon btn-danger" type="button" data-action="status-delete" data-id="${st.id}" id="sx-${st.id}" aria-label="${lastOfCat ? 'Cannot delete ' + st.name + ', it is the only ' + T.CAT_LABEL[st.category] + ' status' : 'Delete ' + st.name}" ${lastOfCat ? 'disabled' : ''}>${icon('trash', 'icon-sm')}</button>
                </span>
              </li>`; })}</ol>
            <form class="inline-add" data-form="status-add" style="max-width:560px">
              ${icon('plus')}
              <label class="sr-only" for="status-new">New status name</label>
              <input type="text" id="status-new" name="name" placeholder="New status, for example Blocked" maxlength="24" required>
              <label class="sr-only" for="status-new-cat">Category</label>
              <select class="select" id="status-new-cat" name="cat">${['planned', 'active', 'paused', 'done'].map((c) => html`<option value="${c}">${T.CAT_LABEL[c]}</option>`)}</select>
              <button class="btn btn-sm" type="submit">Add status</button>
            </form>
            <ul class="cat-legend">${['planned', 'active', 'paused', 'done'].map((c) => html`<li class="cat-${c}">${glyph(c)}<span><b>${T.CAT_LABEL[c]}.</b> ${T.CAT_HELP[c]}</span></li>`)}</ul>
          </section>
          <section class="set-section" id="set-data" aria-labelledby="sh-data">
            <h2 id="sh-data">Demo data</h2>
            <p class="page-sub">All data is synthetic and stays in this browser.</p>
            <div class="knot-actions">
              <button class="btn" type="button" data-action="reset-demo" data-kind="example">${icon('refresh', 'icon-sm')}Reset example workspace</button>
              <button class="btn" type="button" data-action="reset-demo" data-kind="empty">Switch to empty workspace</button>
              <a class="btn btn-ghost" href="#/welcome">Show first-run screen</a>
            </div>
          </section>
        </div>
      </div>
    </div>`;
  };

  /* ------------------------------------------------------------------ Trash */
  V.trash = () => {
    const items = T.state.trash.slice().sort((a, b) => b.deletedAt.localeCompare(a.deletedAt));
    const where = (e) => {
      if (e.type === 'topic') return 'Topic with ' + e.tasks.length + ' Task' + (e.tasks.length === 1 ? '' : 's') + ' and ' + e.notes.length + ' Note' + (e.notes.length === 1 ? '' : 's');
      const tid = e.type === 'task' ? e.item.topicId : (e.item.topicIds || [])[0];
      const t = tid ? Q.topic(tid) : null;
      const kind = e.type === 'task' ? 'Task' : 'Note';
      if (tid && !t) return kind + '. Its Topic is gone, so it restores as standalone';
      return kind + (t ? '. Was in ' + t.name : e.item.inbox ? '. Was in Inbox' : '. Standalone');
    };
    return html`<div class="page" style="max-width:860px">
      <div class="page-head"><div><h1>Trash</h1><p class="page-sub">Deleted items stay here until you empty Trash.</p></div>
        <div class="head-actions"><button class="btn btn-danger" type="button" data-action="empty-trash" id="empty-trash" ${items.length ? '' : 'disabled'}>Empty Trash</button></div></div>
      <div class="note-box">${icon('lock', 'icon-sm')}<span>Jira tickets and pull requests are never deleted by Thread. Unlinking or deleting a Topic removes only the link. The ticket or pull request is unchanged.</span></div>
      ${items.length ? html`<ul class="trash-list">${items.map((e) => { const title = e.type === 'topic' ? e.item.name : (e.item.title || 'Untitled note'); return html`<li class="trash-row">
        <span class="type-ico">${icon({ note: 'note', task: 'check', topic: 'topics' }[e.type], 'icon-sm')}</span>
        <div><b>${title}</b><p>${where(e)}. Deleted ${T.fmtWhen(e.deletedAt)}.</p></div>
        <div class="knot-actions"><button class="btn btn-sm" type="button" data-action="restore" data-id="${e.id}" data-fk="rs-${e.id}">${icon('restore', 'icon-sm')}Restore</button><button class="btn btn-sm btn-ghost btn-danger" type="button" data-action="purge" data-id="${e.id}" data-fk="pg-${e.id}" aria-label="Delete ${title} forever">Delete forever</button></div>
      </li>`; })}</ul>` : html`<div class="empty">${icon('trash', 'icon-lg')}<h3>Trash is empty</h3><p>Deleted Topics, Tasks and Notes appear here and can be restored.</p><a class="btn" href="#/notes">Go to Notes</a></div>`}
    </div>`;
  };
})();
