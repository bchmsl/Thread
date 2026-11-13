/* Shared components: shell, Task rows, status, Topic identity, external rows,
   external detail and source-state banners. Every Task, Topic, PR and Jira
   ticket renders through one function here so they look the same everywhere. */
(function () {
  'use strict';
  const T = window.Thread;
  const { html, raw, icon, glyph, Q, ui } = T;
  const V = T.views;
  const C = T.c = {};

  /* ------------------------------------------------------------ identity */
  C.tc = (topic) => raw('--tc:' + Q.topicColor(topic));

  C.status = (statusId) => {
    const s = Q.status(statusId);
    return html`<span class="status cat-${s.category}">${glyph(s.category)}<span>${s.name}</span></span>`;
  };

  C.topicChip = (topicId, opts) => {
    opts = opts || {};
    const t = topicId ? Q.topic(topicId) : null;
    if (!t) {
      if (opts.inbox) return html`<span class="chip-topic is-inbox">${icon('inbox', 'icon-sm')}<span>Inbox</span></span>`;
      return html`<span class="chip-topic is-standalone"><span class="thread"></span><span>No Topic</span></span>`;
    }
    if (opts.plain) return html`<span class="chip-topic"><span class="thread" style="${C.tc(t)}"></span><span>${t.name}</span></span>`;
    return html`<a class="chip-topic" href="#/topic/${t.id}"><span class="thread" style="${C.tc(t)}"></span><span>${t.name}</span></a>`;
  };

  C.meter = (topicId) => {
    const p = Q.progress(topicId);
    const pct = p.total ? Math.round((p.done / p.total) * 100) : 0;
    return html`<span class="meter" title="Personal Tasks completed"><span class="meter-bar" aria-hidden="true"><i style="width:${pct}%"></i></span><span>${p.done} of ${p.total} tasks</span></span>`;
  };

  /* ------------------------------------------------------------ Task row */
  C.taskRow = (t, opts) => {
    opts = opts || {};
    const topic = t.topicId ? Q.topic(t.topicId) : null;
    const n = T.now();
    const overdue = !t.done && t.deadline && t.deadline < n;
    const rems = Q.remindersFor('task', t.id).filter((r) => r.at >= T.today());
    const meta = [];
    if (!opts.hideTopic) meta.push(C.topicChip(t.topicId, { inbox: t.inbox }));
    if (t.deadline) {
      meta.push(html`<span class="meta-item ${overdue ? 'is-danger' : ''}">${icon('flag')}<span>${overdue ? 'Overdue, was due ' : 'Due '}${T.fmtWhen(t.deadline)}</span></span>`);
    }
    if (t.planned && (opts.showPlanned || t.planned !== T.today())) {
      meta.push(html`<span class="meta-item">${icon('calendar')}<span>Planned ${T.fmtDay(t.planned)}</span></span>`);
    }
    if (rems.length) meta.push(html`<span class="meta-item">${icon('bell')}<span>${T.fmtWhen(rems[0].at)}</span></span>`);
    if (t.done && t.completedAt) meta.push(html`<span class="meta-item">${icon('check')}<span>Completed ${T.fmtWhen(t.completedAt)}</span></span>`);
    const side = opts.side ? opts.side(t) : '';
    return html`<div class="task-row ${t.done ? 'is-done' : ''}" style="${C.tc(topic)}">
      ${topic ? html`<span class="row-thread" aria-hidden="true"></span>` : ''}
      <input class="check" type="checkbox" ${t.done ? 'checked' : ''} data-change="toggle-task" data-id="${t.id}" data-fk="chk-${t.id}" aria-label="${t.done ? 'Mark as not done' : 'Complete'}: ${t.title}">
      <div class="task-main">
        <button class="task-title" type="button" data-action="open-task" data-id="${t.id}" data-fk="tt-${t.id}">${t.title}</button>
        ${meta.length ? html`<div class="task-meta">${meta}</div>` : ''}
      </div>
      <div class="task-side">${side}</div>
    </div>`;
  };

  /* ---------------------------------------------------------- Jira and PR */
  C.jiraStatus = (j) => html`<span class="jira-status js-${j.cat}">${j.status}</span>`;
  C.typeIcon = (type) => ({ Bug: 'bug', Story: 'story' })[type] || 'task';

  C.ciInd = (pr) => {
    const m = { pass: ['ciPass', 'Checks passed', 'ind-pass'], fail: ['ciFail', 'Checks failed', 'ind-fail'], run: ['ciRun', 'Checks running', 'ind-run'] }[pr.ci];
    return html`<span class="ind ${m[2]}">${icon(m[0])}<span>${m[1]}</span></span>`;
  };
  C.reviewInd = (pr) => {
    const m = {
      approved: ['approve', 'Approved', 'ind-approved'], changes: ['changes', 'Changes requested', 'ind-changes'],
      pending: ['eye', 'No review yet', 'ind-pending'], requested: ['eye', 'Your review requested', 'ind-requested']
    }[pr.review];
    return html`<span class="ind ${m[2]}">${icon(m[0])}<span>${m[1]}</span></span>`;
  };
  C.prState = (pr) => pr.draft
    ? html`<span class="pr-state is-draft">${icon('pr')}Draft</span>`
    : html`<span class="pr-state">${icon('pr')}Open</span>`;

  C.prRow = (id, opts) => {
    opts = opts || {};
    const pr = Q.pr(id);
    const author = Q.person(pr.author);
    return html`<a class="pr-row" href="${opts.href || '#/prs/' + id}" ${opts.current ? raw('aria-current="true"') : ''} data-fk="pr-${id}">
      <span class="pr-l1">${C.prState(pr)}<span class="pr-title">${pr.title}</span><span class="pr-num">#${pr.number}</span></span>
      <span class="pr-l2"><span class="ind">${icon('repo')}<span>${pr.repo}</span></span><span>${pr.author === 'me' ? 'You' : author.name}</span>${C.ciInd(pr)}${C.reviewInd(pr)}${opts.topics !== false ? C.linkedThreads(Q.prTopics(id)) : ''}</span>
    </a>`;
  };

  C.linkedThreads = (ids) => {
    if (!ids.length) return '';
    return html`<span class="ind">${ids.map((id) => { const t = Q.topic(id); return html`<span class="thread" style="${C.tc(t)}"></span>`; })}<span>${ids.map((id) => Q.topic(id).name).join(', ')}</span></span>`;
  };

  C.jiraRow = (key, opts) => {
    opts = opts || {};
    const j = Q.jira(key);
    const cached = Q.jiraCached(key);
    return html`<a class="jira-row" href="${opts.href || '#/jira/' + key}" ${opts.current ? raw('aria-current="true"') : ''} data-fk="jr-${key}">
      <span class="j-key">${key}</span>
      <span class="j-sum">${j.summary}</span>
      ${C.jiraStatus(j)}
      <span class="j-meta"><span class="ind">${icon(C.typeIcon(j.type))}<span>${j.type}</span></span><span>${j.priority} priority</span><span>Updated ${T.fmtWhen(j.updated)}</span>${C.linkedThreads(Q.jiraTopics(key))}${cached ? '' : html`<span class="ind">${icon('cloud')}<span>Not on this device yet</span></span>`}</span>
    </a>`;
  };

  /* --------------------------------------------------- source state banner */
  const SRC_NAME = { jira: 'Jira', github: 'GitHub' };
  C.sourceBanner = (source, ctx) => {
    const st = Q.source(source);
    const name = SRC_NAME[source];
    const listAt = state().cache.lists[source === 'jira' ? 'jira' : 'github'];
    if (st === 'ok') return '';
    if (st === 'disconnected') {
      return html`<div class="banner">${icon('unlink')}<div class="banner-body"><strong>${name} is not connected.</strong><p>Links to Topics are kept. Connect to see ${source === 'jira' ? 'ticket' : 'pull request'} details.</p></div><div class="banner-actions"><a class="btn btn-sm" href="#/settings?sec=connections">Open Settings</a></div></div>`;
    }
    if (st === 'offline') {
      return html`<div class="banner banner-warn">${icon('offline')}<div class="banner-body"><strong>Offline.</strong><p>${listAt ? html`Showing what is saved on this device, last updated ${T.ago(listAt)}.` : 'Nothing from ' + name + ' is saved on this device yet.'} Refresh works again when you reconnect.</p></div></div>`;
    }
    if (st === 'expired') {
      return html`<div class="banner banner-danger">${icon('lock')}<div class="banner-body"><strong>${name} access expired.</strong><p>Saved content stays visible but will not update until you reconnect.</p></div><div class="banner-actions"><button class="btn btn-sm" type="button" data-action="reconnect" data-source="${source}">Reconnect (demo)</button></div></div>`;
    }
    if (st === 'ratelimited') {
      const until = state().sim[source + 'RetryAt'];
      return html`<div class="banner banner-warn">${icon('clock')}<div class="banner-body"><strong>${name} rate limit reached.</strong><p>Showing saved content. Refresh is paused until ${until ? T.fmtTime(until) : 'later'}.</p></div><div class="banner-actions"><button class="btn btn-sm" type="button" disabled>Retry at ${until ? T.fmtTime(until) : 'later'}</button></div></div>`;
    }
    return '';
  };
  const state = () => T.state;

  /* Cache freshness line for lists and details */
  C.cacheLine = (source, key) => {
    const id = source + ':' + (key || 'list');
    const st = Q.source(source);
    let at;
    if (key) at = (source === 'jira' ? state().cache.jira[key] : state().cache.prs[key]);
    at = key ? (at && at.at) : state().cache.lists[source === 'jira' ? 'jira' : 'github'];
    if (ui.refreshing[id]) return html`<span class="cache-line is-refreshing" role="status">${icon('refresh')}<span>Showing saved copy from ${T.fmtTime(at)}. Refreshing.</span></span>`;
    if (st === 'ok' && ui.fresh[id]) return html`<span class="cache-line" role="status">${icon('cloudCheck')}<span>Up to date, refreshed ${T.ago(at)}</span></span>`;
    if (!at) return '';
    const warn = st !== 'ok';
    return html`<span class="cache-line ${warn ? 'is-warn' : ''}">${icon(warn ? 'alert' : 'device')}<span>Saved on this device ${T.ago(at)}</span></span>`;
  };

  /* ------------------------------------------------------ external detail */
  function descBlocks(blocks) {
    return blocks.map((b) => {
      if (b.t === 'p') return html`<p>${inlineCode(b.text)}</p>`;
      if (b.t === 'h') return html`<h4>${b.text}</h4>`;
      if (b.t === 'ul') return html`<ul>${b.items.map((i) => html`<li>${inlineCode(i)}</li>`)}</ul>`;
      if (b.t === 'ol') return html`<ol>${b.items.map((i) => html`<li>${inlineCode(i)}</li>`)}</ol>`;
      if (b.t === 'code') return html`<pre><code>${b.text}</code></pre>`;
      return html`<div class="x-unsupported">${icon('alert', 'icon-sm')}<span>${b.text}</span></div>`;
    });
  }
  /* Escape first, then turn `text` into code. Content never becomes markup. */
  function inlineCode(text) { return raw(T.esc(text).replace(/`([^`]+)`/g, '<code>$1</code>')); }

  function linkedTopicsSection(source, key) {
    const ids = source === 'jira' ? Q.jiraTopics(key) : Q.prTopics(key);
    return html`<section class="x-section" aria-labelledby="lt-${key}">
      <h3 id="lt-${key}">Linked Topics <span class="count">${ids.length}</span></h3>
      ${ids.length ? html`<div class="linked-topics">${ids.map((id) => { const t = Q.topic(id); return html`<span class="linked-topic"><span class="thread" style="${C.tc(t)}"></span><a href="#/topic/${id}">${t.name}</a><button class="btn btn-ghost btn-sm btn-icon" type="button" data-action="unlink" data-source="${source}" data-key="${key}" data-topic="${id}" data-fk="ul-${key}-${id}" aria-label="Unlink from ${t.name}">${icon('x', 'icon-sm')}</button></span>`; })}</div>`
        : html`<p class="faint">Not linked to a Topic yet.</p>`}
      <div><button class="btn btn-primary" type="button" data-action="pick-topics" data-source="${source}" data-key="${key}" data-fk="lk-${key}">${icon('link')}Link to Topic</button></div>
    </section>`;
  }

  function detailTop(source, opts) {
    return html`<div class="x-top">
      <span class="x-source">${icon(source === 'jira' ? 'ticket' : 'pr')}<span>${source === 'jira' ? 'Jira' : 'GitHub'}</span></span>
      <span class="readonly-tag">${icon('lock')}<span>Read-only</span></span>
      ${opts.close ? html`<a class="btn btn-ghost btn-sm btn-icon" href="${opts.close}" aria-label="Close details">${icon('x')}</a>` : ''}
    </div>`;
  }

  function unavailable(source, key, opts) {
    const st = Q.source(source);
    const id = source + ':' + key;
    const name = SRC_NAME[source];
    let body;
    if (st === 'ok') {
      if (!ui.loading[id]) setTimeout(() => T.fetchItem(source, key), 0);
      body = html`<div class="empty" role="status">${icon('refresh', 'icon-lg')}<h3>Loading ${key} from ${name}</h3><p>This item is not saved on this device yet.</p></div>`;
    } else if (st === 'offline') {
      body = html`<div class="empty">${icon('offline', 'icon-lg')}<h3>Not available offline</h3><p>${key} was never opened on this device, so there is no saved copy. It loads when you reconnect.</p></div>`;
    } else if (st === 'expired') {
      body = html`<div class="empty">${icon('lock', 'icon-lg')}<h3>${name} access expired</h3><p>Reconnect to load ${key}.</p><button class="btn" type="button" data-action="reconnect" data-source="${source}">Reconnect (demo)</button></div>`;
    } else if (st === 'ratelimited') {
      body = html`<div class="empty">${icon('clock', 'icon-lg')}<h3>Rate limit reached</h3><p>${key} is not saved on this device. Try again after ${T.fmtTime(state().sim[source + 'RetryAt'])}.</p></div>`;
    } else {
      body = html`<div class="empty">${icon('unlink', 'icon-lg')}<h3>${name} is not connected</h3><p>The link to ${key} is kept. Connect ${name} to see its details.</p><a class="btn" href="#/settings?sec=connections">Open Settings</a></div>`;
    }
    return html`<article class="xdetail" aria-label="${key}">${detailTop(source, opts)}<p class="x-key">${source === 'jira' ? key : (Q.pr(key) ? Q.pr(key).repo + ' #' + Q.pr(key).number : key)}</p>${body}${linkedTopicsSection(source, key)}</article>`;
  }

  C.jiraDetail = (key, opts) => {
    opts = opts || {};
    const j = Q.jira(key);
    if (!j) return html`<div class="panel-empty">${icon('ticket', 'icon-lg')}<p>No ticket ${key}.</p></div>`;
    if (!Q.jiraCached(key)) return unavailable('jira', key, opts);
    const p = (k) => Q.person(k).name + (k === 'me' ? ' (you)' : '');
    return html`<article class="xdetail" aria-labelledby="xt-${key}">
      ${detailTop('jira', opts)}
      ${C.sourceBanner('jira')}
      <div>
        <p class="x-key">${key}, ${j.type}</p>
        <h2 class="x-title" id="xt-${key}">${j.summary}</h2>
      </div>
      <div class="x-statusrow">${C.jiraStatus(j)}<span class="faint">${j.priority} priority</span>${C.cacheLine('jira', key)}</div>
      <dl class="x-fields">
        <dt>Assignee</dt><dd>${p(j.assignee)}</dd>
        <dt>Reporter</dt><dd>${p(j.reporter)}</dd>
        <dt>Sprint</dt><dd>${j.sprint}</dd>
        <dt>Labels</dt><dd>${j.labels.join(', ')}</dd>
        <dt>Updated</dt><dd>${T.fmtWhen(j.updated)}</dd>
      </dl>
      ${linkedTopicsSection('jira', key)}
      <section class="x-section"><h3>Description</h3><div class="x-desc">${descBlocks(j.description)}</div></section>
      <section class="x-section"><h3>Comments <span class="count">${j.comments.length}</span></h3>
        ${j.comments.length ? j.comments.map((c) => html`<div class="comment"><div class="comment-head"><span class="avatar" aria-hidden="true">${Q.person(c.by).initials}</span><b>${Q.person(c.by).name}</b><span>${T.fmtWhen(c.at)}</span></div><p>${c.text}</p></div>`) : html`<p class="faint">No comments.</p>`}
        <p class="help">Comments are read-only in Thread v1.</p>
      </section>
      ${j.related.length ? html`<section class="x-section"><h3>Related tickets</h3><div class="related-list">${j.related.map((r) => html`<a href="${opts.relHref ? opts.relHref(r.key) : '#/jira/' + r.key}">${icon('ticket', 'icon-sm')}<span><b>${r.key}</b> ${r.rel}${Q.jiraCached(r.key) ? html`, ${Q.jira(r.key).summary}` : ''}</span></a>`)}</div></section>` : ''}
      <div><button class="btn btn-ghost btn-sm" type="button" data-action="open-external" data-what="Jira">${icon('external', 'icon-sm')}Open in Jira</button></div>
    </article>`;
  };

  C.prDetail = (id, opts) => {
    opts = opts || {};
    const pr = Q.pr(id);
    if (!pr) return html`<div class="panel-empty">${icon('pr', 'icon-lg')}<p>No pull request ${id}.</p></div>`;
    if (!Q.prCached(id)) return unavailable('github', id, opts);
    const stateInd = (s) => ({ pass: ['ciPass', 'ind-pass', 'Passed'], fail: ['ciFail', 'ind-fail', 'Failed'], run: ['ciRun', 'ind-run', 'Running'] })[s];
    const revInd = (s) => ({ approved: ['approve', 'ind-approved', 'Approved'], changes: ['changes', 'ind-changes', 'Requested changes'], pending: ['eye', 'ind-pending', 'Not reviewed yet'], requested: ['eye', 'ind-requested', 'Review requested'] })[s];
    return html`<article class="xdetail" aria-labelledby="xt-${id}">
      ${detailTop('github', opts)}
      ${C.sourceBanner('github')}
      <div>
        <p class="x-key">${pr.repo} #${pr.number}</p>
        <h2 class="x-title" id="xt-${id}">${pr.title}</h2>
      </div>
      <div class="x-statusrow">${C.prState(pr)}${C.ciInd(pr)}${C.reviewInd(pr)}</div>
      <div>${C.cacheLine('github', id)}</div>
      <dl class="x-fields">
        <dt>Author</dt><dd>${pr.author === 'me' ? 'You' : Q.person(pr.author).name}</dd>
        <dt>Branch</dt><dd>${pr.branch} into ${pr.base}</dd>
        <dt>Files</dt><dd>${pr.files} changed</dd>
        <dt>Updated</dt><dd>${T.fmtWhen(pr.updated)}</dd>
      </dl>
      ${linkedTopicsSection('github', id)}
      <section class="x-section"><h3>Description</h3><div class="x-desc">${descBlocks(pr.description)}</div></section>
      <section class="x-section"><h3>Checks</h3><ul class="check-list">${pr.checks.map((c) => { const m = stateInd(c.state); return html`<li class="check-item"><span class="ind ${m[1]}">${icon(m[0])}</span><span>${c.name}</span><span class="faint">${m[2]}</span></li>`; })}</ul></section>
      <section class="x-section"><h3>Reviews</h3>${pr.reviewers.length ? html`<ul class="check-list">${pr.reviewers.map((r) => { const m = revInd(r.state); return html`<li class="check-item"><span class="avatar" aria-hidden="true">${Q.person(r.who).initials}</span><span>${r.who === 'me' ? 'You' : Q.person(r.who).name}</span><span class="ind ${m[1]}">${icon(m[0])}<span>${m[2]}</span></span></li>`; })}</ul>` : html`<p class="faint">No reviewers yet.</p>`}</section>
      <section class="x-section"><h3>Comments <span class="count">${pr.comments.length}</span></h3>
        ${pr.comments.length ? pr.comments.map((c) => html`<div class="comment"><div class="comment-head"><span class="avatar" aria-hidden="true">${Q.person(c.by).initials}</span><b>${Q.person(c.by).name}</b><span>${T.fmtWhen(c.at)}</span></div><p>${c.text}</p></div>`) : html`<p class="faint">No comments.</p>`}
        <p class="help">Reviews and comments are read-only in Thread v1.</p>
      </section>
      <div><button class="btn btn-ghost btn-sm" type="button" data-action="open-external" data-what="GitHub">${icon('external', 'icon-sm')}Open on GitHub</button></div>
    </article>`;
  };

  /* ----------------------------------------------------------- reminders */
  C.remTarget = (r) => {
    const tg = Q.targetOf(r);
    if (!tg) return '';
    const topic = Q.targetTopic(r);
    const ico = { task: 'check', note: 'note', topic: 'topics' }[r.target.type];
    const label = r.target.type === 'topic' ? tg.name : tg.title;
    const act = r.target.type === 'task' ? html`<button class="btn-link" type="button" data-action="open-task" data-id="${tg.id}">${label}</button>`
      : r.target.type === 'note' ? html`<a href="#/notes/${tg.id}">${label}</a>` : html`<a href="#/topic/${tg.id}">${label}</a>`;
    return html`<span class="rem-target">${icon(ico, 'icon-sm')}${act}${topic && r.target.type !== 'topic' ? html`<span class="thread" style="${C.tc(topic)}"></span><span>${topic.name}</span>` : ''}</span>`;
  };

  C.remRow = (r) => {
    const n = T.now();
    const past = r.at < n;
    const mins = T.minutesBetween(n, r.at);
    const due = !past && mins <= 60;
    const rel = past ? 'passed' : (mins < 60 ? 'in ' + mins + ' min' : (r.at.slice(0, 10) === T.today() ? 'in ' + Math.round(mins / 60) + ' h' : T.fmtDay(r.at.slice(0, 10))));
    return html`<li class="rem ${past ? 'is-past' : ''} ${due ? 'is-due' : ''}">
      <span class="rem-time">${T.fmtTime(r.at)}<small>${rel}</small></span>
      <div class="rem-body">
        <span class="rem-text">${r.text || 'Reminder'}</span>
        ${C.remTarget(r)}
        <span class="rem-actions">
          ${past ? html`<button class="btn btn-ghost btn-sm" type="button" data-action="dismiss-rem" data-id="${r.id}" data-fk="rd-${r.id}">Dismiss</button>` : ''}
          <button class="btn btn-ghost btn-sm" type="button" data-action="edit-rem" data-id="${r.id}" data-fk="re-${r.id}">${past ? 'Reschedule' : 'Edit'}</button>
        </span>
      </div>
    </li>`;
  };

  /* ---------------------------------------------------------------- shell */
  const NAV = [
    ['today', 'Today', 'today'], ['inbox', 'Inbox', 'inbox'], ['topics', 'Topics', 'topics'], ['notes', 'Notes', 'note'],
    'sep', ['jira', 'Jira', 'ticket'], ['prs', 'Pull requests', 'pr'], 'sep', ['trash', 'Trash', 'trash'], ['settings', 'Settings', 'settings']
  ];
  const SECTION_OF = { topic: 'topics', board: 'topics' };
  const TITLES = { today: 'Today', inbox: 'Inbox', topics: 'Topics', topic: 'Topic', notes: 'Notes', jira: 'Jira', prs: 'Pull requests', trash: 'Trash', settings: 'Settings' };

  C.pendingSync = () => T.state.notes.filter((n) => { const s = T.state.noteSync[n.id]; return s && /^(local|sync-failed|saving|syncing|local-failed)$/.test(s.state); }).length;

  V.sidebar = (r) => {
    const cur = SECTION_OF[r.name] || r.name;
    const s = T.state;
    const inboxN = Q.inbox().length;
    const reviewN = Q.reviewPrs().length;
    const trashN = s.trash.length;
    const pend = C.pendingSync();
    const f = s.focus;
    const running = f.status === 'running' || f.status === 'paused';
    return html`
      <div class="brand"><svg viewBox="0 0 24 24" aria-hidden="true"><use href="#i-logo"></use></svg><span>Thread</span><span class="demo-tag" title="Prototype with synthetic data">Demo</span></div>
      <div class="side-actions">
        <button class="search-trigger" type="button" data-action="open-search" id="side-search">${icon('search', 'icon-sm')}<span>Search</span><kbd aria-hidden="true">⌘K</kbd></button>
        <button class="btn btn-icon" type="button" data-action="open-capture" id="side-capture" aria-label="Quick capture" title="Quick capture (C)">${icon('plus')}</button>
      </div>
      <nav class="nav" aria-label="Main">
        ${NAV.map((it) => it === 'sep' ? html`<div class="nav-sep" role="separator"></div>` : html`<a class="nav-item" href="#/${it[0]}" ${cur === it[0] ? raw('aria-current="page"') : ''} data-fk="nav-${it[0]}">${icon(it[2])}<span>${it[1]}</span>${
          it[0] === 'inbox' && inboxN ? html`<span class="nav-count is-attention" aria-label="${inboxN} to sort">${inboxN}</span>` :
          it[0] === 'prs' && reviewN ? html`<span class="nav-count" aria-label="${reviewN} review requests">${reviewN}</span>` :
          it[0] === 'trash' && trashN ? html`<span class="nav-count" aria-label="${trashN} items">${trashN}</span>` : ''}</a>`)}
      </nav>
      <div class="side-foot">
        <button class="focus-btn ${f.status === 'running' ? 'is-running' : ''}" type="button" data-action="toggle-focus" id="focus-btn" aria-haspopup="dialog" aria-expanded="${!!T.popoverOpen}">
          ${icon('timer')}<span>Focus</span>${running ? html`<span class="focus-time" id="focus-time">--:--</span>` : html`<span class="concept-tag" style="margin-left:auto">Concept</span>`}
        </button>
        <div class="net-line ${s.sim.online ? '' : 'is-offline'}" role="status">
          <span class="dot" aria-hidden="true"></span>
          <span>${s.sim.online ? (pend ? pend + ' note' + (pend > 1 ? 's' : '') + ' syncing' : 'Online, all synced') : 'Offline' + (pend ? ', ' + pend + ' change' + (pend > 1 ? 's' : '') + ' on this device' : '')}</span>
        </div>
        <button class="demo-btn" type="button" data-action="open-demo" id="demo-btn">${icon('flask', 'icon-sm')}<span>Demo controls</span></button>
      </div>`;
  };

  V.topbar = (r) => {
    let title = TITLES[r.name] || 'Thread';
    if (r.name === 'topic' && Q.topic(r.id)) title = Q.topic(r.id).name;
    return html`
      <button class="btn btn-ghost btn-icon" type="button" id="nav-toggle" data-action="toggle-nav" aria-controls="sidebar" aria-expanded="${ui.navOpen}" aria-label="Open navigation">${icon('menu')}</button>
      <span class="topbar-title">${title}</span>
      <button class="btn btn-ghost btn-icon" type="button" data-action="open-search" id="top-search" aria-label="Search">${icon('search')}</button>
      <button class="btn btn-ghost btn-icon" type="button" data-action="open-capture" id="top-capture" aria-label="Quick capture">${icon('plus')}</button>`;
  };

  V.globalBanners = () => {
    const s = T.state;
    const out = [];
    if (!s.sim.online) out.push(html`<div class="banner banner-warn">${icon('offline')}<div class="banner-body"><strong>You are offline.</strong><p>Everything you change is saved on this device and syncs when you reconnect.</p></div><span class="sim-tag">Simulated</span></div>`);
    if (!T.storageOk) out.push(html`<div class="banner">${icon('alert')}<div class="banner-body"><strong>Browser storage is blocked.</strong><p>The demo still works, but changes are lost when you close this tab.</p></div></div>`);
    return out;
  };

  V.notfound = () => html`<div class="page"><div class="empty">${icon('search', 'icon-lg')}<h3>This page does not exist</h3><p>The link may be from an older prototype.</p><a class="btn" href="#/today">Go to Today</a></div></div>`;
})();
