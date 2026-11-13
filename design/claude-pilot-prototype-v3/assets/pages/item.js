/* One Jira ticket or pull request as its own page. Used from Topic details on narrow screens and "Open as page". */
(function () {
  const T = window.T;
  const S = T.sel;
  const html = T.html;
  const I = T.I;
  const X = T.ext;
  const kind = T.params.get('kind') === 'pr' ? 'pr' : 'jira';
  let id = T.params.get('id');
  const from = T.params.get('from') || '';
  const fromTopic = from.startsWith('topic:') ? S.topic(from.slice(6)) : null;
  if (fromTopic) T.captureTopic = fromTopic.id;

  T.definePage({
    skip: (source) => source === 'sync' || source === 'editor',
    render(main) {
      const sys = kind === 'jira' ? 'jira' : 'github';
      const back = fromTopic
        ? html`<a class="back" href="topic.html?id=${fromTopic.id}">${I('chevLeft', 16)} ${fromTopic.name}</a>`
        : html`<a class="back" href="${kind === 'jira' ? 'jira.html' : 'prs.html'}">${I('chevLeft', 16)} ${kind === 'jira' ? 'Jira' : 'Pull requests'}</a>`;
      let body;
      if (!X.connected(sys)) body = X.notConnected(sys);
      else if (kind === 'jira') {
        const j = S.jira(id);
        X.markDownloaded(j);
        body = X.jiraDetail(j, { h1: true, relHref: (k) => 'item.html?kind=jira&id=' + k + (from ? '&from=' + from : ''), relAct: 'item-rel' });
      } else body = X.prDetail(S.pr(id), { h1: true });
      const it = kind === 'jira' ? S.jira(id) : S.pr(id);
      if (it) document.title = (kind === 'jira' ? it.key : '#' + it.number) + ' | Thread v3';
      main.innerHTML = html`${back}<div class="ph__acts" style="margin-bottom:16px">${X.demo(sys)}</div>${X.banner(sys)}${body}`.s;
    }
  });
  T.on('item-rel', (el, e) => {
    e.preventDefault();
    id = el.dataset.key;
    T.setParam('id', id);
    T.rerender('local');
    const h = document.getElementById('xd-title');
    if (h) h.focus();
  });
})();
