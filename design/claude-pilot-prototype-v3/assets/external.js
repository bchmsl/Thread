/* Thread v3 external records: Jira tickets and pull requests.
   Read-only. Shared rows, details and the cache/access states used by Jira, PRs, Topic details and item.html. */
(function () {
  const T = window.T;
  const S = T.sel;
  const D = T.D;
  const html = T.html;
  const I = T.I;
  const X = (T.ext = { older: {} });

  const SYS = { jira: { name: 'Jira', what: 'tickets' }, github: { name: 'GitHub', what: 'pull requests' } };
  const SAVED_AT = '12:40';
  X.state = (sys) => T.state.demo[sys];
  X.connected = (sys) => !!T.state.connections[sys];
  X.online = (sys) => ['fresh', 'cached-refreshing'].includes(X.state(sys));
  X.hasCache = (sys) => X.state(sys) !== 'offline-nocache';
  X.who = (k) => T.people[k] || k;
  const initials = (k) => X.who(k).split(/\s+/).map((w) => w[0]).slice(0, 2).join('').toUpperCase();

  /* ---------------- State banner and demo control ---------------- */
  X.banner = function (sys) {
    const s = X.state(sys);
    const n = SYS[sys].name;
    const box = (tone, icon, title, text, acts) => html`<div class="nbanner nbanner--${tone}" role="status">${I(icon, 18)}
      <div class="nbanner__body"><p class="nbanner__title">${title}</p><p class="nbanner__text">${text}</p></div>
      ${acts ? html`<div class="nbanner__acts">${acts}</div>` : ''}</div>`;
    if (s === 'cached-refreshing') return box('info', 'refresh', 'Refreshing', 'Showing the saved copy from ' + SAVED_AT + ' until ' + n + ' answers.');
    if (s === 'offline-cache') return box('warn', 'cloudOff', 'Offline', 'Showing the saved copy from ' + SAVED_AT + '. Refresh works again when you are back online.');
    if (s === 'offline-nocache') return box('warn', 'cloudOff', 'Offline, nothing saved yet', 'There is no saved copy on this device. Connect to the internet to load your ' + SYS[sys].what + '.');
    if (s === 'expired') return box('bad', 'lock', n + ' access expired', 'Saved copies stay readable. Reconnect to refresh them.',
      html`<button class="btn btn--line btn--sm" type="button" data-act="ext-reconnect" data-sys="${sys}">Reconnect (demo)</button>`);
    if (s === 'rate-limited') return box('warn', 'clock', n + ' is limiting requests', 'Next refresh in about 2 minutes. Showing the saved copy from ' + SAVED_AT + '.');
    return '';
  };
  X.demo = function (sys) {
    const s = X.state(sys);
    const opt = (v, l) => html`<option value="${v}" ${s === v ? 'selected' : ''}>${l}</option>`;
    return T.demoBar(SYS[sys].name + ' connection', html`<label class="vh" for="ext-state-${sys}">${SYS[sys].name} connection state (demo)</label>
      <select class="input input--sm input--auto" id="ext-state-${sys}" data-change="ext-state" data-sys="${sys}" data-k="ext-state-${sys}">
        ${opt('fresh', 'Online, fresh')}${opt('cached-refreshing', 'Cached, refreshing')}${opt('offline-cache', 'Offline, cached')}
        ${opt('offline-nocache', 'Offline, no cache')}${opt('expired', 'Access expired')}${opt('rate-limited', 'Rate limited')}</select>`);
  };
  X.cachebar = function (sys) {
    const s = X.state(sys);
    const label = s === 'fresh' ? 'Updated ' + D.time(T.state.demo.now) : s === 'cached-refreshing' ? 'Refreshing…' : 'Saved copy from ' + SAVED_AT;
    return html`<div class="cachebar"><span>${I(s === 'fresh' ? 'cloudCheck' : s === 'cached-refreshing' ? 'refresh' : 'device', 15)}</span>
      <span>${label}</span><span class="cachebar__sp"></span>
      <button class="btn btn--text btn--sm" type="button" data-act="ext-refresh" data-sys="${sys}" ${s === 'cached-refreshing' ? 'disabled' : ''}>${I('refresh', 15)} Refresh</button></div>`;
  };
  X.notConnected = (sys) => T.empty({
    icon: sys === 'jira' ? 'jira' : 'pr', title: SYS[sys].name + ' is not connected',
    text: 'Connect it in Settings to see your ' + SYS[sys].what + ' here. Thread only reads; it never changes anything in ' + SYS[sys].name + '.',
    action: html`<a class="btn btn--line" href="settings.html#connections">Open Settings</a>`
  });

  T.on('ext-state', (el) => T.act.setIntegration(el.dataset.sys, el.value));
  function refresh(sys, msg) {
    T.act.setIntegration(sys, 'cached-refreshing');
    setTimeout(() => {
      if (X.state(sys) !== 'cached-refreshing') return;
      T.act.setIntegration(sys, 'fresh');
      T.toast(msg);
    }, 1200);
  }
  T.on('ext-refresh', (el) => {
    const sys = el.dataset.sys;
    const s = X.state(sys);
    const n = SYS[sys].name;
    if (s === 'fresh') return refresh(sys, n + ' refreshed');
    if (s === 'offline-cache' || s === 'offline-nocache') return T.toast('Offline. Refresh works again when you are back online.');
    if (s === 'expired') return T.toast(n + ' access expired. Reconnect first.');
    if (s === 'rate-limited') return T.toast(n + ' is limiting requests. Try again in about 2 minutes.');
  });
  T.on('ext-reconnect', (el) => refresh(el.dataset.sys, SYS[el.dataset.sys].name + ' reconnected (demo)'));

  /* ---------------- Indicators ---------------- */
  X.ci = (p) => {
    const icon = p.ci === 'pass' ? 'ciPass' : p.ci === 'fail' ? 'ciFail' : 'ciRun';
    return html`<span class="ind ind--${p.ci === 'running' ? 'running' : p.ci}">${I(icon, 15)}<span class="vh">CI: </span>${p.ciText}</span>`;
  };
  X.review = (p) => {
    const icon = p.review === 'approved' ? 'approved' : p.review === 'changes' ? 'changes' : 'eye';
    return html`<span class="ind ind--${p.review}">${I(icon, 15)}<span class="vh">Review: </span>${p.reviewText}</span>`;
  };
  X.openPill = () => html`<span class="pill-open">${I('pr', 13)}Open</span>`;

  /* ---------------- Rows ---------------- */
  const topicNames = (kind, id) => {
    const ts = S.linksOf(kind, id);
    if (!ts.length) return html`<span>Not linked</span>`;
    return ts.map((t) => T.topicTag(t, { plain: true }));
  };
  X.jiraRow = function (j, opts) {
    opts = opts || {};
    const here = S.jiraDownloaded(j);
    return html`<li><a class="xi" href="${opts.href}" data-act="${opts.act || ''}" data-key="${j.key}" ${opts.current ? T.raw('aria-current="true"') : ''}>
      <span class="xi__top"><span class="xi__key">${j.key}</span><span>${j.type}</span><span class="jst">${j.status}</span></span>
      <span class="xi__title">${j.title}</span>
      <span class="xi__meta">${topicNames('jira', j.key)}${here ? '' : html`<span class="dl-badge">${I('cloud', 13)} Not on this device yet</span>`}</span></a></li>`;
  };
  X.prRow = function (p, opts) {
    opts = opts || {};
    return html`<li><a class="xi" href="${opts.href}" data-act="${opts.act || ''}" data-id="${p.id}" ${opts.current ? T.raw('aria-current="true"') : ''}>
      <span class="xi__top">${X.openPill()}<span>${p.repo}</span></span>
      <span class="xi__title">${p.title} <span class="xi__num">#${p.number}</span></span>
      <span class="xi__meta">${X.ci(p)}${X.review(p)}</span>
      <span class="xi__meta"><span>${p.mine ? 'You' : X.who(p.author)}</span>${topicNames('pr', p.id)}</span></a></li>`;
  };

  /* ---------------- Linked Topics line ---------------- */
  X.links = function (kind, id) {
    const ts = S.linksOf(kind, id);
    return html`<div class="xd__links"><span>${ts.length ? 'Linked to' : 'Not linked to a Topic yet.'}</span>
      ${ts.map((t) => html`<span class="lchip">${T.topicTag(t)}<button class="iconbtn iconbtn--sm" type="button" data-act="ext-unlink"
        data-kind="${kind}" data-id="${id}" data-topic="${t.id}" aria-label="Unlink ${t.name}">${I('x', 14)}</button></span>`)}</div>`;
  };
  T.on('ext-link', async (el) => {
    const { kind, id } = el.dataset;
    const label = kind === 'jira' ? id : '#' + S.pr(id).number;
    const cur = (T.state.links[kind + ':' + id] || []).slice();
    const ids = await T.pickTopics({ title: 'Link ' + label + ' to Topics', multiple: true, selected: cur,
      hint: 'One ' + (kind === 'jira' ? 'ticket' : 'pull request') + ' can belong to several Topics. Nothing is copied.' });
    if (!ids) return;
    T.act.setExternalLinks(kind, id, ids);
    T.toast(ids.length ? 'Linked ' + label + ' to ' + T.topicsPhrase(ids) : 'Removed all Topic links from ' + label,
      { undo: () => T.act.setExternalLinks(kind, id, cur),
        open: ids.length === 1 ? () => { location.href = 'topic.html?id=' + ids[0]; } : null, openLabel: 'Open Topic' });
  });
  /* The prototype makes no network calls, so the outbound link explains itself instead of doing nothing. */
  T.on('ext-out', (el) => T.toast('The real app opens this in ' + el.dataset.sys + ' in a new tab. The demo stays offline.'));
  T.on('ext-unlink', (el) => {
    const { kind, id, topic } = el.dataset;
    const t = S.topic(topic);
    T.act.unlinkExternal(kind, id, topic);
    T.toast('Unlinked from ' + (t ? t.name : 'Topic') + '. The ' + (kind === 'jira' ? 'ticket' : 'pull request') + ' is unchanged.',
      { undo: () => T.act.linkExternal(kind, id, topic) });
  });
  T.on('ext-create-topic', (el) => {
    const j = S.jira(el.dataset.id);
    if (!j) return;
    const tp = T.act.createTopic({ name: j.title });
    T.act.updateTopic(tp.id, { leftOff: 'Created from ' + j.key + '.', nextStep: '' });
    T.act.linkExternal('jira', j.key, tp.id);
    T.toast('Created Topic “' + tp.name + '” and linked ' + j.key, { open: () => { location.href = 'topic.html?id=' + tp.id; }, openLabel: 'Open Topic' });
  });
  T.on('ext-older', (el) => { X.older[el.dataset.id] = true; T.rerender('local'); });

  /* ---------------- Details ---------------- */
  const OLDER = ['Moved into the current sprint.', 'Added the acceptance list.', 'Linked the design ticket.',
    'Asked QA to add a slow-network case.', 'Split error states into their own story.', 'Updated the API reference.'];
  const comment = (c, old) => html`<li class="cmt ${old ? 'cmt--old' : ''}"><span class="cmt__av" aria-hidden="true">${initials(c.a)}</span>
    <div><p class="cmt__who">${X.who(c.a)}<span class="cmt__at">${D.when(c.at)}</span></p><p class="cmt__x">${c.x}</p></div></li>`;
  const roNote = (n) => html`<p class="ro-note">${I('lock', 15)} Read-only in v1. Changes happen in ${n}.</p>`;

  X.unavailable = (sys) => T.empty({ icon: 'cloudOff', title: 'Not saved on this device',
    text: 'This one was never opened here, so there is no copy to show offline. Connect to the internet to load it.' });

  X.jiraAvailable = (j) => X.connected('jira') && (S.jiraDownloaded(j) ? X.hasCache('jira') : X.online('jira'));
  X.markDownloaded = function (j) {
    if (j && !S.jiraDownloaded(j) && X.online('jira')) {
      T.state.demo.jiraDownloaded.push(j.key);
      T.persist();
    }
  };

  X.jiraDetail = function (j, opts) {
    opts = opts || {};
    if (!j) return T.empty({ icon: 'jira', title: 'Ticket not found', text: 'It may not be shared with you.' });
    if (!X.jiraAvailable(j)) return X.unavailable('jira');
    const H = opts.h1 ? 'h1' : 'h2';
    const relHref = opts.relHref || ((k) => 'jira.html?key=' + k);
    const older = X.older[j.key] ? OLDER.slice(0, j.olderComments).map((x, i) => ({ a: ['sam', 'lena', 'mira', 'teo'][i % 4], at: '2026-11-0' + (5 + i) + 'T10:1' + i, x })) : [];
    return html`<article class="xd" aria-labelledby="xd-title">
      <header class="xd__head">
        <p class="xd__kicker"><span class="xd__key">${j.key}</span><span>${j.type}</span><span class="jst">${j.status}</span>
          ${S.jiraDownloaded(j) ? html`<span class="dl-badge">${I('device', 13)} Saved on this device</span>` : ''}</p>
        <${T.raw(H)} class="xd__title" id="xd-title" tabindex="-1">${j.title}</${T.raw(H)}>
        ${X.links('jira', j.key)}
        <div class="xd__acts">
          <button class="btn btn--primary" type="button" data-act="ext-link" data-kind="jira" data-id="${j.key}">Link to Topic</button>
          <button class="btn btn--line" type="button" data-act="ext-create-topic" data-id="${j.key}">${I('plus', 16)} Create Topic from ticket</button>
          <button class="btn btn--text" type="button" data-act="ext-out" data-sys="Jira">${I('external', 16)} Open in Jira</button>
        </div>
      </header>
      <div class="xd__grid">
        <div class="xd__main">
          <h3 class="xd__h">Description</h3>
          <div class="desc">${j.description.map((b) => {
            if (b.t === 'h') return html`<h4>${b.x}</h4>`;
            if (b.t === 'ul') return html`<ul>${b.items.map((x) => html`<li>${x}</li>`)}</ul>`;
            if (b.t === 'code') return html`<pre><code>${b.x}</code></pre>`;
            if (b.t === 'unsupported') return html`<p class="unsup">${I('doc', 16)} ${b.x} is not supported here. Open the ticket in Jira to see it.</p>`;
            return html`<p>${b.x}</p>`;
          })}</div>
          <h3 class="xd__h">Comments <span class="n">${j.comments.length + j.olderComments}</span></h3>
          ${j.olderComments && !X.older[j.key] ? html`<button class="btn btn--text btn--sm" type="button" data-act="ext-older" data-id="${j.key}">Load ${j.olderComments} older ${j.olderComments === 1 ? 'comment' : 'comments'}</button>` : ''}
          ${j.comments.length || older.length ? html`<ul class="cmts">${older.map((c) => comment(c, true))}${j.comments.map((c) => comment(c))}</ul>` : html`<p class="muted">No comments.</p>`}
          <h3 class="xd__h">Related issues</h3>
          ${j.related.length ? html`<ul class="rel">${j.related.map((r) => {
            const o = S.jira(r.key);
            const can = o && X.jiraAvailable(o);
            return html`<li><span class="rel__type">${r.rel}</span>${can ? html`<a class="lnk" href="${relHref(r.key)}" data-act="${opts.relAct || ''}" data-key="${r.key}"><b>${r.key}</b> ${o.title}</a>`
              : html`<span><b>${r.key}</b> <span class="muted">${o ? o.title + ' (not on this device)' : 'not shared with you'}</span></span>`}</li>`;
          })}</ul>` : html`<p class="muted">None.</p>`}
        </div>
        <dl class="fields">
          <dt>Status</dt><dd>${j.status}</dd>
          <dt>Priority</dt><dd>${j.priority}</dd>
          <dt>Assignee</dt><dd>${X.who(j.assignee)}</dd>
          <dt>Reporter</dt><dd>${X.who(j.reporter)}</dd>
          <dt>Sprint</dt><dd>${j.sprint}</dd>
          <dt>Labels</dt><dd>${j.labels.join(', ')}</dd>
          <dt>Updated</dt><dd>${D.when(j.updated)}</dd>
        </dl>
      </div>
      ${roNote('Jira')}
    </article>`;
  };

  X.prAvailable = (p) => X.connected('github') && X.hasCache('github');
  X.prDetail = function (p, opts) {
    opts = opts || {};
    if (!p) return T.empty({ icon: 'pr', title: 'Pull request not found', text: 'It may be closed or not shared with you.' });
    if (!X.prAvailable(p)) return X.unavailable('github');
    const H = opts.h1 ? 'h1' : 'h2';
    return html`<article class="xd" aria-labelledby="xd-title">
      <header class="xd__head">
        <p class="xd__kicker">${X.openPill()}<span>${p.repo}</span><span>${I('branch', 14)} ${p.branch}</span></p>
        <${T.raw(H)} class="xd__title" id="xd-title" tabindex="-1">${p.title} <span class="xi__num">#${p.number}</span></${T.raw(H)}>
        <p class="xd__kicker">${X.ci(p)}${X.review(p)}</p>
        ${X.links('pr', p.id)}
        <div class="xd__acts">
          <button class="btn btn--primary" type="button" data-act="ext-link" data-kind="pr" data-id="${p.id}">Link to Topic</button>
          <button class="btn btn--text" type="button" data-act="ext-out" data-sys="GitHub">${I('external', 16)} Open in GitHub</button>
        </div>
      </header>
      <div class="xd__grid">
        <div class="xd__main">
          <h3 class="xd__h">Description</h3>
          <div class="desc"><p>${p.body}</p></div>
          <h3 class="xd__h">Comments <span class="n">${p.comments.length}</span></h3>
          ${p.comments.length ? html`<ul class="cmts">${p.comments.map((c) => comment(c))}</ul>` : html`<p class="muted">No comments.</p>`}
          <h3 class="xd__h">Reviewers</h3>
          ${p.reviewers.length ? html`<ul class="revs">${p.reviewers.map((r) => html`<li><span>${X.who(r.who)}</span><span class="muted">${r.state}</span></li>`)}</ul>` : html`<p class="muted">No reviewers yet.</p>`}
        </div>
        <dl class="fields">
          <dt>Repository</dt><dd>${p.repo}</dd>
          <dt>Author</dt><dd>${X.who(p.author)}</dd>
          <dt>Branch</dt><dd>${p.branch}</dd>
          <dt>Updated</dt><dd>${D.when(p.updated)}</dd>
        </dl>
      </div>
      ${roNote('GitHub')}
    </article>`;
  };
})();
