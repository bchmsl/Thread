/* Jira: list on the left (Assigned to me, Search), the ticket on the right. Read-only, cached on this device. */
(function () {
  const T = window.T;
  const S = T.sel;
  const html = T.html;
  const I = T.I;
  const X = T.ext;
  const page = {
    tab: T.params.get('tab') === 'search' ? 'search' : 'assigned',
    key: T.params.get('key'), q: T.params.get('q') || '', searching: false, results: null, msg: ''
  };

  function runSearch(q) {
    const s = q.trim().toLowerCase();
    return T.jiraIssues.filter((j) => j.key.toLowerCase().includes(s) || j.title.toLowerCase().includes(s) || j.labels.some((l) => l.includes(s)));
  }
  if (page.tab === 'search' && page.q && X.connected('jira') && X.online('jira')) page.results = runSearch(page.q);

  function blockedReason() {
    const s = X.state('jira');
    if (s === 'offline-cache' || s === 'offline-nocache') return 'Jira search needs a connection. Tickets saved on this device are still in Assigned to me and in ⌘K search.';
    if (s === 'expired') return 'Jira access expired. Reconnect Jira to search.';
    if (s === 'rate-limited') return 'Jira is limiting requests. Try again in about 2 minutes.';
    return '';
  }

  function visible() {
    if (page.tab === 'search') return page.results || [];
    return X.hasCache('jira') ? T.jiraIssues.filter((j) => j.assigned) : [];
  }
  const rowHref = (k) => 'jira.html?tab=' + page.tab + (page.tab === 'search' && page.q ? '&q=' + encodeURIComponent(page.q) : '') + '&key=' + k;

  function listPanel() {
    const rows = visible();
    if (page.tab === 'assigned') {
      if (!X.hasCache('jira')) return T.empty({ icon: 'cloudOff', title: 'Nothing saved on this device yet', text: 'Your assigned tickets load when you are back online.' });
      return html`${X.cachebar('jira')}<ul class="md__scroll">${rows.map((j) => X.jiraRow(j, { href: rowHref(j.key), act: 'jira-open', current: j.key === shownKey() }))}</ul>`;
    }
    return html`<form class="addtask" data-submit="jira-search" role="search" autocomplete="off">
        <label class="vh" for="jq">Search Jira</label>
        <input class="input" type="search" id="jq" name="q" value="${page.q}" placeholder="Key, words or label" data-k="jq">
        <button class="btn btn--line" type="submit" ${page.searching ? 'disabled' : ''}>${I('search', 16)} Search</button></form>
      <p class="muted" style="font-size:13px" aria-live="polite">${page.searching ? 'Searching Jira…' : page.msg ? page.msg
        : page.results ? page.results.length + (page.results.length === 1 ? ' ticket' : ' tickets') + ' found. Opening one saves it on this device.' : 'Searches all of Jira that you can see. Needs a connection.'}</p>
      ${page.results && page.results.length ? html`<ul class="md__scroll">${page.results.map((j) => X.jiraRow(j, { href: rowHref(j.key), act: 'jira-open', current: j.key === shownKey() }))}</ul>`
        : page.results ? T.empty({ icon: 'search', title: 'No tickets match', text: 'Try a ticket key such as KITE-412, or fewer words.' }) : ''}`;
  }
  function shownKey() {
    if (page.key) return page.key;
    if (window.innerWidth < 1000) return null;
    const first = visible()[0];
    return first ? first.key : null;
  }

  T.definePage({
    skip: (source) => source === 'sync' || source === 'editor',
    render(main) {
      if (!X.connected('jira')) {
        main.innerHTML = html`<header class="ph"><div><h1 class="ph__title">Jira</h1></div></header>${X.notConnected('jira')}`.s;
        return;
      }
      const key = shownKey();
      const j = key ? S.jira(key) : null;
      const assigned = T.jiraIssues.filter((x) => x.assigned).length;
      const tab = (v, label, n) => html`<button class="tab" type="button" role="tab" id="tab-${v}" aria-selected="${page.tab === v}" aria-controls="jl-panel"
        tabindex="${page.tab === v ? '0' : '-1'}" data-act="jira-tab" data-v="${v}" data-k="tab-${v}">${label}${n != null ? html` <span class="n">${n}</span>` : ''}</button>`;
      main.innerHTML = html`
        <header class="ph">
          <div><h1 class="ph__title">Jira</h1><p class="ph__sub">Read-only. Tickets you open are saved on this device.</p></div>
          <div class="ph__acts">${X.demo('jira')}</div>
        </header>
        ${X.banner('jira')}
        <div class="md ${page.key ? 'has-open' : ''}">
          <div class="md__list">
            <div class="tabs" role="tablist" aria-label="Jira lists">${tab('assigned', 'Assigned to me', X.hasCache('jira') ? assigned : null)}${tab('search', 'Search')}</div>
            <div id="jl-panel" role="tabpanel" aria-labelledby="tab-${page.tab}" class="md__list">${listPanel()}</div>
          </div>
          <div class="md__detail">
            <a class="back md-back" href="jira.html?tab=${page.tab}" data-act="jira-back">${I('chevLeft', 16)} ${page.tab === 'search' ? 'Search results' : 'Assigned to me'}</a>
            ${j ? X.jiraDetail(j, { relHref: (k) => rowHref(k), relAct: 'jira-open' })
              : T.empty({ icon: 'jira', title: page.tab === 'search' ? 'Search for a ticket' : 'No ticket selected', text: 'Pick one from the list to read it here.' })}
          </div>
        </div>`.s;
    }
  });

  T.on('jira-tab', (el) => {
    page.tab = el.dataset.v;
    page.key = null;
    T.setParam('tab', page.tab === 'search' ? 'search' : null);
    T.setParam('key', null);
    T.rerender('local');
    const t = document.getElementById('tab-' + page.tab);
    if (t) t.focus();
    if (page.tab === 'search' && !page.results) { const q = document.getElementById('jq'); if (q) q.focus(); }
  });
  T.on('jira-open', (el, e) => {
    e.preventDefault();
    const j = S.jira(el.dataset.key);
    if (!j) return;
    page.key = j.key;
    T.setParam('key', j.key);
    X.markDownloaded(j);
    T.rerender('local');
    if (window.innerWidth < 1000) window.scrollTo(0, 0);
    const h = document.getElementById('xd-title');
    if (h) h.focus({ preventScroll: window.innerWidth >= 1000 });
  });
  T.on('jira-back', (el, e) => {
    e.preventDefault();
    const was = page.key;
    page.key = null;
    T.setParam('key', null);
    T.rerender('local');
    const row = document.querySelector('.xi[data-key="' + was + '"]');
    if (row) row.focus();
  });
  T.on('jira-search', (form) => {
    page.q = form.elements.q.value.trim();
    T.setParam('q', page.q || null);
    if (!page.q) { page.results = null; page.msg = 'Type a key, words or a label first.'; T.rerender('local'); return; }
    const why = blockedReason();
    if (why) { page.msg = why; page.results = null; T.rerender('local'); return; }
    page.msg = '';
    page.searching = true;
    T.rerender('local');
    setTimeout(() => {
      page.searching = false;
      page.results = runSearch(page.q);
      page.key = null;
      T.setParam('key', null);
      T.rerender('local');
    }, 600);
  });
  document.addEventListener('keydown', (e) => {
    const tab = e.target.closest && e.target.closest('[role="tab"]');
    if (!tab || !['ArrowLeft', 'ArrowRight'].includes(e.key)) return;
    e.preventDefault();
    const tabs = Array.from(document.querySelectorAll('[role="tab"]'));
    const next = tabs[(tabs.indexOf(tab) + (e.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length];
    next.click();
  });
  matchMedia('(min-width: 1000px)').addEventListener('change', () => { if (!page.key) T.rerender('local'); });
})();
