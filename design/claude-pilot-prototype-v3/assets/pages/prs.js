/* Pull requests: Mine and Review requested on the left, the pull request on the right. Read-only. */
(function () {
  const T = window.T;
  const S = T.sel;
  const html = T.html;
  const I = T.I;
  const X = T.ext;
  const page = { tab: T.params.get('tab') === 'review' ? 'review' : 'mine', id: T.params.get('id') };

  const list = () => (page.tab === 'review' ? S.reviewRequested() : S.myPRs());
  const rowHref = (id) => 'prs.html?' + (page.tab === 'review' ? 'tab=review&' : '') + 'id=' + id;
  function shownId() {
    if (page.id) return page.id;
    if (window.innerWidth < 1000 || !X.hasCache('github')) return null;
    const first = list()[0];
    return first ? first.id : null;
  }

  function listPanel() {
    if (!X.hasCache('github')) return T.empty({ icon: 'cloudOff', title: 'Nothing saved on this device yet', text: 'Pull requests load when you are back online.' });
    const ps = list();
    if (!ps.length) {
      return T.empty({ icon: 'pr', title: page.tab === 'review' ? 'No review requests' : 'No open pull requests',
        text: page.tab === 'review' ? 'Pull requests that ask for your review show here.' : 'Your open pull requests show here.' });
    }
    return html`${X.cachebar('github')}<ul class="md__scroll">${ps.map((p) => X.prRow(p, { href: rowHref(p.id), act: 'pr-open', current: p.id === shownId() }))}</ul>`;
  }

  T.definePage({
    skip: (source) => source === 'sync' || source === 'editor',
    render(main) {
      if (!X.connected('github')) {
        main.innerHTML = html`<header class="ph"><div><h1 class="ph__title">Pull requests</h1></div></header>${X.notConnected('github')}`.s;
        return;
      }
      const id = shownId();
      const p = id ? S.pr(id) : null;
      const cnt = (v) => (X.hasCache('github') ? (v === 'review' ? S.reviewRequested() : S.myPRs()).length : null);
      const tab = (v, label) => html`<button class="tab" type="button" role="tab" id="tab-${v}" aria-selected="${page.tab === v}" aria-controls="pl-panel"
        tabindex="${page.tab === v ? '0' : '-1'}" data-act="pr-tab" data-v="${v}" data-k="tab-${v}">${label}${cnt(v) != null ? html` <span class="n">${cnt(v)}</span>` : ''}</button>`;
      main.innerHTML = html`
        <header class="ph">
          <div><h1 class="ph__title">Pull requests</h1><p class="ph__sub">Read-only. Open pull requests from GitHub.</p></div>
          <div class="ph__acts">${X.demo('github')}</div>
        </header>
        ${X.banner('github')}
        <div class="md ${page.id ? 'has-open' : ''}">
          <div class="md__list">
            <div class="tabs" role="tablist" aria-label="Pull request lists">${tab('mine', 'Mine')}${tab('review', 'Review requested')}</div>
            <div id="pl-panel" role="tabpanel" aria-labelledby="tab-${page.tab}" class="md__list">${listPanel()}</div>
          </div>
          <div class="md__detail">
            <a class="back md-back" href="prs.html${page.tab === 'review' ? '?tab=review' : ''}" data-act="pr-back">${I('chevLeft', 16)} ${page.tab === 'review' ? 'Review requested' : 'Mine'}</a>
            ${p ? X.prDetail(p) : T.empty({ icon: 'pr', title: 'No pull request selected', text: 'Pick one from the list to read it here.' })}
          </div>
        </div>`.s;
    }
  });

  T.on('pr-tab', (el) => {
    page.tab = el.dataset.v;
    page.id = null;
    T.setParam('tab', page.tab === 'review' ? 'review' : null);
    T.setParam('id', null);
    T.rerender('local');
    const t = document.getElementById('tab-' + page.tab);
    if (t) t.focus();
  });
  T.on('pr-open', (el, e) => {
    e.preventDefault();
    page.id = el.dataset.id;
    T.setParam('id', page.id);
    T.rerender('local');
    if (window.innerWidth < 1000) window.scrollTo(0, 0);
    const h = document.getElementById('xd-title');
    if (h) h.focus({ preventScroll: window.innerWidth >= 1000 });
  });
  T.on('pr-back', (el, e) => {
    e.preventDefault();
    const was = page.id;
    page.id = null;
    T.setParam('id', null);
    T.rerender('local');
    const row = document.querySelector('.xi[data-id="' + was + '"]');
    if (row) row.focus();
  });
  document.addEventListener('keydown', (e) => {
    const tab = e.target.closest && e.target.closest('[role="tab"]');
    if (!tab || !['ArrowLeft', 'ArrowRight'].includes(e.key)) return;
    e.preventDefault();
    const tabs = Array.from(document.querySelectorAll('[role="tab"]'));
    tabs[(tabs.indexOf(tab) + (e.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length].click();
  });
  matchMedia('(min-width: 1000px)').addEventListener('change', () => { if (!page.id) T.rerender('local'); });
})();
