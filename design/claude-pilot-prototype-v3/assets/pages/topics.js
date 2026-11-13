/* Topics: List (grouped by status order) and Board (lanes per status). The last view is remembered. */
(function () {
  const T = window.T;
  const S = T.sel;
  const html = T.html;
  const I = T.I;

  const urlView = T.params.get('view');
  if (urlView === 'list' || urlView === 'board') { T.state.prefs.topicsView = urlView; T.persist(); }

  function moveSelect(t) {
    return html`<span class="bcard__move"><label class="vh" for="mv-${t.id}">Move ${t.name} to</label>
      <select class="input input--sm input--auto" id="mv-${t.id}" data-change="topic-move" data-id="${t.id}" data-k="mv-${t.id}">
        ${T.state.statuses.map((s) => html`<option value="${s.id}" ${s.id === t.statusId ? 'selected' : ''}>${s.name}</option>`)}</select></span>`;
  }

  function list(topics) {
    return T.state.statuses.map((st) => {
      const ts = topics.filter((t) => t.statusId === st.id);
      if (!ts.length) return '';
      return html`<section class="tl-group" aria-labelledby="tl-${st.id}">
        <h2 class="tl-head" id="tl-${st.id}">${T.statusLabel(st)} <span class="n">${ts.length}</span></h2>
        <ul class="tl-list">${ts.map((t) => html`<li><a class="trow2" href="topic.html?id=${t.id}" style="${T.topicVar(t)}">
          ${T.hex(t, 'md')}
          <span class="trow2__name">${t.name}</span>
          <span class="trow2__next">${t.nextStep ? 'Next: ' + t.nextStep : S.cat(t) === 'done' ? 'Finished' : 'No next step yet'}</span>
          <span class="trow2__side">${T.progress(t)}</span></a></li>`)}</ul>
      </section>`;
    });
  }

  function board(topics) {
    return html`<div class="board" aria-label="Board. Drag a card by its handle, or use its Move to menu.">${T.state.statuses.map((st) => {
      const ts = topics.filter((t) => t.statusId === st.id);
      return html`<section class="lane" data-lane="${st.id}" aria-labelledby="ln-${st.id}">
        <h2 class="lane__head" id="ln-${st.id}">${T.statusLabel(st)} <span class="n">${ts.length}</span></h2>
        ${ts.length ? html`<ul class="lane__list">${ts.map((t) => html`<li class="bcard" draggable="true" data-topic="${t.id}" style="${T.topicVar(t)}">
          <div class="bcard__top">${T.hex(t, 'sm')}<a class="bcard__name" href="topic.html?id=${t.id}">${t.name}</a>
            <span class="bcard__grip" title="Drag to move" aria-hidden="true">${I('grip', 18)}</span></div>
          ${t.nextStep ? html`<p class="bcard__next">Next: ${t.nextStep}</p>` : ''}
          <div class="bcard__foot">${T.progress(t)}${moveSelect(t)}</div></li>`)}</ul>`
          : html`<p class="lane__empty">No Topics. Drop one here.</p>`}
      </section>`;
    })}</div>`;
  }

  T.definePage({
    render(main) {
      const view = T.state.prefs.topicsView === 'board' ? 'board' : 'list';
      const topics = S.topicsInOrder();
      const active = topics.filter((t) => S.cat(t) === 'active').length;
      main.innerHTML = html`
        <header class="ph">
          <div><h1 class="ph__title">Topics</h1>
            <p class="ph__sub">${topics.length ? topics.length + ' Topics, ' + active + ' active' : 'A Topic holds the tasks, notes, tickets and PRs for one piece of work.'}</p></div>
          <div class="ph__acts">
            <div class="seg" role="radiogroup" aria-label="Topics view">
              <button type="button" role="radio" aria-checked="${view === 'list'}" data-act="topics-view" data-v="list" data-k="v-list">${I('list', 16)} List</button>
              <button type="button" role="radio" aria-checked="${view === 'board'}" data-act="topics-view" data-v="board" data-k="v-board">${I('topics', 16)} Board</button>
            </div>
            <button class="btn btn--primary" type="button" data-act="focus-capture" data-type="topic">New topic</button>
          </div>
        </header>
        ${topics.length ? (view === 'board' ? board(topics) : list(topics))
          : T.empty({ icon: 'topics', title: 'No Topics yet', text: 'Create one with New topic. It starts as Planned.' })}`.s;
    }
  });

  T.on('topics-view', (el) => {
    T.state.prefs.topicsView = el.dataset.v;
    T.setParam('view', el.dataset.v);
    T.commit('local');
  });
  function move(id, statusId) {
    const t = S.topic(id);
    if (!t || t.statusId === statusId) return;
    const prev = t.statusId;
    T.act.setTopicStatus(id, statusId);
    T.toast('Moved ' + t.name + ' to ' + S.status(statusId).name, { undo: () => T.act.setTopicStatus(id, prev) });
  }
  T.on('topic-move', (el) => move(el.dataset.id, el.value));

  /* HTML5 drag and drop between lanes */
  let dragId = null;
  document.addEventListener('dragstart', (e) => {
    const card = e.target.closest && e.target.closest('.bcard');
    if (!card) return;
    dragId = card.dataset.topic;
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', dragId);
    card.classList.add('is-dragging');
  });
  document.addEventListener('dragend', () => {
    document.querySelectorAll('.is-dragging, .is-over').forEach((el) => el.classList.remove('is-dragging', 'is-over'));
    dragId = null;
  });
  document.addEventListener('dragover', (e) => {
    const lane = e.target.closest && e.target.closest('.lane');
    if (!lane || !dragId) return;
    e.preventDefault();
    document.querySelectorAll('.lane.is-over').forEach((l) => { if (l !== lane) l.classList.remove('is-over'); });
    lane.classList.add('is-over');
  });
  document.addEventListener('drop', (e) => {
    const lane = e.target.closest && e.target.closest('.lane');
    if (!lane || !dragId) return;
    e.preventDefault();
    const id = dragId;
    dragId = null;
    move(id, lane.dataset.lane);
  });
})();
