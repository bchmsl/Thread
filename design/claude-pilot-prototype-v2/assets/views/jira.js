/* Jira: assigned issues and a sample search, with a read-only detail beside the list. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const ui = T.ui;
  const esc = T.esc;
  const icon = T.icon;
  const store = T.store;

  let tab = T.param('q') ? 'search' : 'mine';
  let query = T.param('q') || '';
  let selKey = T.param('key');

  function online() { const st = T.state().sim.jira; return st === 'fresh' || st === 'refreshing'; }

  function visible() {
    const all = T.state().jira;
    if (tab === 'mine') return all.filter((j) => j.mine && !j.remoteOnly);
    const q = query.trim().toLowerCase();
    if (!q) return [];
    return all.filter((j) => (online() || !j.remoteOnly) && (j.key + ' ' + j.title + ' ' + j.components.join(' ')).toLowerCase().indexOf(q) !== -1);
  }

  function render() {
    const s = T.state();
    const st = T.ext.sourceState('jira', 'issues');
    const head = T.pageHead('Jira', 'Read-only. Your assigned issues are cached on this device so they open offline.',
      T.ext.simControl('jira') + '<button type="button" class="btn" data-act="extRefresh" data-src="jira">' + icon('refresh') + 'Refresh</button>');
    if (st.blocked) return head + st.banner;

    const mineN = s.jira.filter((j) => j.mine && !j.remoteOnly).length;
    const tabs = '<div class="seg" role="group" aria-label="Which issues">' +
      '<button type="button" aria-pressed="' + (tab === 'mine') + '" data-act="jiraTab" data-value="mine">Assigned to me <span class="count">' + mineN + '</span></button>' +
      '<button type="button" aria-pressed="' + (tab === 'search') + '" data-act="jiraTab" data-value="search">' + icon('search') + 'Search</button></div>';

    const list = visible();
    if (!list.some((j) => j.key === selKey)) selKey = list.length ? list[0].key : null;
    const sel = selKey ? S.jiraByKey(selKey) : null;

    let search = '';
    if (tab === 'search') {
      search = '<form class="searchbox" data-submit="jiraSearch" role="search"><label class="vh" for="jiraQ">Search Jira issues</label>' +
        '<input class="input" id="jiraQ" name="q" value="' + esc(query) + '" placeholder="Try QR, wallet, settings or LARK-455">' +
        '<button type="submit" class="btn">' + icon('search') + 'Search</button></form>' +
        '<p class="faint" style="font-size:12.5px;margin:-6px 0 14px">' + (online() ? 'Sample search over synthetic issues. Results you open are downloaded for offline reading.' : 'Search needs a connection. Showing matches from the cache only.') + '</p>';
    }

    let body;
    if (tab === 'search' && !query.trim()) body = '<p class="tray-empty">Type a word or an issue key to search.</p>';
    else if (!list.length) body = tab === 'search' ? ui.empty('No issues match “' + query + '”', 'Check the key or try a broader word. Only issues you can see in Jira are searched.', '', 'h3') : ui.empty('No issues assigned to you', 'Issues assigned to you in Jira appear here after a refresh.', '', 'h3');
    else {
      body = '<div class="split"><div class="split-list" role="list" aria-label="Issues">' + list.map((j) =>
        '<div role="listitem"><button type="button" class="rowsel" data-act="selectJira" data-key="' + j.key + '" aria-current="' + (j.key === selKey) + '">' + T.ext.jiraRow(j, j.key === selKey) + '</button></div>').join('') + '</div>' +
        '<aside class="split-detail" aria-label="Issue detail">' + detail(sel) + '</aside></div>';
    }
    return head + (st.banner ? '<div style="margin-bottom:18px">' + st.banner + '</div>' : '') + '<div class="toolbar">' + tabs + '<span class="spacer"></span><span class="readonly-note">' + icon('lock') + 'Read-only</span></div>' + search + body;
  }

  function detail(j) {
    if (!j) return '<p class="faint">Select an issue.</p>';
    if (j.remoteOnly) return '<div class="empty"><h3>' + esc(j.key) + ' is not downloaded</h3><p>It opens once this device can reach Jira.</p></div>';
    return T.ext.jiraDetail(j, { idPrefix: 'jd' });
  }

  function select(key) {
    const j = S.jiraByKey(key);
    if (j && j.remoteOnly && online()) {
      store.update((s) => { s.jira.find((x) => x.key === key).remoteOnly = false; });
      T.toast(key + ' downloaded for offline reading');
    }
    if (T.narrow()) { T.go('detail.html?kind=jira&key=' + encodeURIComponent(key) + '&from=jira'); return; }
    selKey = key;
    history.replaceState(null, '', 'jira.html?key=' + encodeURIComponent(key) + (tab === 'search' && query ? '&q=' + encodeURIComponent(query) : ''));
    T.render();
  }

  T.act.jiraTab = (el) => { tab = el.dataset.value; T.render(); if (tab === 'search') { const i = document.getElementById('jiraQ'); if (i) i.focus(); } };
  T.act.jiraSearch = (form) => { query = String(new FormData(form).get('q') || ''); selKey = null; T.render(); };
  T.act.selectJira = (el) => select(el.dataset.key);

  T.views.jira = { title: 'Jira', render: render };
})();
