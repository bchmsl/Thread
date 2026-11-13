/* Shared read-only renderers for Jira issues and pull requests, their cache states, and Topic linking.
   Used by the Jira and PR pages, the Topic side panel and the routed detail page. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const TM = T.time;
  const ui = T.ui;
  const esc = T.esc;
  const icon = T.icon;
  const store = T.store;

  const CACHED_AT = '09:42';
  const SIM_OPTIONS = [
    ['fresh', 'Up to date'],
    ['refreshing', 'Refreshing, cache first'],
    ['offline', 'Offline, cache available'],
    ['nocache', 'Offline, nothing cached'],
    ['expired', 'Access expired'],
    ['ratelimit', 'Rate limited']
  ];
  const NAMES = { jira: 'Jira', github: 'GitHub' };

  function simState(src) { return T.state().sim[src]; }
  function connected(src) { return T.state().sim[src === 'jira' ? 'jiraConnected' : 'githubConnected']; }

  /* Banner or empty state for the current simulated source state. Returns { banner, blocked }. */
  function sourceState(src, noun) {
    const name = NAMES[src];
    if (!connected(src)) {
      return { blocked: true, banner: ui.empty(name + ' is not connected', 'This demo connection is off. Connecting is simulated and never asks for a password or token.',
        '<a class="btn" href="settings.html#connections">' + icon('link') + 'Connect ' + name + ' in Settings</a>') };
    }
    const st = simState(src);
    if (st === 'refreshing') return { banner: '<div class="banner info" role="status">' + icon('refresh', 'spin') + '<div><strong>Refreshing.</strong> Showing ' + noun + ' cached at ' + CACHED_AT + ' until ' + name + ' answers.</div></div>' };
    if (st === 'offline') return { banner: '<div class="banner warn" role="status">' + icon('wifiOff') + '<div><strong>Offline.</strong> Showing ' + noun + ' cached on this device at ' + CACHED_AT + '. They refresh when you are back online.</div></div>' };
    if (st === 'nocache') return { blocked: true, banner: ui.empty('Nothing cached on this device', 'You are offline and ' + noun + ' have not been downloaded here yet. They appear once this device connects to ' + name + '.', '') };
    if (st === 'expired') return { banner: '<div class="banner bad" role="alert">' + icon('lock') + '<div><strong>' + name + ' access expired.</strong> Cached ' + noun + ' stay readable and your links are kept. Reconnect to refresh.</div><div class="banner-actions"><button type="button" class="btn btn-sm" data-act="extReconnect" data-src="' + src + '">Reconnect (demo)</button></div></div>' };
    if (st === 'ratelimit') return { banner: '<div class="banner warn" role="status">' + icon('clock') + '<div><strong>' + name + ' asked Thread to slow down.</strong> Showing cached ' + noun + '. The next refresh runs in about 2 minutes.</div></div>' };
    return { banner: '' };
  }

  function freshLine(src, updated) {
    const st = simState(src);
    const map = {
      fresh: [icon('cloud'), 'Fetched from ' + NAMES[src] + ' at ' + (T.state().sim[src + 'At'] || '11:18') + '. Last changed ' + TM.fmtStamp(updated) + '.'],
      refreshing: [icon('refresh', 'spin'), 'Cached at ' + CACHED_AT + ', refreshing now.'],
      offline: [icon('cloudOff'), 'Cached at ' + CACHED_AT + ' on this device. Offline.'],
      expired: [icon('lock'), 'Cached at ' + CACHED_AT + '. Access expired, not refreshing.'],
      ratelimit: [icon('clock'), 'Cached at ' + CACHED_AT + '. Refresh paused by a rate limit.']
    };
    const m = map[st] || map.fresh;
    return '<p class="xd-fresh">' + m[0] + '<span>' + esc(m[1]) + '</span></p>';
  }

  function simControl(src) {
    return ui.sim(NAMES[src] + ' state', 'extSim', SIM_OPTIONS, simState(src), 'data-src="' + src + '" aria-label="' + NAMES[src] + ' connection state, simulation"');
  }

  function blocks(list) {
    return '<div class="adf">' + (list || []).map((b) => {
      if (b.t === 'p') return '<p>' + esc(b.text) + '</p>';
      if (b.t === 'h') return '<h4>' + esc(b.text) + '</h4>';
      if (b.t === 'ul') return '<ul>' + b.items.map((i) => '<li>' + esc(i) + '</li>').join('') + '</ul>';
      if (b.t === 'code') return '<code>' + esc(b.text) + '</code>';
      if (b.t === 'unsupported') return '<div class="unsupported">' + icon('alert') + '<span>' + esc(b.label) + ' cannot be shown in Thread. It is only visible in Jira.</span></div>';
      return '';
    }).join('') + '</div>';
  }

  function comments(list, more, src) {
    if (!list.length && !more) return '<p class="faint" style="font-size:13.5px">No comments.</p>';
    return list.map((c) => '<div class="comment"><div class="who">' + esc(c.author) + '<span>' + esc(TM.fmtStamp(c.at)) + '</span></div><p>' + esc(c.text) + '</p></div>').join('') +
      (more ? '<p class="faint" style="font-size:12.5px;margin-top:8px">' + more + ' older comment' + (more === 1 ? ' is' : 's are') + ' only in ' + NAMES[src] + '. Thread keeps the latest ones on this device.</p>' : '');
  }

  /* Topic links for an external record. Linking adds a reference; it never copies or changes the record. */
  function links(kind, item) {
    const id = kind === 'jira' ? item.key : item.id;
    const label = kind === 'jira' ? item.key : '#' + item.number;
    const linked = item.topicIds.map((tid) => S.topicById(tid)).filter(Boolean);
    const free = T.state().topics.filter((t) => item.topicIds.indexOf(t.id) === -1);
    return '<div class="links-box">' +
      (linked.length ? '<div class="ttab-row">' + linked.map((t) => ui.ttabX(t, 'extUnlink', 'data-kind="' + kind + '" data-id="' + esc(id) + '" data-topic="' + t.id + '"', 'Unlink ' + label + ' from ' + t.name)).join('') + '</div>' :
        '<p class="faint" style="font-size:13.5px">Not linked to a topic yet.</p>') +
      (free.length ? '<form class="linker" data-submit="extLink" data-kind="' + kind + '" data-id="' + esc(id) + '"><label class="vh" for="lk-' + esc(id) + '">Topic to link ' + esc(label) + ' to</label>' +
        '<select class="select" id="lk-' + esc(id) + '" name="topic"><option value="">Choose a topic</option>' + free.map((t) => '<option value="' + t.id + '">' + esc(t.name) + '</option>').join('') + '</select>' +
        '<button type="submit" class="btn-key">' + icon('link') + '<span>Link</span></button></form>' : '') +
      '<button type="button" class="btn btn-sm btn-ghost" style="justify-self:start" data-act="extCreateTopic" data-kind="' + kind + '" data-id="' + esc(id) + '">' + icon('plus') + 'Create a topic from this ' + (kind === 'jira' ? 'issue' : 'pull request') + '</button>' +
    '</div>';
  }

  function jiraDetail(j, o) {
    o = o || {};
    const hid = (o.idPrefix || 'xd') + '-' + j.key;
    return '<article class="xd" aria-labelledby="' + hid + '">' +
      '<div class="xd-head"><div class="xd-id"><span class="jkey">' + esc(j.key) + '</span><span class="jstatus">' + esc(j.status) + '</span><span class="faint" style="font-size:13px">' + esc(j.type) + '</span></div>' +
        '<' + (o.h1 ? 'h1' : 'h2') + ' class="xd-title" id="' + hid + '" tabindex="-1">' + esc(j.title) + '</' + (o.h1 ? 'h1' : 'h2') + '>' + freshLine('jira', j.updated) +
        '<p class="readonly-note">' + icon('lock') + 'Read-only in Thread. Change the issue in Jira.</p></div>' +
      '<section class="xd-sec"><h3>' + icon('folder') + 'Topics</h3>' + links('jira', j) + '</section>' +
      '<section class="xd-sec"><h3>Fields</h3><dl class="fields">' +
        [['Status', j.status], ['Priority', j.priority], ['Assignee', j.assignee], ['Reporter', j.reporter], ['Sprint', j.sprint], ['Components', j.components.join(', ')], ['Fix version', j.fixVersion], ['Updated', TM.fmtStamp(j.updated)]]
          .map((f) => '<dt>' + f[0] + '</dt><dd>' + esc(f[1]) + '</dd>').join('') + '</dl></section>' +
      '<section class="xd-sec"><h3>Description</h3>' + blocks(j.desc) + '</section>' +
      '<section class="xd-sec"><h3>' + icon('comment') + 'Comments</h3>' + comments(j.comments, j.moreComments, 'jira') + '</section>' +
      (j.related.length ? '<section class="xd-sec"><h3>Related issues</h3><ul class="checks">' + j.related.map((r) => {
        const rj = S.jiraByKey(r.key);
        const cached = rj && !rj.remoteOnly;
        return '<li><span class="faint">' + esc(r.rel) + '</span>' + (cached ? '<a class="linkbtn" href="' + (o.relHref ? o.relHref(r.key) : 'jira.html?key=' + r.key) + '"><span class="jkey" style="font-size:16px">' + esc(r.key) + '</span> ' + esc(rj.title) + '</a>' : '<span>' + esc(r.key) + ' (not downloaded)</span>') + '</li>';
      }).join('') + '</ul></section>' : '') +
    '</article>';
  }

  function prDetail(p, o) {
    o = o || {};
    const hid = (o.idPrefix || 'xd') + '-' + p.id;
    const checkIcon = { passing: 'circleCheck', failing: 'circleX', running: 'clock' };
    const checkCls = { passing: 'ok', failing: 'fail', running: 'run' };
    const revWord = { approved: 'Approved', changes: 'Changes requested', pending: 'Pending' };
    return '<article class="xd" aria-labelledby="' + hid + '">' +
      '<div class="xd-head"><div class="xd-id">' + ui.prPill(p) + '<span class="pr-num">#' + p.number + '</span></div>' +
        '<' + (o.h1 ? 'h1' : 'h2') + ' class="xd-title" id="' + hid + '" tabindex="-1">' + esc(p.title) + '</' + (o.h1 ? 'h1' : 'h2') + '>' +
        '<p class="pr-sub" style="grid-column:auto"><span>' + esc(p.repo) + '</span><span>' + icon('branch') + esc(p.branch) + ' into ' + esc(p.base) + '</span><span>' + esc(p.author) + '</span></p>' +
        freshLine('github', p.updated) +
        '<p class="readonly-note">' + icon('lock') + 'Read-only in Thread. Review and merge on GitHub.</p></div>' +
      '<section class="xd-sec"><h3>' + icon('folder') + 'Topics</h3>' + links('pr', p) + '</section>' +
      '<section class="xd-sec"><h3>Checks</h3><div style="margin-bottom:10px">' + ui.ci(p.ci) + '</div><ul class="checks">' +
        p.checks.map((c) => '<li><span>' + esc(c.name) + '</span><span class="ind ' + checkCls[c.state] + '">' + icon(checkIcon[c.state]) + esc(c.state) + '</span></li>').join('') + '</ul></section>' +
      '<section class="xd-sec"><h3>Review</h3><div style="margin-bottom:10px">' + ui.review(p) + '</div>' +
        (p.reviewers.length ? '<ul class="checks">' + p.reviewers.map((r) => '<li><span>' + esc(r.name) + '</span><span class="faint">' + revWord[r.state] + '</span></li>').join('') + '</ul>' : '<p class="faint" style="font-size:13.5px">No reviewers yet.</p>') + '</section>' +
      '<section class="xd-sec"><h3>Description</h3>' + blocks(p.body) + '</section>' +
      '<section class="xd-sec"><h3>' + icon('comment') + 'Comments</h3>' + comments(p.comments, 0, 'github') + '</section>' +
    '</article>';
  }

  function jiraRow(j, selected) {
    const tabs = j.topicIds.map((tid) => S.topicById(tid)).filter(Boolean).map((t) => ui.ttab(t, { size: 'sm', link: false })).join('');
    return '<div class="jrow' + (selected ? ' selected' : '') + '"><span class="jkey">' + esc(j.key) + '</span><span class="pr-title">' + esc(j.title) + '</span><span class="jstatus">' + esc(j.status) + '</span>' +
      '<span class="jsub"><span>' + esc(j.type) + '</span><span>' + esc(j.priority) + ' priority</span><span>Updated ' + esc(TM.fmtStamp(j.updated)) + '</span>' + tabs + (j.remoteOnly ? '<span class="m">' + icon('cloud') + 'Not downloaded</span>' : '') + '</span></div>';
  }

  function prListRow(p, selected) {
    const tabs = p.topicIds.map((tid) => S.topicById(tid)).filter(Boolean).map((t) => ui.ttab(t, { size: 'sm', link: false })).join('');
    return '<div class="prrow' + (selected ? ' selected' : '') + '">' + ui.prPill(p) + '<span class="pr-title">' + esc(p.title) + '</span><span class="pr-num">#' + p.number + '</span>' +
      '<span class="pr-sub"><span>' + esc(p.repo) + '</span><span>' + esc(p.author) + '</span>' + ui.ci(p.ci) + ui.review(p) + tabs + '</span></div>';
  }

  /* ---------- actions ---------- */
  function findItem(kind, id) { return kind === 'jira' ? S.jiraByKey(id) : S.prById(id); }
  function itemLabel(kind, item) { return kind === 'jira' ? item.key : 'PR #' + item.number; }
  function patchItem(kind, id, fn) {
    store.update((s) => {
      const it = kind === 'jira' ? s.jira.find((x) => x.key === id) : s.prs.find((x) => x.id === id);
      if (it) fn(it, s);
    });
  }

  T.act.extLink = function (form) {
    const kind = form.dataset.kind;
    const id = form.dataset.id;
    const topicId = new FormData(form).get('topic');
    if (!topicId) { form.querySelector('select').focus(); return; }
    const item = findItem(kind, id);
    patchItem(kind, id, (it) => { if (it.topicIds.indexOf(topicId) === -1) it.topicIds.push(topicId); });
    T.toast('Linked ' + itemLabel(kind, item) + ' to ' + S.topicById(topicId).name, { href: ui.topicHref(topicId), linkLabel: 'Open topic' });
  };
  T.act.extUnlink = function (el) {
    const kind = el.dataset.kind;
    const id = el.dataset.id;
    const topicId = el.dataset.topic;
    const item = findItem(kind, id);
    patchItem(kind, id, (it) => { it.topicIds = it.topicIds.filter((x) => x !== topicId); });
    T.toast('Unlinked ' + itemLabel(kind, item) + '. It stays in ' + (kind === 'jira' ? 'Jira' : 'GitHub') + '.', {
      undo: () => patchItem(kind, id, (it) => { if (it.topicIds.indexOf(topicId) === -1) it.topicIds.push(topicId); })
    });
  };
  T.act.extCreateTopic = function (el) {
    const kind = el.dataset.kind;
    const id = el.dataset.id;
    const item = findItem(kind, id);
    const used = T.state().topics.map((t) => t.color);
    const color = Object.keys(T.COLORS).find((c) => used.indexOf(c) === -1) || 'lavender';
    const planned = T.state().statuses.find((s) => s.cat === 'planned') || T.state().statuses[0];
    const tid = T.util.uid('t');
    store.update((s) => {
      s.topics.push({ id: tid, name: item.title, color: color, statusId: planned.id, goal: '', leftOff: 'Created from ' + itemLabel(kind, item) + '.', next: '' });
      const it = kind === 'jira' ? s.jira.find((x) => x.key === id) : s.prs.find((x) => x.id === id);
      it.topicIds.push(tid);
    });
    T.toast('Topic created and linked', { href: ui.topicHref(tid), linkLabel: 'Open topic' });
  };
  T.act.extSim = function (el) {
    const src = el.dataset.src;
    store.update((s) => { s.sim[src] = el.value; });
  };
  T.act.extReconnect = function (el) {
    store.update((s) => { s.sim[el.dataset.src] = 'fresh'; });
    T.toast(NAMES[el.dataset.src] + ' reconnected (demo). No password or token was used.');
  };
  T.act.extRefresh = function (el) {
    const src = el.dataset.src;
    const st = simState(src);
    if (st === 'offline' || st === 'nocache') { T.toast('Offline. Thread will refresh when the connection is back.'); return; }
    if (st === 'expired') { T.toast('Access expired. Reconnect to refresh.'); return; }
    if (st === 'ratelimit') { T.toast('Rate limited. Next refresh in about 2 minutes.'); return; }
    store.update((s) => { s.sim[src] = 'refreshing'; });
    setTimeout(() => store.update((s) => { if (s.sim[src] === 'refreshing') { s.sim[src] = 'fresh'; s.sim[src + 'At'] = s.sim.clock || '11:20'; } }), 1400);
  };

  T.ext = {
    SIM_OPTIONS: SIM_OPTIONS, sourceState: sourceState, simControl: simControl, freshLine: freshLine,
    jiraDetail: jiraDetail, prDetail: prDetail, jiraRow: jiraRow, prListRow: prListRow, links: links
  };
})();
