/* Pull requests: My PRs and Review requested, with a read-only detail beside the list. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const ui = T.ui;
  const icon = T.icon;

  let tab = T.param('tab') === 'review' ? 'review' : 'mine';
  let selId = T.param('id');
  if (selId && !T.param('tab')) { const p = S.prById(selId); if (p && p.requested && !p.mine) tab = 'review'; }

  function render() {
    const s = T.state();
    const st = T.ext.sourceState('github', 'pull requests');
    const head = T.pageHead('Pull requests', 'Read-only. Your pull requests and the ones waiting for your review.',
      T.ext.simControl('github') + '<button type="button" class="btn" data-act="extRefresh" data-src="github">' + icon('refresh') + 'Refresh</button>');
    if (st.blocked) return head + st.banner;

    const mine = s.prs.filter((p) => p.mine);
    const review = s.prs.filter((p) => p.requested);
    const list = tab === 'mine' ? mine : review;
    if (!list.some((p) => p.id === selId)) selId = list.length ? list[0].id : null;
    const sel = selId ? S.prById(selId) : null;

    const tabs = '<div class="seg" role="group" aria-label="Which pull requests">' +
      '<button type="button" aria-pressed="' + (tab === 'mine') + '" data-act="prTab" data-value="mine">My PRs <span class="count">' + mine.length + '</span></button>' +
      '<button type="button" aria-pressed="' + (tab === 'review') + '" data-act="prTab" data-value="review">Review requested <span class="count">' + review.length + '</span></button></div>';

    const body = !list.length ? ui.empty(tab === 'mine' ? 'No open pull requests' : 'No reviews requested', tab === 'mine' ? 'Pull requests you open on GitHub appear here.' : 'When someone asks for your review, it shows here and on Today.', '', 'h3') :
      '<div class="split"><div class="split-list" role="list" aria-label="Pull requests">' + list.map((p) =>
        '<div role="listitem"><button type="button" class="rowsel" data-act="selectPr" data-id="' + p.id + '" aria-current="' + (p.id === selId) + '">' + T.ext.prListRow(p, p.id === selId) + '</button></div>').join('') + '</div>' +
      '<aside class="split-detail" aria-label="Pull request detail">' + (sel ? T.ext.prDetail(sel, { idPrefix: 'pd' }) : '') + '</aside></div>';

    return head + (st.banner ? '<div style="margin-bottom:18px">' + st.banner + '</div>' : '') +
      '<div class="toolbar">' + tabs + '<span class="spacer"></span><span class="readonly-note">' + icon('lock') + 'Read-only</span></div>' + body;
  }

  T.act.prTab = (el) => { tab = el.dataset.value; selId = null; history.replaceState(null, '', 'prs.html' + (tab === 'review' ? '?tab=review' : '')); T.render(); };
  T.act.selectPr = (el) => {
    const id = el.dataset.id;
    if (T.narrow()) { T.go('detail.html?kind=pr&id=' + encodeURIComponent(id) + '&from=prs'); return; }
    selId = id;
    history.replaceState(null, '', 'prs.html?id=' + encodeURIComponent(id) + (tab === 'review' ? '&tab=review' : ''));
    T.render();
  };

  T.views.prs = { title: 'Pull requests', render: render };
})();
