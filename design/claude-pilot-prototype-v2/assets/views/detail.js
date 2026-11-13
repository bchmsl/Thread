/* Routed external detail: the narrow-screen view of a Jira issue or pull request. Also works as a direct link. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const ui = T.ui;
  const esc = T.esc;
  const icon = T.icon;

  function back() {
    const from = T.param('from') || '';
    const kind = T.param('kind');
    if (from.indexOf('topic:') === 0) {
      const t = S.topicById(from.slice(6));
      if (t) return ['topic.html?id=' + encodeURIComponent(t.id), t.name];
    }
    if (kind === 'pr') return ['prs.html' + (T.param('id') ? '?id=' + encodeURIComponent(T.param('id')) : ''), 'Pull requests'];
    return ['jira.html' + (T.param('key') ? '?key=' + encodeURIComponent(T.param('key')) : ''), 'Jira'];
  }

  function render() {
    const kind = T.param('kind') === 'pr' ? 'pr' : 'jira';
    const b = back();
    const backlink = '<a class="backlink" href="' + b[0] + '">' + icon('chevronLeft') + esc(b[1]) + '</a>';
    const src = kind === 'pr' ? 'github' : 'jira';
    const st = T.ext.sourceState(src, kind === 'pr' ? 'pull requests' : 'issues');
    const item = kind === 'pr' ? S.prById(T.param('id')) : S.jiraByKey(T.param('key'));
    if (!item) return backlink + ui.empty('Not found on this device', 'This record is not in the local cache. Open the list and refresh.', '<a class="btn" href="' + b[0] + '">Back to ' + esc(b[1]) + '</a>');
    if (st.blocked) return backlink + st.banner;
    if (kind === 'jira') document.title = item.key + ' | Thread v2 prototype';
    else document.title = 'PR #' + item.number + ' | Thread v2 prototype';
    return backlink + (st.banner ? '<div style="margin-bottom:18px">' + st.banner + '</div>' : '') +
      '<div class="detail-page">' + (kind === 'pr' ? T.ext.prDetail(item, { idPrefix: 'dd', h1: true }) :
        T.ext.jiraDetail(item, { idPrefix: 'dd', h1: true, relHref: (key) => 'detail.html?kind=jira&key=' + encodeURIComponent(key) + '&from=' + encodeURIComponent(T.param('from') || 'jira') })) + '</div>';
  }

  T.views.detail = { title: 'Detail', render: render };
})();
