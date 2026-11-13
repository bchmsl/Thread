/* Today: date + personal checklist lead, reminders and review requests on the rail, Active Topics below. */
(function () {
  const T = window.T;
  const S = T.sel;
  const D = T.D;
  const html = T.html;
  const I = T.I;
  const page = { adding: T.params.get('add') === '1' };

  const KIND_ICON = { task: 'task', note: 'notes', topic: 'topics' };

  function group(key, title, tasks, hint) {
    if (!tasks.length && key !== 'today') return '';
    return html`<section class="tgroup tgroup--${key}" aria-labelledby="g-${key}">
      <h2 class="tgroup__head" id="g-${key}">${key === 'overdue' ? I('alert', 17) : ''}${title} <span class="n">${tasks.filter((t) => !t.done).length}</span>
        ${hint ? html`<span class="tgroup__hint">${hint}</span>` : ''}</h2>
      ${tasks.length ? html`<ul class="tlist">${tasks.map((t) => T.taskRow(t))}</ul>`
        : html`<p class="muted" style="padding:12px 4px">Nothing planned for today yet.</p>`}
    </section>`;
  }

  function addForm() {
    const topics = S.topicsInOrder().filter((t) => S.cat(t) !== 'done');
    return html`<form class="addrow" data-submit="today-add-submit" autocomplete="off">
      <label class="vh" for="today-new">New task for today</label>
      <input class="input" id="today-new" name="title" placeholder="What do you need to do today?" data-k="today-new" required>
      <label class="vh" for="today-topic">Topic (optional)</label>
      <select class="input" id="today-topic" name="topic" data-k="today-topic">
        <option value="">No Topic</option>${topics.map((t) => html`<option value="${t.id}">${t.name}</option>`)}</select>
      <button class="btn btn--line" type="submit">Add</button>
      <button class="btn btn--text" type="button" data-act="today-add-cancel">Done</button>
      <p class="addrow__note">Planned for today. Without a Topic it also waits in Inbox until you file it.</p>
    </form>`;
  }

  function reminders() {
    const rems = S.todayReminders();
    const next = rems.find((x) => !x.past);
    const upcoming = T.state.reminders.filter((r) => r.at > T.state.demo.now && S.targetOf(r)).length;
    return html`<section aria-labelledby="rem-h">
      <div class="railsec__head"><h2 class="h2" id="rem-h">Reminders <span class="n">${rems.length}</span></h2></div>
      ${rems.length ? html`<ul class="rems">${rems.map(({ r, past }) => {
        const tg = S.targetOf(r);
        return html`<li><button class="rem ${past ? 'is-past' : ''}" type="button" data-act="open-rem" data-id="${r.id}">
          <span class="rem__time">${D.time(r.at)}</span>
          <span class="rem__text">${r.text}${past ? html`<span class="vh"> (past)</span>` : ''}${next && next.r.id === r.id ? html`<span class="rem__next">Next</span>` : ''}</span>
          <span class="rem__for">${I(KIND_ICON[tg.kind], 14)}<span>${tg.kind === 'topic' ? tg.label : tg.kind === 'task' ? 'Task: ' + tg.label : 'Note: ' + tg.label}</span></span>
        </button></li>`;
      })}</ul>` : html`<p class="muted">No reminders today. Add one from a Task, Note or Topic.</p>`}
      <div style="margin-top:14px">${T.demoBar('Clock ' + D.time(T.state.demo.now), html`
        <button class="btn btn--line btn--sm" type="button" data-act="today-advance" ${upcoming ? '' : 'disabled'}>${I('bell', 15)} Advance to next reminder</button>
        <button class="btn btn--text btn--sm" type="button" data-act="today-clock-reset">Reset</button>`)}</div>
    </section>`;
  }

  function reviews() {
    let body;
    if (!T.state.connections.github) body = html`<p class="muted">GitHub is not connected. <a class="lnk" href="settings.html#connections">Connect in Settings</a></p>`;
    else if (!T.ext.hasCache('github')) body = html`<p class="muted">Offline, and no saved copy on this device yet.</p>`;
    else {
      const prs = S.reviewRequested();
      body = prs.length ? html`<div>${prs.map((p) => html`<a class="rv" href="prs.html?tab=review&id=${p.id}">
        <p class="rv__title">${p.title} <span class="rv__num">#${p.number}</span></p>
        <p class="rv__meta"><span>${T.ext.who(p.author)}</span><span>${p.repo.split('/')[1]}</span></p>
        <p class="rv__meta">${T.ext.ci(p)}${T.ext.review(p)}</p></a>`)}</div>`
        : html`<p class="muted">No review requests.</p>`;
    }
    return html`<section aria-labelledby="rv-h">
      <div class="railsec__head"><h2 class="h2" id="rv-h">Review requested <span class="n">${S.reviewRequested().length}</span></h2>
        <a class="btn btn--text btn--sm" href="prs.html?tab=review">All</a></div>${body}</section>`;
  }

  function activeTopics() {
    const ts = S.activeTopics();
    const names = T.state.statuses.filter((s) => s.category === 'active').map((s) => s.name).join(' and ');
    return html`<section class="atopics" aria-labelledby="at-h">
      <div class="atopics__head"><h2 class="h2" id="at-h">Active Topics <span class="n">${ts.length}</span></h2>
        <span class="muted" style="font-size:13px">${names ? 'Statuses in the Active category: ' + names : 'No status uses the Active category'}</span></div>
      ${ts.length ? html`<ul class="agrid">${ts.map((t) => html`<li><a class="acard" href="topic.html?id=${t.id}" style="${T.topicVar(t)}">
        <span class="acard__top">${T.hex(t, 'md')}<span class="acard__name">${t.name}</span></span>
        ${T.statusLabel(S.statusOf(t))}
        <span class="acard__next">${t.nextStep ? html`<b>Next:</b> ${t.nextStep}` : html`<span class="muted">No next step yet.</span>`}</span>
        <span class="acard__foot">${T.progress(t)}</span></a></li>`)}</ul>`
        : T.empty({ icon: 'topics', title: 'No active Topics', text: 'Topics show here when their status is in the Active category.', action: html`<a class="btn btn--line" href="topics.html">Open Topics</a>` })}
    </section>`;
  }

  T.definePage({
    render(main) {
      const g = S.todayGroups();
      const all = g.overdue.concat(g.today, g.earlier);
      const open = all.filter((t) => !t.done).length;
      const doneToday = all.filter((t) => t.done).length;
      main.innerHTML = html`
        <header class="ph">
          <div><h1 class="ph__title">${D.longDate(D.today())}</h1>
            <p class="ph__sub">${open ? open + (open === 1 ? ' open task' : ' open tasks') : 'No open tasks'}${doneToday ? ', ' + doneToday + ' done today' : ''}</p></div>
          <div class="ph__acts"><button class="btn btn--primary" type="button" data-act="today-add" data-k="today-add">Add task for today</button></div>
        </header>
        ${page.adding ? addForm() : ''}
        <div class="today">
          <div class="today__main">
            ${group('overdue', 'Overdue', g.overdue, 'Deadline passed')}
            ${group('today', 'Today', g.today, 'Planned or due today')}
            ${group('earlier', 'Earlier planned', g.earlier, 'Planned before today, still open')}
          </div>
          <aside class="today__rail" aria-label="Reminders and reviews">${reminders()}${reviews()}</aside>
        </div>
        ${activeTopics()}`.s;
    }
  });

  T.on('today-add', () => {
    page.adding = true;
    T.rerender('local');
    const el = document.getElementById('today-new');
    if (el) el.focus();
  });
  T.on('today-add-cancel', () => { page.adding = false; T.rerender('local'); document.querySelector('[data-k="today-add"]').focus(); });
  T.on('today-add-submit', (form) => {
    const title = form.title.value.trim();
    if (!title) return;
    const topicId = form.topic.value || null;
    const t = T.act.createTask({ title, topicId, plannedDay: D.today() });
    T.toast(topicId ? 'Added to Today and ' + S.topic(topicId).name : 'Added to Today and Inbox', { undo: () => T.act.discardTask(t.id) });
    const el = document.getElementById('today-new');
    if (el) { el.value = ''; el.focus(); }
  });
  T.on('open-rem', (el) => { const r = S.reminder(el.dataset.id); if (r) T.openTarget(r.target); });
  T.on('today-advance', () => {
    const r = T.act.advanceToNextReminder();
    if (r) T.toast('Demo clock moved to ' + D.when(r.at, true) + '. The reminder is showing at the top.');
  });
  T.on('today-clock-reset', () => { T.act.resetClock(); T.toast('Demo clock reset to Fri 13 Nov 13:05'); });
})();
