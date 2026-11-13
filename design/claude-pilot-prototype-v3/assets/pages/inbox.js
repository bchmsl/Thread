/* Inbox: select captures, then link them to a Topic or keep them standalone. Both clear the entry; nothing is deleted. */
(function () {
  const T = window.T;
  const S = T.sel;
  const D = T.D;
  const html = T.html;
  const I = T.I;
  const page = { sel: new Set() };
  const keyOf = (it) => it.kind + ':' + it.id;

  function selected() {
    return S.inboxItems().filter((it) => page.sel.has(keyOf(it))).map((it) => ({ kind: it.kind, id: it.id, title: it.title }));
  }

  T.definePage({
    render(main) {
      const items = S.inboxItems();
      const keys = new Set(items.map(keyOf));
      Array.from(page.sel).forEach((k) => { if (!keys.has(k)) page.sel.delete(k); });
      const n = page.sel.size;
      document.body.classList.toggle('has-selbar', n > 0);
      const all = items.length > 0 && n === items.length;
      main.innerHTML = html`
        <header class="ph">
          <div><h1 class="ph__title">Inbox</h1>
            <p class="ph__sub">${items.length ? 'Select captures, then link them to a Topic or keep them standalone.' : 'Captures without a Topic land here.'}</p></div>
        </header>
        ${items.length ? html`
          <div class="ibx-tools"><label><input class="check" type="checkbox" data-change="ibx-all" data-k="ibx-all" ${all ? 'checked' : ''}>
            ${all ? 'Clear selection' : 'Select all'} <span class="n">${items.length}</span></label></div>
          <ul class="ibx">${items.map((it) => html`<li class="irow ${page.sel.has(keyOf(it)) ? 'is-sel' : ''}">
            <input class="check" type="checkbox" data-change="ibx-sel" data-key="${keyOf(it)}" data-k="sel-${keyOf(it)}" ${page.sel.has(keyOf(it)) ? 'checked' : ''}
              aria-label="Select ${it.kind}: ${it.title}">
            <span class="irow__icon">${I(it.kind === 'task' ? 'task' : 'notes', 18)}</span>
            <div class="irow__body">
              <button class="irow__title" type="button" data-act="ibx-open" data-kind="${it.kind}" data-id="${it.id}">${it.title}</button>
              <span class="irow__kind">${it.kind === 'task' ? 'Task' : 'Note'}, captured ${D.ago(it.at)}</span>
            </div></li>`)}</ul>`
        : T.empty({ icon: 'inbox', title: 'Inbox is clear',
          text: 'Everything is filed. New tasks and notes captured without a Topic will wait here.',
          action: html`<button class="btn btn--line" type="button" data-act="focus-capture">${I('plus', 16)} Capture something</button>` })}
        ${n ? html`<div class="selbar" role="region" aria-label="Selection">
          <p class="selbar__count" aria-live="polite">${n} selected</p>
          <button class="btn btn--primary" type="button" data-act="ibx-link" data-k="ibx-link">Link to Topic</button>
          <button class="btn btn--line" type="button" data-act="ibx-keep">Keep standalone</button>
          <button class="btn btn--text" type="button" data-act="ibx-clear">Clear</button>
        </div>` : ''}`.s;
      const allBox = main.querySelector('[data-change="ibx-all"]');
      if (allBox) allBox.indeterminate = n > 0 && !all;
    }
  });

  T.on('ibx-sel', (el) => { if (el.checked) page.sel.add(el.dataset.key); else page.sel.delete(el.dataset.key); T.rerender('local'); });
  T.on('ibx-all', (el) => {
    if (el.checked) S.inboxItems().forEach((it) => page.sel.add(keyOf(it))); else page.sel.clear();
    T.rerender('local');
  });
  T.on('ibx-clear', () => { page.sel.clear(); T.rerender('local'); const a = document.querySelector('[data-k="ibx-all"]'); if (a) a.focus(); });
  T.on('ibx-open', (el) => {
    if (el.dataset.kind === 'task') T.openTask(el.dataset.id);
    else location.href = 'notes.html?id=' + el.dataset.id;
  });
  T.on('ibx-link', async () => {
    const items = selected();
    if (!items.length) return;
    const ids = await T.pickTopics({ title: 'Link ' + items.length + (items.length === 1 ? ' item' : ' items') + ' to a Topic', multiple: false,
      hint: 'Tasks join the Topic. Notes add it to their Topics. They leave Inbox; nothing is copied or deleted.', confirmLabel: 'Link to Topic' });
    if (!ids || !ids.length) return;
    const tp = S.topic(ids[0]);
    T.act.triage(items, tp.id);
    page.sel.clear();
    T.rerender('local');
    T.toast('Linked ' + count(items) + ' to ' + tp.name, { undo: () => T.act.untriage(items, tp.id), open: () => { location.href = 'topic.html?id=' + tp.id; }, openLabel: 'Open Topic' });
  });
  T.on('ibx-keep', () => {
    const items = selected();
    T.act.triage(items, null);
    page.sel.clear();
    T.rerender('local');
    T.toast('Kept ' + count(items) + ' standalone', { undo: () => T.act.untriage(items, null) });
  });
  function count(items) { return items.length + (items.length === 1 ? ' item' : ' items'); }
})();
