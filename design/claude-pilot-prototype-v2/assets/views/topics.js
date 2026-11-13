/* Topics: a ledger (List) or status lanes (Board). The last view is remembered. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const ui = T.ui;
  const esc = T.esc;
  const icon = T.icon;
  const store = T.store;

  let flash = T.param('flash');
  const askedView = T.param('view');
  if (askedView === 'list' || askedView === 'board') store.update((s) => { s.prefs.topicsView = askedView; }, { silent: true });

  function head() {
    const s = T.state();
    const view = s.prefs.topicsView;
    const active = S.activeTopics().length;
    const seg = '<div class="seg" role="group" aria-label="Layout">' +
      '<button type="button" aria-pressed="' + (view === 'list') + '" data-act="topicsView" data-value="list">' + icon('layoutList') + 'List</button>' +
      '<button type="button" aria-pressed="' + (view === 'board') + '" data-act="topicsView" data-value="board">' + icon('layoutBoard') + 'Board</button></div>';
    return T.pageHead('Topics', s.topics.length + ' topics, ' + active + ' active. Status names are yours; their category decides what shows in Today.',
      seg + ui.keycap('New topic', '', 'data-act="capture" data-kind="topic"'));
  }

  function list() {
    const s = T.state();
    const showDone = s.prefs.showDone;
    const topics = S.topicsInOrder(showDone);
    const doneN = s.topics.filter((t) => S.topicCat(t) === 'done').length;
    const toggle = '<label class="chipcheck"><input type="checkbox" data-change="topicsShowDone"' + (showDone ? ' checked' : '') + '><span>Show done topics (' + doneN + ')</span></label>';
    if (!s.topics.length) return ui.empty('No topics yet', 'A topic holds the tasks, notes, Jira issues and pull requests for one piece of work.', ui.keycap('New topic', '', 'data-act="capture" data-kind="topic"'));
    return '<div class="toolbar">' + toggle + '<span class="spacer"></span><span class="faint" style="font-size:12.5px">Sorted by status order from Settings</span></div>' +
      '<table class="ledger"><caption class="vh">Topics</caption><thead><tr><th scope="col">Topic</th><th scope="col">Status</th><th scope="col">Next step</th><th scope="col">Tasks</th></tr></thead><tbody>' +
      topics.map((t) => {
        const c = S.topicColor(t);
        const cat = S.topicCat(t);
        return '<tr class="' + (t.id === flash ? 'flash ' : '') + (cat === 'done' ? 'is-done' : '') + '" data-row-href="' + ui.topicHref(t.id) + '">' +
          '<td class="c-topic">' + ui.ttab(t) + (t.goal ? '<span class="goal">' + esc(t.goal) + '</span>' : '') + '</td>' +
          '<td class="c-status">' + ui.status(S.statusById(t.statusId)) + '</td>' +
          '<td class="c-next">' + (t.next ? esc(t.next) : '<span class="faint">No next step</span>') + '</td>' +
          '<td class="c-tasks">' + ui.pips(S.topicProgress(t.id), c) + '</td></tr>';
      }).join('') + '</tbody></table>';
  }

  function board() {
    const s = T.state();
    const lanes = s.statuses.map((st) => {
      const topics = s.topics.filter((t) => t.statusId === st.id).sort((a, b) => a.name.localeCompare(b.name));
      return '<section class="lane" data-cat="' + st.cat + '" data-status="' + st.id + '" aria-labelledby="lane-' + st.id + '">' +
        '<div class="lane-head"><h2 id="lane-' + st.id + '" style="font-size:inherit">' + ui.status(st) + '</h2><span class="count-num">' + topics.length + '</span></div>' +
        (topics.length ? topics.map((t) => {
          const c = S.topicColor(t);
          return '<article class="bcard' + (t.id === flash ? ' flash' : '') + '" draggable="true" data-topic="' + t.id + '" style="--c:' + c + '" aria-label="' + esc(t.name) + '">' +
            '<a class="ttab" style="--c:' + c + '" href="' + ui.topicHref(t.id) + '" draggable="false">' + esc(t.name) + '</a>' +
            '<div class="bcard-body">' +
              '<p class="bcard-next">' + (t.next ? esc(t.next) : '<span class="faint">No next step</span>') + '</p>' +
              ui.pips(S.topicProgress(t.id), c) +
              '<div class="bcard-foot"><label class="vh" for="mv-' + t.id + '">Status of ' + esc(t.name) + '</label>' +
                '<select class="select select-sm" id="mv-' + t.id + '" data-change="moveTopic" data-id="' + t.id + '">' +
                s.statuses.map((x) => '<option value="' + x.id + '"' + (x.id === t.statusId ? ' selected' : '') + '>Move to: ' + esc(x.name) + '</option>').join('') + '</select>' +
                '<span class="grip faint" aria-hidden="true">' + icon('grip') + '</span></div>' +
            '</div></article>';
        }).join('') : '<p class="lane-empty">No topics. Drag one here or use its Move menu.</p>') +
      '</section>';
    }).join('');
    return '<p class="board-hint">Scroll sideways for more lanes. Each card has a Move menu if dragging is awkward.</p>' +
      '<div class="board" data-keep-scroll="board" tabindex="0" aria-label="Topics board, ' + s.statuses.length + ' status lanes">' + lanes + '</div>';
  }

  function render() {
    const view = T.state().prefs.topicsView;
    return head() + (view === 'board' ? board() : list());
  }

  function move(topicId, statusId) {
    const t = S.topicById(topicId);
    if (!t || t.statusId === statusId) return;
    const before = S.topicCat(t);
    const st = S.statusById(statusId);
    store.update((s) => { s.topics.find((x) => x.id === topicId).statusId = statusId; });
    let note = '';
    if (before !== 'active' && st.cat === 'active') note = ' It now shows in Today and the Active tabs.';
    if (before === 'active' && st.cat !== 'active') note = ' It leaves Today and the Active tabs.';
    T.toast(t.name + ' moved to ' + st.name + '.' + note);
  }

  function mount(root) {
    if (flash) setTimeout(() => { flash = null; }, 1800);
    root.querySelectorAll('tr[data-row-href]').forEach((tr) => {
      tr.addEventListener('click', (e) => { if (!e.target.closest('a,button,select,input')) T.go(tr.dataset.rowHref); });
    });
    root.querySelectorAll('.bcard').forEach((card) => {
      card.addEventListener('dragstart', (e) => { e.dataTransfer.setData('text/plain', card.dataset.topic); e.dataTransfer.effectAllowed = 'move'; card.classList.add('dragging'); });
      card.addEventListener('dragend', () => card.classList.remove('dragging'));
    });
    root.querySelectorAll('.lane').forEach((lane) => {
      lane.addEventListener('dragover', (e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; lane.classList.add('drop-ok'); });
      lane.addEventListener('dragleave', (e) => { if (!lane.contains(e.relatedTarget)) lane.classList.remove('drop-ok'); });
      lane.addEventListener('drop', (e) => { e.preventDefault(); lane.classList.remove('drop-ok'); move(e.dataTransfer.getData('text/plain'), lane.dataset.status); });
    });
  }

  T.act.topicsView = (el) => store.update((s) => { s.prefs.topicsView = el.dataset.value; });
  T.act.topicsShowDone = (el) => store.update((s) => { s.prefs.showDone = el.checked; });
  T.act.moveTopic = (el) => move(el.dataset.id, el.value);

  T.views.topics = { title: 'Topics', render: render, mount: mount };
})();
