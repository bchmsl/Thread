/* Thread v2 prototype shell: chrome, shared components, overlays and delegated events.
   Views register in Thread.views[page] with render() and optional mount(root).
   Controls name their handler with data-act (click), data-change, data-input or data-submit. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const TM = T.time;
  const store = T.store;
  const icon = T.icon;

  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ESC[c]);
  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

  T.esc = esc;
  T.$ = $;
  T.$$ = $$;
  T.views = T.views || {};
  T.act = T.act || {};
  T.param = (n) => new URLSearchParams(location.search).get(n);
  T.narrow = () => window.matchMedia('(max-width: 720px)').matches;
  T.isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || '');
  T.modKey = T.isMac ? '⌘K' : 'Ctrl K';
  T.nowStamp = () => TM.toStamp(TM.now());
  T.go = (href) => { location.href = href; };

  /* ---------- shared components ---------- */
  const ui = (T.ui = {});

  ui.topicHref = (id) => 'topic.html?id=' + encodeURIComponent(id);

  ui.ttab = function (t, o) {
    o = o || {};
    if (!t) return '';
    const cls = 'ttab' + (o.size ? ' ' + o.size : '') + (o.cls ? ' ' + o.cls : '');
    const style = ' style="--c:' + S.topicColor(t) + '"';
    const inner = '<span class="tname">' + esc(t.name) + '</span>' + (o.count != null ? '<span class="tcount">' + o.count + '</span>' : '');
    if (o.link === false) return '<span class="' + cls + '"' + style + '>' + inner + '</span>';
    return '<a class="' + cls + '"' + style + ' href="' + ui.topicHref(t.id) + '"' + (o.current ? ' aria-current="page"' : '') + '>' + inner + '</a>';
  };

  /* A Topic tab with its own unlink button. The name and the button are separate controls. */
  ui.ttabX = function (t, act, data, label) {
    return '<span class="ttab md has-x" style="--c:' + S.topicColor(t) + '"><a href="' + ui.topicHref(t.id) + '">' + esc(t.name) + '</a>' +
      '<button type="button" class="x" data-act="' + act + '" ' + data + ' aria-label="' + esc(label) + '">' + icon('x') + '</button></span>';
  };

  ui.status = function (st, o) {
    o = o || {};
    if (!st) return '';
    return '<span class="status' + (o.lg ? ' lg' : '') + '" data-cat="' + st.cat + '"><span class="tile">' + T.glyph(st.cat) + '</span>' +
      (o.noName ? '<span class="vh">' + esc(st.name) + '</span>' : esc(st.name)) + '</span>';
  };

  ui.pips = function (p, color) {
    if (!p.total) return '<span class="prog">No tasks yet</span>';
    let pips = '';
    if (p.total <= 10) {
      for (let i = 0; i < p.total; i++) pips += '<i class="pip' + (i < p.done ? ' on' : '') + '"></i>';
      pips = '<span class="pips" aria-hidden="true"' + (color ? ' style="--c:' + color + '"' : '') + '>' + pips + '</span>';
    } else {
      pips = '<span class="pipbar" aria-hidden="true"' + (color ? ' style="--c:' + color + '"' : '') + '><i style="width:' + Math.round((p.done / p.total) * 100) + '%"></i></span>';
    }
    return '<span class="prog">' + pips + '<span>' + p.done + ' of ' + p.total + ' done</span></span>';
  };

  ui.empty = function (title, text, action, tag) {
    tag = tag || 'h2';
    return '<div class="empty"><div class="ghost-tabs" aria-hidden="true"><i></i><i></i><i></i></div><' + tag + '>' + esc(title) + '</' + tag + '>' +
      (text ? '<p>' + esc(text) + '</p>' : '') + (action || '') + '</div>';
  };

  ui.keycap = function (label, key, attrs) {
    return '<button type="button" class="btn-key" ' + (attrs || '') + '>' + icon('plus') + '<span>' + esc(label) + '</span>' + (key ? '<kbd aria-hidden="true">' + esc(key) + '</kbd>' : '') + '</button>';
  };

  /* Dashed control that changes simulated state only. */
  ui.sim = function (label, change, options, value, extra) {
    return '<label class="sim">' + icon('flask') + '<span>' + esc(label) + '</span><select class="select select-sm" data-change="' + change + '" ' + (extra || '') + '>' +
      options.map((o) => '<option value="' + o[0] + '"' + (o[0] === value ? ' selected' : '') + '>' + esc(o[1]) + '</option>').join('') + '</select></label>';
  };

  ui.deadlineLabel = function (k) {
    if (!k.deadline) return '';
    const hasTime = k.deadline.length > 10;
    return TM.fmtDay(k.deadline) + (hasTime ? ' ' + TM.fmtTime(k.deadline) : '');
  };

  ui.taskMeta = function (k, o) {
    o = o || {};
    const out = [];
    const t = S.topicById(k.topicId);
    if (o.topic !== false) {
      if (t) out.push(ui.ttab(t, { size: 'sm' }));
      else if (k.inbox) out.push('<span class="m">' + icon('inbox') + 'Inbox</span>');
      else if (o.standalone) out.push('<span class="m">Standalone</span>');
    }
    if (k.planned && o.planned !== false) {
      out.push('<span class="m">' + icon('calendar') + '<span>Planned ' + esc(TM.fmtDay(k.planned)) + '</span></span>');
    }
    if (k.deadline) {
      const late = !k.done && S.taskTodayGroup(k) === 'overdue';
      out.push('<span class="m' + (late ? ' late' : ' soon') + '">' + icon('flag') + '<span>' + (late ? 'Overdue, due ' : 'Due ') + esc(ui.deadlineLabel(k)) + '</span></span>');
    }
    const rem = S.taskReminder(k.id);
    if (rem && o.reminder !== false) out.push('<span class="m">' + icon('bell') + '<span>' + esc(TM.fmtStamp(rem.at)) + '</span></span>');
    if (k.done && k.completedOn) out.push('<span class="m">' + icon('check') + 'Done ' + esc(TM.fmtDay(k.completedOn)) + '</span>');
    return out.length ? '<div class="trow-meta">' + out.join('') + '</div>' : '';
  };

  ui.taskRow = function (k, o) {
    o = o || {};
    return '<div class="trow' + (k.done ? ' is-done' : '') + '">' +
      '<label class="checkhit"><input type="checkbox" class="check" data-change="toggleTask" data-id="' + k.id + '"' + (k.done ? ' checked' : '') + ' aria-label="' + esc(k.title) + '"></label>' +
      '<div><button type="button" class="linkbtn trow-title" data-act="openTask" data-id="' + k.id + '">' + esc(k.title) + '</button>' + ui.taskMeta(k, o) + '</div>' +
      '<div class="trow-side">' + (o.side || '') + '</div></div>';
  };

  ui.ci = function (state) {
    const map = {
      passing: ['ok', 'circleCheck', 'CI passing'],
      failing: ['fail', 'circleX', 'CI failing'],
      running: ['run', 'clock', 'CI running']
    };
    const m = map[state] || ['wait', 'circleDot', 'No CI'];
    return '<span class="ind ' + m[0] + '">' + icon(m[1]) + m[2] + '</span>';
  };
  ui.review = function (pr) {
    const map = {
      approved: ['ok', 'check', 'Approved'],
      changes: ['changes', 'comment', 'Changes requested'],
      pending: ['wait', 'eye', pr.requested ? 'Your review requested' : 'Review pending'],
      none: ['wait', 'user', 'No reviewers yet']
    };
    const m = map[pr.review] || map.none;
    return '<span class="ind ' + m[0] + '">' + icon(m[1]) + m[2] + '</span>';
  };
  ui.prPill = (pr) => '<span class="pill-open' + (pr.draft ? ' draft' : '') + '">' + icon('pr') + (pr.draft ? 'Draft' : 'Open') + '</span>';

  ui.prRow = function (pr) {
    return '<div class="prrow">' + ui.prPill(pr) + '<span class="pr-title">' + esc(pr.title) + '</span><span class="pr-num">#' + pr.number + '</span>' +
      '<span class="pr-sub"><span>' + esc(pr.repo) + '</span><span>' + esc(pr.author) + '</span>' + ui.ci(pr.ci) + ui.review(pr) + '</span></div>';
  };

  /* ---------- toasts ---------- */
  T.toast = function (msg, o) {
    o = o || {};
    const host = $('#toasts');
    if (!host) return;
    const el = document.createElement('div');
    el.className = 'toast';
    el.innerHTML = '<span>' + esc(msg) + '</span>' +
      (o.href ? '<a href="' + esc(o.href) + '">' + esc(o.linkLabel || 'Open') + '</a>' : '') +
      (o.undo ? '<button type="button">Undo</button>' : '');
    host.appendChild(el);
    const kill = () => { if (el.parentNode) el.parentNode.removeChild(el); };
    if (o.undo) el.querySelector('button').addEventListener('click', () => { o.undo(); kill(); });
    setTimeout(kill, o.undo ? 7000 : 4200);
  };

  /* ---------- trash helpers (app-owned objects only) ---------- */
  T.trash = function (kind, id) {
    const s = T.state();
    const lists = { task: 'tasks', note: 'notes', topic: 'topics' };
    const list = s[lists[kind]];
    const idx = list.findIndex((x) => x.id === id);
    if (idx < 0) return;
    const item = list[idx];
    let context = 'Standalone';
    if (kind === 'task') { const t = S.topicById(item.topicId); context = t ? 'From ' + t.name : (item.inbox ? 'From Inbox' : 'Standalone task'); }
    if (kind === 'note') {
      const names = item.topicIds.map((tid) => S.topicById(tid)).filter(Boolean).map((t) => t.name);
      context = names.length ? 'From ' + names.join(' and ') : (item.inbox ? 'From Inbox' : 'Standalone note');
    }
    if (kind === 'topic') {
      const p = S.topicProgress(item.id);
      context = 'Topic with ' + p.total + ' task' + (p.total === 1 ? '' : 's') + ' and ' + S.topicNotes(item.id).length + ' note' + (S.topicNotes(item.id).length === 1 ? '' : 's') + '. Links stay hidden until restored.';
    }
    const trashId = T.util.uid('tr');
    store.update((st) => {
      const l = st[lists[kind]];
      const i = l.findIndex((x) => x.id === id);
      const removed = l.splice(i, 1)[0];
      const rems = st.reminders.filter((r) => r.target.kind === kind && r.target.id === id);
      st.reminders = st.reminders.filter((r) => !(r.target.kind === kind && r.target.id === id));
      st.trash.unshift({ id: trashId, kind: kind, deletedAt: T.nowStamp(), context: context, item: removed, reminders: rems });
    });
    T.toast('Moved to Trash', { undo: () => T.restore(trashId, true) });
    return trashId;
  };

  T.restore = function (trashId, quiet) {
    const s = T.state();
    const entry = s.trash.find((x) => x.id === trashId);
    if (!entry) return;
    const lists = { task: 'tasks', note: 'notes', topic: 'topics' };
    store.update((st) => {
      st.trash = st.trash.filter((x) => x.id !== trashId);
      st[lists[entry.kind]].push(entry.item);
      (entry.reminders || []).forEach((r) => st.reminders.push(r));
    });
    if (quiet) return;
    let where = '';
    let href = '';
    if (entry.kind === 'task') { const t = S.topicById(entry.item.topicId); where = t ? t.name : (entry.item.inbox ? 'Inbox' : 'your tasks'); href = 'task.html?id=' + entry.item.id; }
    if (entry.kind === 'note') { const t = S.topicById(entry.item.topicIds[0]); where = t ? t.name : 'Notes'; href = 'notes.html?id=' + entry.item.id; }
    if (entry.kind === 'topic') { where = 'Topics'; href = ui.topicHref(entry.item.id); }
    T.toast('Restored to ' + where, { href: href, linkLabel: 'Open' });
  };

  /* ---------- overlays: modal dialogs ---------- */
  const overlays = {};
  T.dialog = function (id, o) {
    let d = document.getElementById(id);
    if (!d) {
      d = document.createElement('dialog');
      d.id = id;
      document.body.appendChild(d);
      d.addEventListener('close', () => {
        const rec = overlays[id];
        delete overlays[id];
        if (rec && rec.onClose) rec.onClose();
        if (!rec) return;
        if (rec.opener && document.contains(rec.opener)) { rec.opener.focus(); return; }
        restoreFocus(rec.openerKey);
        if (!document.activeElement || document.activeElement === document.body) { const m = $('#main'); if (m) m.focus({ preventScroll: true }); }
      });
      d.addEventListener('click', (e) => { if (e.target === d) d.close(); });
    }
    d.className = o.cls || '';
    if (o.label) d.setAttribute('aria-labelledby', o.label);
    const prev = overlays[id];
    overlays[id] = {
      el: d, render: o.render, onClose: o.onClose,
      opener: prev ? prev.opener : document.activeElement,
      openerKey: prev ? prev.openerKey : focusKey(document.activeElement)
    };
    d.innerHTML = o.render();
    if (!d.open) d.showModal();
    if (o.mount) o.mount(d);
    const f = o.focus ? $(o.focus, d) : null;
    if (f) f.focus();
    return d;
  };
  T.closeDialog = function (id) {
    const d = document.getElementById(id);
    if (d && d.open) d.close();
  };
  T.act.closeDlg = function (el) {
    const d = el.closest('dialog');
    if (d) d.close();
  };

  /* ---------- overlays: popovers (timer, reminders bell) ---------- */
  let pop = null;
  T.popover = function (id, anchor, render, label) {
    if (pop && pop.id === id) { T.closePopover(); return; }
    T.closePopover();
    const el = document.createElement('div');
    el.className = 'popover';
    el.id = id;
    el.setAttribute('role', 'dialog');
    el.setAttribute('aria-label', label);
    el.innerHTML = render();
    document.body.appendChild(el);
    pop = { id: id, el: el, anchor: anchor, render: render };
    placePopover();
    anchor.setAttribute('aria-expanded', 'true');
    const f = el.querySelector('button, select, input, a[href]');
    if (f) f.focus();
  };
  function placePopover() {
    if (!pop) return;
    const r = pop.anchor.getBoundingClientRect();
    const w = pop.el.offsetWidth;
    pop.el.style.top = (window.scrollY + r.bottom + 8) + 'px';
    pop.el.style.left = Math.max(12, Math.min(window.innerWidth - w - 12, r.right - w)) + 'px';
  }
  T.closePopover = function (refocus) {
    if (!pop) return;
    const p = pop;
    pop = null;
    p.el.remove();
    p.anchor.setAttribute('aria-expanded', 'false');
    if (refocus) {
      const a = document.getElementById(p.anchor.id) || p.anchor;
      if (document.contains(a)) a.focus();
    }
  };
  function rerenderPopover() {
    if (!pop) return;
    const fk = focusKey(document.activeElement);
    pop.el.innerHTML = pop.render();
    const a = document.getElementById(pop.anchor.id);
    if (a) { pop.anchor = a; a.setAttribute('aria-expanded', 'true'); }
    placePopover();
    restoreFocus(fk, pop.el);
  }
  document.addEventListener('mousedown', (e) => {
    if (pop && !pop.el.contains(e.target) && !pop.anchor.contains(e.target)) T.closePopover();
  });

  /* ---------- chrome ---------- */
  const NAV = [
    ['today', 'today.html', 'calendar', 'Today'],
    ['inbox', 'inbox.html', 'inbox', 'Inbox'],
    ['topics', 'topics.html', 'folder', 'Topics'],
    ['notes', 'notes.html', 'note', 'Notes'],
    ['jira', 'jira.html', 'ticket', 'Jira'],
    ['prs', 'prs.html', 'pr', 'Pull requests']
  ];

  function navSection() {
    if (T.page === 'topic') return 'topics';
    if (T.page === 'detail') return T.param('kind') === 'pr' ? 'prs' : 'jira';
    return T.page;
  }

  function timerChip() {
    const run = T.state().focus.run;
    if (!run) {
      return '<button type="button" class="timer-chip" id="timerBtn" data-act="openTimer" aria-haspopup="dialog" aria-expanded="false">' + icon('timer') + '<span class="hide-narrow">Focus</span></button>';
    }
    const cls = run.paused ? ' paused' : (run.phase === 'break' ? ' break' : '');
    const word = run.paused ? 'Paused' : (run.phase === 'break' ? 'Break' : 'Focus');
    return '<button type="button" class="timer-chip running' + cls + '" id="timerBtn" data-act="openTimer" aria-haspopup="dialog" aria-expanded="false" aria-label="Focus timer, ' + word + '">' +
      '<span class="phase-dot" aria-hidden="true"></span><span class="num" data-timer-left>' + fmtLeft(timerLeft()) + '</span></button>';
  }

  function renderTop() {
    const sec = navSection();
    const inboxN = S.inboxItems().length;
    const dueN = S.dueReminders().length;
    const nav = NAV.map((n) => '<a href="' + n[1] + '"' + (sec === n[0] ? ' aria-current="page"' : '') + '>' + icon(n[2]) + '<span>' + n[3] + '</span>' +
      (n[0] === 'inbox' && inboxN ? '<span class="navcount" aria-label="' + inboxN + ' items">' + inboxN + '</span>' : '') + '</a>').join('');

    /* Active Topic tabs: the semantic Active category decides who is here, not the status name. */
    const active = S.activeTopics();
    const curId = T.page === 'topic' ? T.param('id') : null;
    const cur = curId ? S.topicById(curId) : null;
    let tabs = active.map((t) => ui.ttab(t, { current: t.id === curId })).join('');
    if (cur && S.topicCat(cur) !== 'active') tabs += ui.ttab(cur, { current: true, cls: 'preview' });
    if (!active.length && !(cur)) tabs = '<span class="strip-empty">No active topics. Set a topic to an Active status to pin it here.</span>';

    $('#shellTop').innerHTML =
      '<div class="bar">' +
        '<a class="brand" href="today.html" aria-label="Thread, go to Today"><span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span><span>Thread</span></a>' +
        '<nav class="mainnav" aria-label="Main">' + nav + '</nav>' +
        '<div class="utils">' +
          '<button type="button" class="search-trigger" id="searchBtn" data-act="openSearch" aria-label="Search (' + T.modKey + ')">' + icon('search') + '<span>Search</span><span class="kbd">' + T.modKey + '</span></button>' +
          timerChip() +
          '<button type="button" class="iconbtn" id="bellBtn" data-act="openBell" aria-haspopup="dialog" aria-expanded="false" aria-label="Reminders' + (dueN ? ', ' + dueN + ' due now' : '') + '">' + icon('bell') + (dueN ? '<span class="badge" aria-hidden="true">' + dueN + '</span>' : '') + '</button>' +
          '<a class="iconbtn hide-narrow" href="trash.html" aria-label="Trash"' + (T.page === 'trash' ? ' aria-current="page"' : '') + '>' + icon('trash') + '</a>' +
          '<a class="iconbtn hide-narrow" href="settings.html" aria-label="Settings"' + (T.page === 'settings' ? ' aria-current="page"' : '') + '>' + icon('sliders') + '</a>' +
        '</div>' +
      '</div>' +
      '<nav class="tabstrip" aria-label="Active topics"><span class="strip-label"><span class="status" data-cat="active"><span class="tile">' + T.glyph('active') + '</span></span><span class="word">Active</span></span>' +
        tabs + '<a class="strip-all" href="topics.html">All topics' + icon('chevronRight') + '</a></nav>';

    const items = [
      ['today', 'today.html', 'calendar', 'Today'],
      ['inbox', 'inbox.html', 'inbox', 'Inbox'],
      ['topics', 'topics.html', 'folder', 'Topics'],
      ['notes', 'notes.html', 'note', 'Notes']
    ];
    const moreCurrent = ['jira', 'prs', 'settings', 'trash'].indexOf(sec) !== -1;
    $('#bottomBar').innerHTML = items.map((n) => '<a href="' + n[1] + '"' + (sec === n[0] ? ' aria-current="page"' : '') + '>' + icon(n[2]) + '<span>' + n[3] + '</span>' +
      (n[0] === 'inbox' && inboxN ? '<span class="navcount">' + inboxN + '</span>' : '') + '</a>').join('') +
      '<button type="button" data-act="openMore" aria-haspopup="dialog"' + (moreCurrent ? ' aria-current="page"' : '') + '>' + icon('more') + '<span>More</span></button>';

    $('#demoFoot').innerHTML = '<span class="flask">' + icon('flask') + 'Demo workspace with synthetic data, saved only in this browser.</span>' +
      '<span>Demo clock ' + esc(T.state().sim.clock) + ', Fri 13 Nov 2026</span>' +
      '<button type="button" data-act="openShortcuts">Keyboard shortcuts</button>' +
      '<button type="button" data-act="resetDemo">Reset demo data</button>' +
      '<a href="index.html" style="color:var(--text-2)">Prototype map</a>';
  }

  /* ---------- render loop with focus preservation ---------- */
  function focusKey(el) {
    if (!el || el === document.body) return null;
    if (el.id) return { id: el.id, sel: selRange(el) };
    const d = el.dataset || {};
    const k = (d.act || d.change || d.input || '') + '|' + (d.id || d.key || d.kind || d.field || d.value || '');
    if (k === '|') return null;
    return { k: k, sel: selRange(el) };
  }
  function selRange(el) {
    try { return typeof el.selectionStart === 'number' ? [el.selectionStart, el.selectionEnd] : null; } catch (e) { return null; }
  }
  function restoreFocus(fk, root) {
    if (!fk) return;
    root = root || document;
    let el = null;
    if (fk.id) el = root.querySelector('#' + CSS.escape(fk.id));
    else {
      el = $$('[data-act],[data-change],[data-input]', root).find((n) => {
        const d = n.dataset;
        return ((d.act || d.change || d.input || '') + '|' + (d.id || d.key || d.kind || d.field || d.value || '')) === fk.k;
      });
    }
    if (el && el !== document.activeElement) {
      el.focus({ preventScroll: true });
      if (fk.sel && el.setSelectionRange) { try { el.setSelectionRange(fk.sel[0], fk.sel[1]); } catch (e) { /* not a text field */ } }
    }
  }

  let pending = false;
  function schedule() {
    if (pending) return;
    pending = true;
    requestAnimationFrame(() => { pending = false; renderAll(); });
  }

  function renderAll() {
    const view = T.views[T.page];
    const fk = focusKey(document.activeElement);
    const keep = $$('[data-keep-scroll]').map((el) => [el.dataset.keepScroll, el.scrollLeft, el.scrollTop]);
    if (!view.bare) renderTop();
    const main = $('#main');
    main.innerHTML = signedOutBanner() + view.render();
    if (view.mount) view.mount(main);
    $$('textarea.grow', main).forEach(grow);
    keep.forEach((k) => { const el = $('[data-keep-scroll="' + k[0] + '"]'); if (el) { el.scrollLeft = k[1]; el.scrollTop = k[2]; } });
    Object.keys(overlays).forEach((id) => {
      const o = overlays[id];
      if (o.render && o.el.open && o.el.dataset.live === '1') o.el.innerHTML = o.render();
    });
    rerenderPopover();
    restoreFocus(fk);
  }
  T.render = renderAll;

  function signedOutBanner() {
    const s = T.state();
    if (s.session.signedIn || T.views[T.page].bare) return '';
    return '<div class="banner warn" style="margin-bottom:22px">' + icon('lock') + '<div><strong>Signed out of the demo.</strong> Your local work is still on this device and nothing was discarded. Sign in again to resume syncing.</div>' +
      '<div class="banner-actions"><button type="button" class="btn btn-sm" data-act="signIn">Sign in again (demo)</button></div></div>';
  }

  T.boot = function (page) {
    T.page = page;
    const view = T.views[page];
    store.get();
    const app = document.getElementById('app');
    if (view.bare) {
      app.innerHTML = '<main id="main" tabindex="-1" class="bleed"></main><div class="toasts" id="toasts" role="status" aria-live="polite"></div>';
    } else {
      app.innerHTML = '<header class="shell-top" id="shellTop"></header>' +
        '<main id="main" tabindex="-1"' + (view.bleed ? ' class="bleed"' : '') + '></main>' +
        '<footer class="demo-foot" id="demoFoot"></footer>' +
        '<nav class="bottombar" id="bottomBar" aria-label="Main, compact"></nav>' +
        '<div class="toasts" id="toasts" role="status" aria-live="polite"></div>';
    }
    if (view.title) document.title = view.title + ' | Thread v2 prototype';
    renderAll();
    if (view.init) view.init();
    store.subscribe((info) => {
      if (info && info.silent) { if (!view.bare) renderTop(); rerenderPopover(); return; }
      schedule();
    });
    setInterval(tick, 1000);
    window.addEventListener('resize', placePopover);
    /* Deep links from the prototype map open an overlay surface directly. */
    const open = T.param('open');
    if (open && !view.bare) {
      setTimeout(() => {
        if (open === 'search') T.openSearch();
        if (open === 'capture') T.capture({});
        if (open === 'timer') { const b = $('#timerBtn'); if (b) T.act.openTimer(b); }
        if (open === 'reminders') { const b = $('#bellBtn'); if (b) T.act.openBell(b); }
      }, 60);
    }
  };

  /* ---------- delegated events ---------- */
  function run(name, el, ev) {
    const fn = T.act[name];
    if (fn) fn(el, ev);
    else if (window.console) console.warn('No handler for', name);
  }
  document.addEventListener('click', (ev) => {
    const el = ev.target.closest('[data-act]');
    if (!el || el.disabled) return;
    if (el.tagName === 'A' && !el.getAttribute('href')) ev.preventDefault();
    run(el.dataset.act, el, ev);
  });
  document.addEventListener('change', (ev) => {
    const el = ev.target.closest('[data-change]');
    if (el) run(el.dataset.change, el, ev);
  });
  /* One-line fields that wrap: a textarea that grows with its text. Enter leaves the field. */
  function grow(el) { el.style.height = 'auto'; el.style.height = el.scrollHeight + 'px'; }
  T.grow = grow;
  document.addEventListener('input', (ev) => { if (ev.target.matches && ev.target.matches('textarea.grow')) grow(ev.target); }, true);
  document.addEventListener('keydown', (ev) => {
    const el = ev.target;
    if (ev.key === 'Enter' && el.matches && el.matches('textarea.grow.oneline')) { ev.preventDefault(); if (!el.dataset.enterTo) el.blur(); }
  }, true);
  window.addEventListener('resize', () => $$('textarea.grow').forEach(grow));
  document.addEventListener('input', (ev) => {
    const el = ev.target.closest('[data-input]');
    if (el) run(el.dataset.input, el, ev);
  });
  document.addEventListener('submit', (ev) => {
    const f = ev.target.closest('form[data-submit]');
    if (!f) return;
    ev.preventDefault();
    run(f.dataset.submit, f, ev);
  });

  function typing(el) {
    if (!el) return false;
    return el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName);
  }
  document.addEventListener('keydown', (ev) => {
    if ((ev.metaKey || ev.ctrlKey) && (ev.key === 'k' || ev.key === 'K')) {
      ev.preventDefault();
      if (T.views[T.page].bare) return;
      T.openSearch();
      return;
    }
    if (ev.key === 'Escape' && pop) { ev.preventDefault(); T.closePopover(true); return; }
    if (ev.metaKey || ev.ctrlKey || ev.altKey) return;
    if (typing(ev.target) || document.querySelector('dialog[open]')) return;
    if (T.views[T.page].bare) return;
    if (ev.key === 'n' || ev.key === 'N') { ev.preventDefault(); T.capture({}); }
    else if (ev.key === '/') { ev.preventDefault(); T.openSearch(); }
    else if (ev.key === '?') { ev.preventDefault(); T.act.openShortcuts(); }
  });

  /* ---------- shared actions ---------- */
  T.setTaskDone = function (id, done) {
    const k = S.taskById(id);
    if (!k) return;
    store.update((s) => {
      const t = s.tasks.find((x) => x.id === id);
      t.done = done;
      t.completedOn = done ? TM.DEMO_DATE : null;
      if (done) t.inbox = false;
    });
    if (done) T.toast('Done: ' + k.title, { undo: () => T.setTaskDone(id, false) });
  };
  T.act.toggleTask = (el) => T.setTaskDone(el.dataset.id, el.checked);
  T.act.toggleTaskBtn = (el) => { const k = S.taskById(el.dataset.id); if (k) T.setTaskDone(k.id, !k.done); };

  T.act.resetDemo = function () {
    T.dialog('confirmDlg', {
      label: 'confirmH',
      render: () => '<div class="dlg"><div class="dlg-head"><h2 id="confirmH">Reset the demo data?</h2></div>' +
        '<div class="dlg-body"><p class="muted">This restores the synthetic example workspace in this browser. Your demo edits are replaced.</p></div>' +
        '<div class="dlg-foot"><button type="button" class="btn" data-act="closeDlg">Cancel</button><button type="button" class="btn-key" data-act="confirmReset">Reset demo data</button></div></div>',
      focus: '.btn'
    });
  };
  T.act.confirmReset = function (el) {
    T.closeDialog('confirmDlg');
    store.reset('example');
    T.toast('Demo data reset');
  };
  T.act.signIn = function () {
    store.update((s) => { s.session.signedIn = true; });
    T.toast('Signed in to the demo workspace');
  };

  T.act.openMore = function () {
    const links = [
      ['jira.html', 'ticket', 'Jira'], ['prs.html', 'pr', 'Pull requests'], ['settings.html', 'sliders', 'Settings'], ['trash.html', 'trash', 'Trash'], ['index.html', 'layoutList', 'Prototype map']
    ];
    T.dialog('moreDlg', {
      cls: 'sheetdlg', label: 'moreH',
      render: () => '<div class="dlg"><div class="dlg-head"><h2 id="moreH">More</h2><button type="button" class="iconbtn" data-act="closeDlg" aria-label="Close">' + icon('x') + '</button></div>' +
        '<div class="dlg-body"><nav class="morelist" aria-label="More pages">' + links.map((l) => '<a href="' + l[0] + '">' + icon(l[1]) + l[2] + '</a>').join('') +
        '<button type="button" data-act="moreSearch">' + icon('search') + 'Search</button></nav></div></div>',
      focus: '.morelist a'
    });
  };
  T.act.moreSearch = function () { T.closeDialog('moreDlg'); setTimeout(T.openSearch, 0); };

  T.act.openShortcuts = function () {
    const rows = [
      [T.modKey, 'Search everything on this device'], ['/', 'Search'], ['N', 'Capture a task, note or topic'], ['?', 'Show these shortcuts'],
      ['Esc', 'Close a dialog, panel or popover'], ['↑ ↓ Enter', 'Move through search results and open one'], ['Tab', 'Move between controls. Focus is always visible.']
    ];
    T.dialog('keysDlg', {
      label: 'keysH',
      render: () => '<div class="dlg"><div class="dlg-head"><h2 id="keysH">Keyboard shortcuts</h2><button type="button" class="iconbtn" data-act="closeDlg" aria-label="Close">' + icon('x') + '</button></div>' +
        '<div class="dlg-body"><dl class="fields keys">' + rows.map((r) => '<dt><span class="kbd">' + esc(r[0]) + '</span></dt><dd>' + esc(r[1]) + '</dd>').join('') + '</dl>' +
        '<p class="faint" style="font-size:13px">Single-letter shortcuts work when you are not typing in a field.</p></div></div>',
      focus: '.iconbtn'
    });
  };

  /* ---------- quick capture ---------- */
  T.capture = function (o) {
    o = o || {};
    const kind = o.kind || 'task';
    const preset = o.topicId !== undefined ? o.topicId : (T.ctxTopicId || null);
    const topics = T.state().topics.filter((t) => S.topicCat(t) !== 'done' || t.id === preset);
    const colors = Object.keys(T.COLORS);
    const firstPlanned = T.state().statuses.find((s) => s.cat === 'planned') || T.state().statuses[0];
    const kinds = [['task', 'check', 'Task'], ['note', 'note', 'Note'], ['topic', 'folder', 'Topic']];
    T.dialog('captureDlg', {
      label: 'capH',
      render: () =>
        '<form class="dlg" data-submit="captureSubmit" autocomplete="off">' +
          '<div class="dlg-head"><h2 id="capH">Capture</h2><button type="button" class="iconbtn" data-act="closeDlg" aria-label="Close">' + icon('x') + '</button></div>' +
          '<div class="dlg-body">' +
            '<div class="seg" role="radiogroup" aria-label="What are you capturing">' + kinds.map((k) =>
              '<label><input type="radio" name="kind" value="' + k[0] + '"' + (k[0] === kind ? ' checked' : '') + ' data-change="captureKind">' + icon(k[1]) + k[2] + '</label>').join('') + '</div>' +
            '<label class="field"><span id="capTitleLabel">Title</span><input class="input" name="title" id="capTitle" required maxlength="140"></label>' +
            '<fieldset class="field" data-for="task note" style="border:0;padding:0;margin:0"><legend class="label" style="margin-bottom:8px">Where it goes</legend>' +
              '<div class="picker" role="radiogroup" aria-label="Where it goes">' +
                '<label><input type="radio" name="where" value=""' + (!preset ? ' checked' : '') + '><span class="ttab md inbox-tab">Inbox</span></label>' +
                topics.map((t) => '<label><input type="radio" name="where" value="' + t.id + '"' + (preset === t.id ? ' checked' : '') + '><span class="ttab md" style="--c:' + S.topicColor(t) + '">' + esc(t.name) + '</span></label>').join('') +
              '</div><p class="rule" id="capRule"></p></fieldset>' +
            '<label class="chipcheck" data-for="task"><input type="checkbox" name="today"><span>Plan for today</span></label>' +
            '<fieldset class="field" data-for="topic" style="border:0;padding:0;margin:0"><legend class="label" style="margin-bottom:8px">Topic colour</legend><div class="swatches">' +
              colors.map((c, i) => '<label><input type="radio" name="color" value="' + c + '"' + (i === 0 ? ' checked' : '') + '><span style="--c:' + T.COLORS[c] + '"></span><span class="vh">' + c + '</span></label>').join('') + '</div></fieldset>' +
            '<label class="field" data-for="topic"><span>Status</span><select class="select" name="status">' +
              T.state().statuses.map((st) => '<option value="' + st.id + '"' + (st.id === firstPlanned.id ? ' selected' : '') + '>' + esc(st.name) + ' (' + T.CATEGORIES[st.cat].label + ')</option>').join('') + '</select></label>' +
          '</div>' +
          '<div class="dlg-foot"><span class="hint">No date needed. Enter adds it.</span><button type="submit" class="btn-key"><span id="capGo">Add task</span><kbd aria-hidden="true">↵</kbd></button></div>' +
        '</form>',
      mount: (d) => { syncCapture(d); $$('input[name="where"]', d).forEach((r) => r.addEventListener('change', () => syncCapture(d))); },
      focus: '#capTitle'
    });
  };
  function syncCapture(d) {
    const kind = $('input[name="kind"]:checked', d).value;
    $$('[data-for]', d).forEach((el) => { el.hidden = el.dataset.for.split(' ').indexOf(kind) === -1; });
    const where = $('input[name="where"]:checked', d);
    const t = where && where.value ? S.topicById(where.value) : null;
    $('#capGo', d).textContent = kind === 'topic' ? 'Create topic' : (kind === 'note' ? 'Add note' : 'Add task');
    $('#capTitleLabel', d).textContent = kind === 'topic' ? 'Topic name' : 'Title';
    $('#capRule', d).textContent = t ? 'Goes straight into ' + t.name + '.' : 'Without a Topic it waits in Inbox until you file it.';
  }
  T.act.captureKind = (el) => syncCapture(el.closest('dialog'));
  T.act.captureSubmit = function (form) {
    const fd = new FormData(form);
    const kind = fd.get('kind');
    const title = String(fd.get('title') || '').trim();
    if (!title) { $('#capTitle').focus(); return; }
    const where = fd.get('where') || null;
    const stamp = T.nowStamp();
    if (kind === 'task') {
      const id = T.util.uid('k');
      store.update((s) => s.tasks.push({ id: id, title: title, topicId: where, done: false, planned: fd.get('today') ? TM.DEMO_DATE : null, deadline: null, inbox: !where, captured: stamp }));
      T.closeDialog('captureDlg');
      const t = S.topicById(where);
      T.toast(t ? 'Task added to ' + t.name : 'Task added to Inbox', { href: t ? ui.topicHref(t.id) : 'inbox.html', linkLabel: t ? 'Open topic' : 'Open Inbox' });
    } else if (kind === 'note') {
      const id = T.util.uid('n');
      store.update((s) => s.notes.push({ id: id, title: title, topicIds: where ? [where] : [], inbox: !where, updated: stamp, sync: 'synced', html: '', captured: stamp }));
      T.closeDialog('captureDlg');
      T.toast(where ? 'Note added to ' + S.topicById(where).name : 'Note added to Inbox', { href: 'notes.html?id=' + id, linkLabel: 'Open note' });
    } else {
      const id = T.util.uid('t');
      store.update((s) => s.topics.push({ id: id, name: title, color: fd.get('color') || 'lavender', statusId: fd.get('status'), goal: '', leftOff: '', next: '' }));
      T.closeDialog('captureDlg');
      T.go('topics.html?flash=' + encodeURIComponent(id));
    }
  };
  T.act.capture = (el) => T.capture({ kind: el.dataset.kind, topicId: el.dataset.topic !== undefined ? (el.dataset.topic || null) : undefined });

  /* ---------- global search ---------- */
  function searchIndex() {
    const s = T.state();
    const items = [];
    s.topics.forEach((t) => items.push({ g: 'Topics', icon: 'folder', title: t.name, sub: S.statusById(t.statusId).name + (t.next ? ', next: ' + t.next : ''), text: t.name + ' ' + t.goal + ' ' + t.next, href: ui.topicHref(t.id), color: S.topicColor(t) }));
    s.tasks.forEach((k) => { const t = S.topicById(k.topicId); items.push({ g: 'Tasks', icon: k.done ? 'circleCheck' : 'check', title: k.title, sub: (k.done ? 'Done, ' : '') + (t ? t.name : (k.inbox ? 'Inbox' : 'Standalone')), text: k.title, task: k.id, href: 'task.html?id=' + k.id }); });
    s.notes.forEach((n) => { const names = n.topicIds.map((id) => S.topicById(id)).filter(Boolean).map((t) => t.name); items.push({ g: 'Notes', icon: 'note', title: n.title, sub: names.length ? names.join(', ') : (n.inbox ? 'Inbox' : 'Standalone'), text: n.title + ' ' + T.util.noteText(n.html), href: 'notes.html?id=' + n.id }); });
    s.jira.filter((j) => !j.remoteOnly).forEach((j) => items.push({ g: 'Jira, downloaded', icon: 'ticket', title: j.key + '  ' + j.title, sub: j.status + ', cached on this device', text: j.key + ' ' + j.title, href: 'jira.html?key=' + j.key }));
    s.prs.forEach((p) => items.push({ g: 'Pull requests, downloaded', icon: 'pr', title: p.title + '  #' + p.number, sub: p.repo, text: p.title + ' #' + p.number + ' ' + p.number + ' ' + p.repo, href: 'prs.html?id=' + p.id }));
    return items;
  }
  function highlight(text, q) {
    if (!q) return esc(text);
    const i = text.toLowerCase().indexOf(q.toLowerCase());
    if (i < 0) return esc(text);
    return esc(text.slice(0, i)) + '<mark>' + esc(text.slice(i, i + q.length)) + '</mark>' + esc(text.slice(i + q.length));
  }
  let searchSel = 0;
  let searchHits = [];
  function searchResults(q) {
    q = q.trim();
    const box = $('#searchList');
    const input = $('#searchIn');
    if (!box) return;
    let html = '';
    searchHits = [];
    if (!q) {
      const act = S.activeTopics();
      const recent = T.state().notes.slice().sort((a, b) => (a.updated < b.updated ? 1 : -1)).slice(0, 3);
      const sugg = act.map((t) => ({ g: 'Active topics', icon: 'folder', title: t.name, sub: t.next ? 'Next: ' + t.next : '', href: ui.topicHref(t.id) }))
        .concat(recent.map((n) => ({ g: 'Recent notes', icon: 'note', title: n.title, sub: 'Edited ' + TM.fmtStamp(n.updated), href: 'notes.html?id=' + n.id })));
      searchHits = sugg;
    } else {
      const terms = q.toLowerCase().split(/\s+/);
      const all = searchIndex().filter((it) => terms.every((tm) => it.text.toLowerCase().indexOf(tm) !== -1));
      const per = {};
      all.forEach((it) => { per[it.g] = per[it.g] || []; if (per[it.g].length < 5) per[it.g].push(it); });
      ['Topics', 'Tasks', 'Notes', 'Jira, downloaded', 'Pull requests, downloaded'].forEach((g) => { (per[g] || []).forEach((it) => searchHits.push(it)); });
    }
    if (!searchHits.length) {
      html = '<div class="noresults"><strong>No matches for “' + esc(q) + '” on this device.</strong><span>Search covers Topics, Tasks, Notes, and Jira issues and pull requests already downloaded. To look for other Jira issues, use Jira search.</span>' +
        '<a class="btn btn-sm" href="jira.html?q=' + encodeURIComponent(q) + '">' + icon('ticket') + 'Search Jira (sample)</a></div>';
      searchSel = -1;
    } else {
      if (searchSel >= searchHits.length || searchSel < 0) searchSel = 0;
      let lastG = null;
      let gi = 0;
      searchHits.forEach((it, i) => {
        if (it.g !== lastG) {
          if (lastG !== null) html += '</div>';
          gi++;
          html += '<div class="rgroup" role="group" aria-labelledby="rg' + gi + '"><h3 id="rg' + gi + '">' + esc(it.g) + '</h3>';
          lastG = it.g;
        }
        html += '<div class="ropt" role="option" id="ropt' + i + '" data-i="' + i + '" aria-selected="' + (i === searchSel) + '">' +
          (it.color ? '<span class="sw-dot" style="--c:' + it.color + '" aria-hidden="true"></span>' : icon(it.icon)) +
          '<span style="min-width:0"><span class="rt" style="display:block">' + highlight(it.title, q.split(/\s+/)[0]) + '</span>' + (it.sub ? '<span class="rs" style="display:block">' + esc(it.sub) + '</span>' : '') + '</span>' +
          '<span class="faint" style="font-size:12px">' + (i === searchSel ? 'Enter' : '') + '</span></div>';
      });
      html += '</div>';
    }
    box.innerHTML = html;
    input.setAttribute('aria-activedescendant', searchSel >= 0 && searchHits.length ? 'ropt' + searchSel : '');
    const live = $('#searchCount');
    if (live) live.textContent = q ? (searchHits.length ? searchHits.length + ' results' : 'No results') : '';
    const cur = $('#ropt' + searchSel);
    if (cur) cur.scrollIntoView({ block: 'nearest' });
  }
  function openHit(i) {
    const it = searchHits[i];
    if (!it) return;
    T.closeDialog('searchDlg');
    if (it.task && !T.narrow() && T.page !== 'task') { setTimeout(() => T.openTask(it.task), 0); return; }
    T.go(it.href);
  }
  T.openSearch = function () {
    if (document.getElementById('searchDlg') && document.getElementById('searchDlg').open) return;
    searchSel = 0;
    T.dialog('searchDlg', {
      cls: 'searchdlg', label: 'searchH',
      render: () => '<div class="dlg"><h2 id="searchH" class="vh">Search</h2>' +
        '<div class="search-in">' + icon('search') + '<input id="searchIn" type="text" role="combobox" aria-expanded="true" aria-controls="searchList" aria-autocomplete="list" aria-label="Search Topics, Tasks, Notes, Jira and pull requests" placeholder="Search topics, tasks, notes, LARK keys, PR numbers" autocomplete="off"><span class="kbd">Esc</span></div>' +
        '<div class="results" id="searchList" role="listbox" aria-label="Results"></div><span class="vh" id="searchCount" aria-live="polite"></span>' +
        '<div class="search-foot"><span><span class="kbd">↑</span><span class="kbd">↓</span>move</span><span><span class="kbd">Enter</span>open</span><span><span class="kbd">Esc</span>close</span><span>Results come from this device only.</span></div></div>',
      mount: (d) => {
        const input = $('#searchIn', d);
        searchResults('');
        input.addEventListener('input', () => { searchSel = 0; searchResults(input.value); });
        input.addEventListener('keydown', (e) => {
          if (e.key === 'ArrowDown') { e.preventDefault(); if (searchHits.length) { searchSel = (searchSel + 1) % searchHits.length; searchResults(input.value); } }
          else if (e.key === 'ArrowUp') { e.preventDefault(); if (searchHits.length) { searchSel = (searchSel - 1 + searchHits.length) % searchHits.length; searchResults(input.value); } }
          else if (e.key === 'Enter') { e.preventDefault(); openHit(searchSel); }
        });
        $('#searchList', d).addEventListener('click', (e) => { const o = e.target.closest('.ropt'); if (o) openHit(+o.dataset.i); });
      },
      focus: '#searchIn'
    });
  };
  T.act.openSearch = () => T.openSearch();

  /* ---------- task details (drawer on wide screens, routed page on narrow) ---------- */
  function effectText(k) {
    if (k.done) return ['Completed', k.completedOn === TM.DEMO_DATE ? 'It stays in Today, struck through, until the day ends.' : 'It no longer shows in Today.'];
    const g = S.taskTodayGroup(k);
    if (g === 'overdue') return ['Overdue in Today', 'The deadline ' + ui.deadlineLabel(k) + ' has passed, so it sits at the top of Today.'];
    if (g === 'today') return ['Planned today', 'It shows in Today under Planned today' + (k.deadline ? ', with the deadline ' + ui.deadlineLabel(k) + ' beside it.' : '.')];
    if (g === 'due') return ['Due today', 'Not planned for today, but the deadline is ' + TM.fmtTime(k.deadline) + ', so Today lists it under Due today.'];
    if (g === 'earlier') return ['Earlier planned', 'Planned for ' + TM.fmtDay(k.planned) + ' and not done, so Today keeps it under Earlier planned.'];
    if (k.planned) return ['Planned for ' + TM.fmtDay(k.planned), 'It will show in Today on that day.'];
    if (k.deadline) return ['Due ' + ui.deadlineLabel(k), 'It will show in Today on the deadline day.'];
    return ['Not in Today', 'Add a planned day or a deadline to see it in Today. A reminder alone does not add it.'];
  }

  ui.taskForm = function (k, o) {
    o = o || {};
    const topics = T.state().topics;
    const rem = S.taskReminder(k.id);
    const dlDate = k.deadline ? k.deadline.slice(0, 10) : '';
    const dlTime = k.deadline && k.deadline.length > 10 ? k.deadline.slice(11, 16) : '';
    const eff = effectText(k);
    return '<div class="taskform">' +
      '<div class="task-head"><label class="checkhit"><input type="checkbox" class="check" id="taskDone" data-change="toggleTask" data-id="' + k.id + '"' + (k.done ? ' checked' : '') + ' aria-label="Completed"></label>' +
        '<div><label class="vh" for="taskTitle">Task title</label><input class="task-title-input" id="taskTitle" value="' + esc(k.title) + '" data-change="taskTitle" data-id="' + k.id + '" maxlength="140">' +
        '<div class="task-state">' + (k.done ? '<span class="status" data-cat="done"><span class="tile">' + T.glyph('done') + '</span>Done ' + esc(TM.fmtDay(k.completedOn || TM.DEMO_DATE)) + '</span>' : '<span class="faint">Open</span>') +
        '<button type="button" class="btn btn-sm" id="taskDoneBtn" data-act="toggleTaskBtn" data-id="' + k.id + '">' + (k.done ? icon('undo') + 'Reopen' : icon('check') + 'Complete') + '</button>' +
        (k.inbox ? '<span class="m faint">' + icon('inbox') + 'In Inbox</span>' : '') + '</div></div></div>' +
      '<label class="field"><span>Topic</span><select class="select" id="taskTopic" data-change="taskTopic" data-id="' + k.id + '">' +
        '<option value="">' + (k.inbox ? 'No topic (stays in Inbox)' : 'No topic (standalone)') + '</option>' +
        topics.map((t) => '<option value="' + t.id + '"' + (t.id === k.topicId ? ' selected' : '') + '>' + esc(t.name) + '</option>').join('') + '</select>' +
        '<span class="faint" style="font-size:12.5px">A task belongs to one topic or none.</span></label>' +
      '<div class="when-grid">' +
        '<div class="when-card"><label class="label" for="taskPlanned">' + icon('calendar') + 'Planned day</label><input type="date" class="input" id="taskPlanned" data-change="taskPlanned" data-id="' + k.id + '" value="' + (k.planned || '') + '">' +
          '<p class="help">The day you mean to work on it.</p>' + (k.planned ? '<button type="button" class="btn btn-sm btn-ghost clear" data-act="taskClear" data-field="planned" data-id="' + k.id + '">Clear day</button>' : '') + '</div>' +
        '<div class="when-card"><label class="label" for="taskDlDate">' + icon('flag') + 'Deadline</label><input type="date" class="input" id="taskDlDate" data-change="taskDeadline" data-id="' + k.id + '" value="' + dlDate + '">' +
          '<label class="vh" for="taskDlTime">Deadline time (optional)</label><input type="time" class="input" id="taskDlTime" data-change="taskDeadline" data-id="' + k.id + '" value="' + dlTime + '"' + (dlDate ? '' : ' disabled') + '>' +
          '<p class="help">When it must be done. Time is optional.</p>' + (k.deadline ? '<button type="button" class="btn btn-sm btn-ghost clear" data-act="taskClear" data-field="deadline" data-id="' + k.id + '">Clear deadline</button>' : '') + '</div>' +
        '<div class="when-card"><span class="label">' + icon('bell') + 'Reminder</span>' +
          (rem ? '<strong class="num" style="font-size:26px;line-height:1">' + esc(TM.fmtTime(rem.at)) + '</strong><span class="help">' + esc(TM.fmtDay(rem.at)) + ': ' + esc(rem.text) + '</span>' +
            '<div style="display:flex;gap:6px;flex-wrap:wrap"><button type="button" class="btn btn-sm" data-act="editReminder" data-id="' + rem.id + '">Change</button><button type="button" class="btn btn-sm btn-ghost" data-act="removeReminder" data-id="' + rem.id + '">Remove</button></div>'
            : '<p class="help">A nudge at a time. It does not plan the task.</p><button type="button" class="btn btn-sm" data-act="remind" data-kind="task" data-id="' + k.id + '">' + icon('plus') + 'Add reminder</button>') + '</div>' +
      '</div>' +
      '<div class="effect" aria-live="polite">' + icon('calendar') + '<div><strong>' + esc(eff[0]) + '</strong><span>' + esc(eff[1]) + '</span></div></div>' +
      '<div class="task-foot">' + (o.drawer ? '<a class="btn btn-sm btn-ghost" href="task.html?id=' + k.id + '">' + icon('external') + 'Open as page</a>' : '') +
        '<button type="button" class="btn btn-sm btn-ghost btn-quiet-danger" data-act="deleteTask" data-id="' + k.id + '">' + icon('trash') + 'Move to Trash</button></div>' +
    '</div>';
  };

  T.openTask = function (id) {
    if (T.narrow()) { T.go('task.html?id=' + encodeURIComponent(id)); return; }
    const d = T.dialog('taskDlg', {
      cls: 'drawer', label: 'taskDlgH',
      render: () => {
        const k = S.taskById(id);
        if (!k) return '<div class="dlg"><div class="dlg-head"><h2 id="taskDlgH">Task moved to Trash</h2><button type="button" class="iconbtn" data-act="closeDlg" aria-label="Close">' + icon('x') + '</button></div><div class="dlg-body"><p class="muted">You can restore it from Trash.</p></div></div>';
        return '<div class="dlg"><div class="dlg-head"><h2 id="taskDlgH">Task</h2><button type="button" class="iconbtn" data-act="closeDlg" aria-label="Close task">' + icon('x') + '</button></div>' +
          '<div class="dlg-body">' + ui.taskForm(k, { drawer: true }) + '</div></div>';
      },
      focus: '#taskTitle'
    });
    d.dataset.live = '1';
  };
  T.act.openTask = (el) => T.openTask(el.dataset.id);

  function patchTask(id, fn) { store.update((s) => { const k = s.tasks.find((x) => x.id === id); if (k) fn(k, s); }); }
  T.act.taskTitle = (el) => { const v = el.value.trim(); if (v) patchTask(el.dataset.id, (k) => { k.title = v; }); };
  T.act.taskTopic = (el) => patchTask(el.dataset.id, (k) => { k.topicId = el.value || null; if (el.value) k.inbox = false; });
  T.act.taskPlanned = (el) => patchTask(el.dataset.id, (k) => { k.planned = el.value || null; });
  T.act.taskDeadline = (el) => {
    const date = $('#taskDlDate', el.closest('.taskform')).value;
    const time = $('#taskDlTime', el.closest('.taskform')).value;
    patchTask(el.dataset.id, (k) => { k.deadline = date ? (time ? date + 'T' + time : date) : null; });
  };
  T.act.taskClear = (el) => patchTask(el.dataset.id, (k) => { k[el.dataset.field] = null; });
  T.act.deleteTask = (el) => { T.closeDialog('taskDlg'); T.trash('task', el.dataset.id); if (T.page === 'task') setTimeout(() => T.go('today.html'), 900); };

  /* ---------- reminders ---------- */
  function targetTitle(kind, id) {
    if (kind === 'task') { const k = S.taskById(id); return k ? k.title : ''; }
    if (kind === 'note') { const n = S.noteById(id); return n ? n.title : ''; }
    const t = S.topicById(id); return t ? t.name : '';
  }
  function targetPicker() {
    const s = T.state();
    return '<label class="field"><span>About</span><select class="select" name="target" id="remTarget" required><option value="">Choose a task, note or topic</option>' +
      '<optgroup label="Tasks">' + s.tasks.filter((k) => !k.done).map((k) => '<option value="task:' + k.id + '">' + esc(k.title) + '</option>').join('') + '</optgroup>' +
      '<optgroup label="Notes">' + s.notes.map((n) => '<option value="note:' + n.id + '">' + esc(n.title) + '</option>').join('') + '</optgroup>' +
      '<optgroup label="Topics">' + s.topics.map((t) => '<option value="topic:' + t.id + '">' + esc(t.name) + '</option>').join('') + '</optgroup></select></label>';
  }
  T.remind = function (kind, id, remId) {
    const existing = remId ? T.state().reminders.find((r) => r.id === remId) : null;
    if (existing) { kind = existing.target.kind; id = existing.target.id; }
    const now = TM.now();
    const nextHour = TM.pad(Math.min(23, now.getHours() + 1)) + ':00';
    const date = existing ? existing.at.slice(0, 10) : TM.DEMO_DATE;
    const time = existing ? existing.at.slice(11, 16) : nextHour;
    const kindWord = { task: 'task', note: 'note', topic: 'topic' }[kind];
    T.dialog('remDlg', {
      label: 'remH',
      render: () => '<form class="dlg" data-submit="saveReminder" data-kind="' + (kind || '') + '" data-id="' + (id || '') + '" data-rem="' + (remId || '') + '">' +
        '<div class="dlg-head"><h2 id="remH">' + (existing ? 'Change reminder' : 'Remind me') + '</h2><button type="button" class="iconbtn" data-act="closeDlg" aria-label="Close">' + icon('x') + '</button></div>' +
        '<div class="dlg-body">' + (kind ? '<p class="muted" style="font-size:14px">For the ' + kindWord + ' <strong style="color:var(--text)">' + esc(targetTitle(kind, id)) + '</strong></p>' : targetPicker()) +
          '<div class="chips" role="group" aria-label="Quick times">' +
            '<button type="button" class="chip" data-act="remQuick" data-value="' + TM.DEMO_DATE + 'T' + nextHour + '">In about an hour</button>' +
            '<button type="button" class="chip" data-act="remQuick" data-value="' + TM.DEMO_DATE + 'T15:00">This afternoon, 15:00</button>' +
            '<button type="button" class="chip" data-act="remQuick" data-value="2026-11-14T' + T.state().prefs.reminderTime + '">Tomorrow, ' + esc(T.state().prefs.reminderTime) + '</button></div>' +
          '<div class="row2"><label class="field"><span>Day</span><input type="date" class="input" name="date" id="remDate" value="' + date + '" required></label>' +
          '<label class="field"><span>Time</span><input type="time" class="input" name="time" id="remTime" value="' + time + '" required></label></div>' +
          '<label class="field"><span>Message</span><input class="input" name="text" id="remText" value="' + esc(existing ? existing.text : (kind ? targetTitle(kind, id) : '')) + '" maxlength="120" placeholder="Uses the item title if empty"></label>' +
          '<p class="faint" style="font-size:12.5px">Shows on the Today ruler at its time and in the bell. It does not change planned day or deadline.</p></div>' +
        '<div class="dlg-foot"><button type="button" class="btn" data-act="closeDlg">Cancel</button><button type="submit" class="btn-key">' + icon('bell') + '<span>' + (existing ? 'Save reminder' : 'Set reminder') + '</span></button></div></form>',
      focus: kind ? '#remTime' : '#remTarget'
    });
  };
  T.act.remind = (el) => T.remind(el.dataset.kind || null, el.dataset.id || null);
  T.act.editReminder = (el) => T.remind(null, null, el.dataset.id);
  T.act.remQuick = (el) => { const v = el.dataset.value; $('#remDate').value = v.slice(0, 10); $('#remTime').value = v.slice(11, 16); $$('.chip', el.parentNode).forEach((c) => c.setAttribute('aria-pressed', String(c === el))); };
  T.act.saveReminder = function (form) {
    const fd = new FormData(form);
    let kind = form.dataset.kind;
    let id = form.dataset.id;
    if (!kind) {
      const v = String(fd.get('target') || '');
      if (!v) { $('#remTarget').focus(); return; }
      kind = v.split(':')[0];
      id = v.split(':')[1];
    }
    const at = fd.get('date') + 'T' + fd.get('time');
    const text = String(fd.get('text') || '').trim() || targetTitle(kind, id);
    const remId = form.dataset.rem;
    store.update((s) => {
      if (remId) { const r = s.reminders.find((x) => x.id === remId); if (r) { r.at = at; r.text = text; r.dismissed = false; } }
      else s.reminders.push({ id: T.util.uid('r'), at: at, text: text, target: { kind: kind, id: id }, dismissed: false });
    });
    T.closeDialog('remDlg');
    T.toast('Reminder set for ' + TM.fmtStamp(at));
  };
  T.act.removeReminder = (el) => {
    const r = T.state().reminders.find((x) => x.id === el.dataset.id);
    store.update((s) => { s.reminders = s.reminders.filter((x) => x.id !== el.dataset.id); });
    T.toast('Reminder removed', { undo: () => store.update((s) => s.reminders.push(r)) });
  };
  T.act.dismissReminder = (el) => store.update((s) => { const r = s.reminders.find((x) => x.id === el.dataset.id); if (r) r.dismissed = true; });
  T.act.snoozeReminder = (el) => {
    store.update((s) => {
      const r = s.reminders.find((x) => x.id === el.dataset.id);
      if (!r) return;
      const d = TM.now();
      d.setMinutes(d.getMinutes() + 60);
      r.at = TM.toStamp(d);
      r.dismissed = false;
    });
    T.toast('Snoozed for an hour');
  };
  T.act.openTarget = (el) => {
    const kind = el.dataset.kind;
    const id = el.dataset.id;
    T.closePopover();
    if (kind === 'task') T.openTask(id);
    else if (kind === 'note') T.go('notes.html?id=' + id);
    else T.go(ui.topicHref(id));
  };

  ui.reminderTarget = function (r) {
    const icons = { task: 'check', note: 'note', topic: 'folder' };
    return '<button type="button" class="linkbtn rem-target" data-act="openTarget" data-kind="' + r.target.kind + '" data-id="' + r.target.id + '">' + icon(icons[r.target.kind]) +
      '<span class="tlabel">' + esc(S.reminderTargetLabel(r)) + '</span></button>';
  };

  function bellPop() {
    const list = S.todayReminders();
    const due = list.filter((x) => x.state === 'due');
    const later = list.filter((x) => x.state === 'upcoming');
    const row = (x) => '<div class="remrow' + (x.state === 'due' ? ' due' : '') + '"><span class="when num">' + esc(TM.fmtTime(x.r.at)) + '</span>' +
      '<div style="min-width:0"><div class="what">' + esc(x.r.text) + '</div>' + ui.reminderTarget(x.r) +
      (x.state === 'due' ? '<div class="rem-actions" style="grid-column:auto"><button type="button" class="btn btn-sm" data-act="dismissReminder" data-id="' + x.r.id + '">Dismiss</button><button type="button" class="btn btn-sm btn-ghost" data-act="snoozeReminder" data-id="' + x.r.id + '">Snooze 1 h</button></div>' : '') + '</div></div>';
    return '<div class="pop-head"><h2>Reminders</h2><span class="faint" style="font-size:12.5px">Demo clock ' + esc(T.state().sim.clock) + '</span></div>' +
      '<div class="bellpop"><h3 class="pop-sub">Due now</h3>' + (due.length ? due.map(row).join('') : '<p class="faint" style="font-size:13.5px">Nothing due right now.</p>') +
      '<h3 class="pop-sub">Later today</h3>' + (later.length ? later.map(row).join('') : '<p class="faint" style="font-size:13.5px">No more reminders today.</p>') + '</div>' +
      '<a class="btn btn-sm" href="today.html#reminders">' + icon('calendar') + 'See them on Today</a>';
  }
  T.act.openBell = (el) => T.popover('bellPop', el, bellPop, 'Reminders');

  /* ---------- focus timer (post-pilot concept) ---------- */
  function timerLeft() {
    const r = T.state().focus.run;
    if (!r) return T.state().focus.work * 60;
    return r.paused ? r.left : Math.max(0, Math.round((r.endsAt - Date.now()) / 1000));
  }
  function fmtLeft(sec) { return TM.pad(Math.floor(sec / 60)) + ':' + TM.pad(sec % 60); }
  function linkLabel(v) {
    if (!v) return 'No task';
    const parts = v.split(':');
    return parts[0] === 'task' ? targetTitle('task', parts[1]) : targetTitle('topic', parts[1]);
  }
  function timerPop() {
    const f = T.state().focus;
    const r = f.run;
    const opts = '<option value="">No task or topic</option><optgroup label="Active topics">' + S.activeTopics().map((t) => '<option value="topic:' + t.id + '"' + (r && r.link === 'topic:' + t.id ? ' selected' : '') + '>' + esc(t.name) + '</option>').join('') + '</optgroup>' +
      '<optgroup label="Planned today">' + S.todayGroups().today.filter((k) => !k.done).map((k) => '<option value="task:' + k.id + '"' + (r && r.link === 'task:' + k.id ? ' selected' : '') + '>' + esc(k.title) + '</option>').join('') + '</optgroup>';
    const phase = !r ? 'Ready: ' + f.work + ' minutes of focus, then a ' + f.brk + ' minute break.' : (r.phase === 'break' ? 'Break. Stand up for a moment.' : 'Focus' + (r.link ? ' on ' + linkLabel(r.link) : '')) + (r && r.paused ? ' Paused.' : '');
    return '<div class="pop-head"><h2>Focus timer</h2><span class="concept">' + icon('flask') + 'Post-pilot concept</span></div>' +
      '<div><div class="timer-big num" data-timer-left>' + fmtLeft(timerLeft()) + '</div><p class="timer-phase">' + esc(phase) + '</p></div>' +
      '<label class="field"><span>Working on (optional)</span><select class="select" id="timerLink"' + (r ? ' disabled' : '') + '>' + opts + '</select></label>' +
      '<div class="timer-ctl">' +
        (!r ? '<button type="button" class="btn-key" id="timerStart" data-act="timerStart">' + icon('play') + '<span>Start ' + f.work + ' min</span></button>' :
          (r.paused ? '<button type="button" class="btn-key" id="timerResume" data-act="timerResume">' + icon('play') + '<span>Resume</span></button>' : '<button type="button" class="btn" id="timerPause" data-act="timerPause">' + icon('pause') + 'Pause</button>') +
          '<button type="button" class="btn" id="timerStop" data-act="timerStop">' + icon('stop') + 'Stop</button>') + '</div>' +
      '<div class="durs"><label>Focus, min<input type="number" class="input" min="5" max="90" id="timerWork" value="' + f.work + '" data-change="timerDur" data-field="work"' + (r ? ' disabled' : '') + '></label>' +
        '<label>Break, min<input type="number" class="input" min="1" max="30" id="timerBrk" value="' + f.brk + '" data-change="timerDur" data-field="brk"' + (r ? ' disabled' : '') + '></label></div>' +
      '<div><h3 class="pop-sub">Recent sessions</h3><ul class="hist">' + (f.history.length ? f.history.slice(0, 4).map((h) =>
        '<li><span class="num">' + esc(TM.fmtDay(h.at) === 'Today' ? TM.fmtTime(h.at) : TM.fmtDay(h.at)) + '</span><span>' + h.minutes + 'm</span><span class="lbl">' + esc(h.label) + (h.completed ? '' : ' (stopped)') + '</span></li>').join('') : '<li class="faint">No sessions yet.</li>') + '</ul></div>';
  }
  T.act.openTimer = (el) => T.popover('timerPop', el, timerPop, 'Focus timer');
  T.act.timerDur = (el) => {
    const v = Math.max(+el.min, Math.min(+el.max, parseInt(el.value, 10) || +el.min));
    store.update((s) => { s.focus[el.dataset.field] = v; }, { silent: true });
  };
  T.act.timerStart = () => {
    const link = $('#timerLink') ? $('#timerLink').value : '';
    store.update((s) => { s.focus.run = { phase: 'work', endsAt: Date.now() + s.focus.work * 60000, startedAt: Date.now(), paused: false, left: 0, link: link }; }, { silent: true });
    restoreTimerFocus('#timerPause');
  };
  T.act.timerPause = () => { store.update((s) => { s.focus.run.left = timerLeft(); s.focus.run.paused = true; }, { silent: true }); restoreTimerFocus('#timerResume'); };
  T.act.timerResume = () => { store.update((s) => { s.focus.run.endsAt = Date.now() + s.focus.run.left * 1000; s.focus.run.paused = false; }, { silent: true }); restoreTimerFocus('#timerPause'); };
  T.act.timerStop = () => {
    const r = T.state().focus.run;
    store.update((s) => {
      if (r && r.phase === 'work') {
        const mins = Math.round((s.focus.work * 60 - timerLeft()) / 60);
        if (mins >= 1) s.focus.history.unshift({ at: T.nowStamp(), minutes: mins, label: linkLabel(r.link), completed: false });
      }
      s.focus.run = null;
    }, { silent: true });
    restoreTimerFocus('#timerStart');
  };
  function restoreTimerFocus(sel) { setTimeout(() => { const b = $(sel); if (b) b.focus(); }, 0); }

  function tick() {
    const r = T.state().focus.run;
    if (!r) return;
    const left = timerLeft();
    $$('[data-timer-left]').forEach((el) => { el.textContent = fmtLeft(left); });
    if (left > 0 || r.paused) return;
    if (r.phase === 'work') {
      store.update((s) => {
        s.focus.history.unshift({ at: T.nowStamp(), minutes: s.focus.work, label: linkLabel(r.link), completed: true });
        s.focus.run = { phase: 'break', endsAt: Date.now() + s.focus.brk * 60000, paused: false, left: 0, link: r.link };
      }, { silent: true });
      T.toast('Focus session done. Break started.');
    } else {
      store.update((s) => { s.focus.run = null; }, { silent: true });
      T.toast('Break over');
    }
  }

  /* ---------- small shared helpers for views ---------- */
  T.pageHead = function (title, sub, actions) {
    return '<div class="pagehead"><div><h1>' + esc(title) + '</h1>' + (sub ? '<p class="sub">' + sub + '</p>' : '') + '</div>' +
      (actions ? '<div class="pagehead-actions">' + actions + '</div>' : '') + '</div>';
  };
})();
