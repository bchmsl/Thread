/* Trash: deleted Topics, notes, tasks and reminders with their old Topic context. Restore puts them back.
   A Topic carries its tasks and reminders with it, and remembers its links so Restore can bring them back. */
(function () {
  const T = window.T;
  const S = T.sel;
  const D = T.D;
  const html = T.html;
  const I = T.I;
  const page = { id: T.params.get('id') };
  const KIND = { topic: ['topics', 'Topic'], note: ['notes', 'Note'], task: ['task', 'Task'], reminder: ['bell', 'Reminder'] };

  const titleOf = (e) => (e.kind === 'topic' ? e.item.name : e.kind === 'reminder' ? e.item.text : e.item.title) || 'Untitled note';
  const topicsOf = (e) => e.context.topicIds.map(S.topic).filter(Boolean);
  const topicInTrash = (tid) => T.state.trash.some((x) => x.kind === 'topic' && x.item.id === tid);
  const count = (n, one, many) => n + ' ' + (n === 1 ? one : many);
  function bundlePhrase(e) {
    const parts = [e.bundle.tasks.length ? count(e.bundle.tasks.length, 'task', 'tasks') : '', e.bundle.reminders.length ? count(e.bundle.reminders.length, 'reminder', 'reminders') : ''].filter(Boolean);
    return parts.length ? 'With ' + parts.join(' and ') : 'No tasks or reminders';
  }
  function shownId() {
    if (page.id && T.state.trash.some((e) => e.id === page.id)) return page.id;
    if (window.innerWidth < 1000) return null;
    return T.state.trash[0] ? T.state.trash[0].id : null;
  }
  function restoreLabel(e) {
    const tps = topicsOf(e);
    if (e.kind === 'topic') return 'Restore Topic';
    if (e.kind === 'reminder') return 'Restore reminder';
    if (!tps.length) return e.context.topicIds.length ? 'Restore as standalone' : 'Restore';
    return tps.length === 1 ? 'Restore to ' + tps[0].name : 'Restore to its ' + tps.length + ' Topics';
  }

  /* What a deleted Topic took with it, and the links Restore brings back. */
  function topicBody(e) {
    const notes = e.context.noteIds.map(S.note).filter(Boolean);
    const ext = e.context.linkKeys.map((k) => {
      const [kind, key] = [k.slice(0, k.indexOf(':')), k.slice(k.indexOf(':') + 1)];
      const it = kind === 'jira' ? S.jira(key) : S.pr(key);
      return it ? (kind === 'jira' ? it.key + ' ' + it.title : it.title + ' #' + it.number) : null;
    }).filter(Boolean);
    const rows = (items) => html`<ul class="tprev__list">${items.map((x) => html`<li>${x}</li>`)}</ul>`;
    return html`<div class="tprev__body">
      ${e.item.goal ? html`<p class="tprev__goal">${e.item.goal}</p>` : ''}
      <h3 class="tprev__h">Went to Trash with it</h3>
      ${e.bundle.tasks.length || e.bundle.reminders.length
        ? rows(e.bundle.tasks.map((t) => (t.done ? 'Done: ' : 'Task: ') + t.title).concat(e.bundle.reminders.map((r) => 'Reminder ' + D.when(r.at, true) + ': ' + r.text)))
        : html`<p class="muted">No tasks or reminders.</p>`}
      <h3 class="tprev__h">Links that come back on restore</h3>
      ${notes.length || ext.length ? rows(notes.map((n) => 'Note: ' + (n.title || 'Untitled note')).concat(ext))
        : html`<p class="muted">No links to notes, tickets or pull requests.</p>`}
      <p class="callout">${I('lock', 15)} Notes stay in Notes. Jira tickets and pull requests are not changed.</p>
    </div>`;
  }

  function preview(e) {
    const tps = topicsOf(e);
    const missing = e.context.topicIds.filter((tid) => !S.topic(tid));
    const parked = missing.filter(topicInTrash).length;
    const lost = missing.length - parked;
    const [icon, label] = KIND[e.kind];
    let body = e.kind === 'topic' ? topicBody(e) : '';
    if (e.kind === 'note') body = html`<div class="tprev__body"><div class="editor editor--ro">${T.raw(T.sanitize(e.item.body))}</div></div>`;
    if (e.kind === 'task') {
      body = html`<dl class="fields tprev__body"><dt>Planned day</dt><dd>${e.item.plannedDay ? D.day(e.item.plannedDay) : 'None'}</dd>
        <dt>Deadline</dt><dd>${e.item.deadline ? D.when(e.item.deadline) : 'None'}</dd><dt>Done</dt><dd>${e.item.done ? 'Yes' : 'No'}</dd></dl>`;
    }
    if (e.kind === 'reminder') {
      const tg = S.targetOf(e.item);
      body = html`<dl class="fields tprev__body"><dt>Time</dt><dd>${D.when(e.item.at)}</dd>
        <dt>For</dt><dd>${tg ? (tg.kind === 'topic' ? 'Topic: ' : tg.kind === 'task' ? 'Task: ' : 'Note: ') + tg.label : 'An item that no longer exists'}</dd></dl>`;
    }
    return html`<article class="tprev" aria-labelledby="tp-title">
      <a class="back md-back" href="trash.html" data-act="trash-back">${I('chevLeft', 16)} Trash</a>
      <p class="tprev__kind">${I(icon, 15)} ${label}, deleted ${D.when(e.deletedAt, true)}</p>
      <h2 class="tprev__title" id="tp-title" tabindex="-1">${titleOf(e)}</h2>
      ${e.kind === 'topic' ? html`<p class="tprev__ctx">${T.hex(e.item, 'sm')}<span>${bundlePhrase(e)}</span></p>`
        : html`<p class="tprev__ctx">${tps.length ? html`<span>Was linked to</span>${tps.map((t) => T.topicTag(t, { plain: true }))}` : html`<span>Not linked to a Topic</span>`}</p>`}
      ${parked ? html`<p class="callout">${I('alert', 15)} ${parked === 1 ? 'Its Topic is' : parked + ' of its Topics are'} in Trash. Restore ${parked === 1 ? 'that Topic' : 'them'} first to keep the link.</p>` : ''}
      ${lost ? html`<p class="callout">${I('alert', 15)} ${lost === 1 ? 'One of its Topics no longer exists' : lost + ' of its Topics no longer exist'}, so that link cannot come back.</p>` : ''}
      ${e.context.reason ? html`<p class="callout">${I('compare', 15)} ${e.context.reason}</p>` : ''}
      ${body}
      <div class="tprev__acts">
        <button class="btn btn--primary" type="button" data-act="trash-restore" data-id="${e.id}" data-k="tr-restore">${restoreLabel(e)}</button>
        <button class="btn btn--text btn--danger-text" type="button" data-act="trash-purge" data-id="${e.id}">${I('trash', 16)} Delete forever</button>
      </div></article>`;
  }

  T.definePage({
    skip: (source) => source === 'sync' || source === 'editor',
    render(main) {
      const items = T.state.trash;
      const id = shownId();
      const cur = items.find((e) => e.id === id);
      main.innerHTML = html`
        <header class="ph"><div><h1 class="ph__title">Trash</h1>
          <p class="ph__sub">Deleted Topics, notes, tasks and reminders. Restore puts them back with their links.</p></div></header>
        ${items.length ? html`<div class="md ${page.id && cur ? 'has-open' : ''}">
          <div class="md__list"><ul class="md__scroll">${items.map((e) => {
            const [icon, label] = KIND[e.kind];
            const tps = topicsOf(e);
            return html`<li><a class="xi" href="trash.html?id=${e.id}" data-act="trash-open" data-id="${e.id}" data-k="tr-${e.id}" ${e.id === id ? T.raw('aria-current="true"') : ''}>
              <span class="xi__top">${I(icon, 14)}<span>${label}</span><span>Deleted ${D.when(e.deletedAt, true)}</span></span>
              <span class="xi__title">${titleOf(e)}</span>
              <span class="xi__meta">${e.kind === 'topic' ? html`${T.hex(e.item, 'xs')}<span>${bundlePhrase(e)}</span>`
                : e.kind === 'reminder' ? html`<span>Was set for ${D.when(e.item.at, true)}</span>`
                : tps.length ? tps.map((t) => T.topicTag(t, { plain: true }))
                : e.context.topicIds.some(topicInTrash) ? html`<span>Its Topic is in Trash</span>` : html`<span>No Topic</span>`}
                ${e.context.from ? html`<span>${e.context.from}</span>` : ''}</span></a></li>`;
          })}</ul>
          <p class="callout">${I('lock', 15)} Jira tickets and pull requests never go to Trash. Unlinking them only removes the link.</p></div>
          <div class="md__detail">${cur ? preview(cur) : ''}</div></div>`
        : T.empty({ icon: 'trash', title: 'Trash is empty', text: 'Deleted Topics, notes, tasks and reminders wait here until you restore them or delete them forever.' })}`.s;
    }
  });

  function select(id, focusTitle) {
    page.id = id;
    T.setParam('id', id);
    T.rerender('local');
    if (window.innerWidth < 1000) window.scrollTo(0, 0);
    if (focusTitle) { const h = document.getElementById('tp-title'); if (h) h.focus({ preventScroll: window.innerWidth >= 1000 }); }
  }
  T.on('trash-open', (el, e) => { e.preventDefault(); select(el.dataset.id, true); });
  T.on('trash-back', (el, e) => {
    e.preventDefault();
    const was = page.id;
    page.id = null;
    T.setParam('id', null);
    T.rerender('local');
    const row = document.querySelector('[data-k="tr-' + was + '"]');
    if (row) row.focus();
  });
  T.on('trash-restore', (el) => {
    const idx = T.state.trash.findIndex((x) => x.id === el.dataset.id);
    const r = T.act.restore(el.dataset.id);
    if (!r) return;
    const e = r.entry;
    const next = T.state.trash[Math.min(idx, T.state.trash.length - 1)];
    page.id = null;
    T.setParam('id', null);
    T.rerender('local');
    const open = e.kind === 'topic' ? () => { location.href = 'topic.html?id=' + e.item.id; }
      : e.kind === 'note' ? () => { location.href = 'notes.html?id=' + e.item.id; }
      : e.kind === 'task' ? () => T.openTask(e.item.id)
      : () => { const tg = S.targetOf(e.item); if (tg) T.openTarget(e.item.target); };
    T.toast('Restored: ' + titleOf(e), { open, openLabel: 'Open' });
    const focus = next && document.querySelector('[data-k="tr-' + next.id + '"]');
    if (focus) focus.focus(); else document.getElementById('main').focus();
  });
  T.on('trash-purge', async (el) => {
    const e = T.state.trash.find((x) => x.id === el.dataset.id);
    const body = e.kind === 'topic'
      ? '“' + titleOf(e) + '”' + (e.bundle.tasks.length || e.bundle.reminders.length ? ' and its ' + bundlePhrase(e).slice(5) : '') + ' cannot be restored after this. Notes, tickets and pull requests are not affected.'
      : '“' + titleOf(e) + '” cannot be restored after this.';
    const ok = await T.confirm({ title: 'Delete forever?', body, confirmLabel: 'Delete forever', danger: true });
    if (!ok) return;
    T.act.purge(e.id);
    page.id = null;
    T.setParam('id', null);
    T.rerender('local');
    T.toast('Deleted forever');
    document.getElementById('main').focus();
  });
  matchMedia('(min-width: 1000px)').addEventListener('change', () => { if (!page.id) T.rerender('local'); });
})();
