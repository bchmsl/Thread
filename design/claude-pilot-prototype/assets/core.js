/* Thread prototype core: one shared state, persistence, routing, rendering,
   dialogs, toasts and the simulated sync and network engine.
   Plain classic script so the prototype also runs from file://. */
(function () {
  'use strict';
  const D = window.ThreadData;
  const STORE_KEY = 'thread-claude-pilot:v1';
  const PALETTE = ['lavender', 'mint', 'peach', 'sky', 'rose', 'butter'];
  const CAT_LABEL = { planned: 'Planned', active: 'Active', paused: 'Paused', done: 'Done' };
  const CAT_HELP = {
    planned: 'Not started yet.',
    active: 'Work in progress. Shown on Today.',
    paused: 'Waiting on something. Not on Today.',
    done: 'Finished. Can be reopened.'
  };

  /* ------------------------------------------------ escaping, templates */
  function Raw(s) { this.s = s; }
  Raw.prototype.toString = function () { return this.s; };
  const raw = (s) => new Raw(String(s));
  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ESC[c]);
  function flat(v) {
    if (v == null || v === false || v === true) return '';
    if (v instanceof Raw) return v.s;
    if (Array.isArray(v)) return v.map(flat).join('');
    return esc(v);
  }
  function html(strings) {
    let out = strings[0];
    for (let i = 1; i < arguments.length; i++) {
      const v = arguments[i];
      /* A boolean that is a whole attribute value, like aria-pressed="${x}", renders as "true" or "false". */
      out += (typeof v === 'boolean' && /="$/.test(strings[i - 1]) && strings[i].charAt(0) === '"' ? String(v) : flat(v)) + strings[i];
    }
    return new Raw(out);
  }
  const icon = (name, cls) => raw('<svg class="icon ' + (cls || '') + '" aria-hidden="true" focusable="false"><use href="#i-' + name + '"></use></svg>');
  const glyph = (cat) => raw('<svg class="glyph" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><use href="#g-' + cat + '"></use></svg>');

  /* -------------------------------------------------------------- state */
  const clone = (o) => JSON.parse(JSON.stringify(o));

  function freshState(kind) {
    const w = kind === 'empty' ? D.empty() : D.example();
    const cacheAt = '2026-11-12T13:58';
    const jiraCache = {}; w.cachedJira.forEach((k) => { jiraCache[k] = { at: cacheAt }; });
    const prCache = {}; w.cachedPrs.forEach((k) => { prCache[k] = { at: cacheAt }; });
    return {
      v: 1,
      session: { signedIn: false, workspace: kind || 'example' },
      prefs: { topicsView: 'list', startPage: 'today', timeFormat: '24h', captureDefault: 'task' },
      statuses: clone(D.statuses),
      topics: w.topics, tasks: w.tasks, notes: w.notes, reminders: w.reminders,
      links: w.links, trash: w.trash,
      cache: { jira: jiraCache, prs: prCache, lists: { jira: kind === 'empty' ? null : cacheAt, github: kind === 'empty' ? null : cacheAt } },
      conn: { jira: kind !== 'empty', github: kind !== 'empty' },
      sim: { now: D.DEMO_NOW, online: true, jira: 'ok', github: 'ok', saveFail: false, syncFail: false },
      noteSync: {},
      focus: { workMin: 25, breakMin: 5, status: 'idle', mode: 'work', endsAt: null, remainingMs: 25 * 60000, target: '',
        history: kind === 'empty' ? [] : [
          { at: '2026-11-12T10:05', mode: 'work', minutes: 25, label: 'QR payments' },
          { at: '2026-11-12T09:20', mode: 'work', minutes: 25, label: 'Review error copy with design' }
        ] },
      alerted: {},
      seq: 100
    };
  }

  let state;
  let storageOk = true;
  function load() {
    try {
      const s = localStorage.getItem(STORE_KEY);
      if (s) { const p = JSON.parse(s); if (p && p.v === 1) return p; }
    } catch (e) { storageOk = false; }
    return freshState('example');
  }
  let saveTimer = null;
  function persist() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(persistNow, 60);
  }
  function persistNow() {
    try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); storageOk = true; } catch (e) { storageOk = false; }
  }

  /* Transient UI state: never persisted. */
  const ui = {
    navOpen: false, refreshing: {}, fresh: {}, loading: {}, sections: {}, topicEdit: null,
    inboxSel: null, inboxChecked: [], noteFilter: 'all', linkForm: false,
    jiraSearch: { q: '', status: 'idle', results: [] }, compare: null, lastPath: ''
  };

  function uid(prefix) { state.seq += 1; return prefix + state.seq; }

  function commit(fn, opts) {
    fn(state);
    persist();
    if (!opts || !opts.silent) render();
  }

  /* ---------------------------------------------------------- date helpers */
  const pad = (n) => String(n).padStart(2, '0');
  const DOW = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  const MON = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  const now = () => state.sim.now;
  const today = () => state.sim.now.slice(0, 10);
  function ymd(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function addDays(date, n) { const d = new Date(date + 'T00:00'); d.setDate(d.getDate() + n); return ymd(d); }
  function addMinutes(dt, n) { const d = new Date(dt); d.setMinutes(d.getMinutes() + n); return ymd(d) + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes()); }
  function fmtTime(dt) {
    if (!dt) return '';
    const h = Number(dt.slice(11, 13)), m = Number(dt.slice(14, 16));
    if (state.prefs.timeFormat === '12h') return (h % 12 || 12) + ':' + pad(m) + (h >= 12 ? ' PM' : ' AM');
    return pad(h) + ':' + pad(m);
  }
  function fmtDay(date) {
    if (!date) return '';
    const t = today();
    if (date === t) return 'Today';
    if (date === addDays(t, -1)) return 'Yesterday';
    if (date === addDays(t, 1)) return 'Tomorrow';
    const d = new Date(date + 'T00:00');
    return DOW[d.getDay()].slice(0, 3) + ' ' + d.getDate() + ' ' + MON[d.getMonth()].slice(0, 3);
  }
  const fmtWhen = (dt) => dt ? fmtDay(dt.slice(0, 10)) + ' ' + fmtTime(dt) : '';
  function minutesBetween(a, b) { return Math.round((new Date(b) - new Date(a)) / 60000); }
  function ago(dt) {
    if (!dt) return 'never';
    const m = minutesBetween(dt, now());
    if (m < 1) return 'just now';
    if (m < 60) return m + ' min ago';
    return fmtWhen(dt);
  }

  /* ------------------------------------------------------------ selectors */
  const Q = {
    status: (id) => state.statuses.find((s) => s.id === id) || state.statuses[0],
    cat: (topic) => topic ? Q.status(topic.statusId).category : 'planned',
    topic: (id) => state.topics.find((t) => t.id === id),
    task: (id) => state.tasks.find((t) => t.id === id),
    note: (id) => state.notes.find((n) => n.id === id),
    topicColor: (topic) => topic ? 'var(--t-' + topic.color + ')' : 'var(--t-none)',
    topicTasks: (id) => state.tasks.filter((t) => t.topicId === id),
    progress(id) { const ts = Q.topicTasks(id); return { done: ts.filter((t) => t.done).length, total: ts.length }; },
    usage: (statusId) => state.topics.filter((t) => t.statusId === statusId).length,
    defaultStatusId() { const s = state.statuses.find((x) => x.category === 'planned') || state.statuses[0]; return s.id; },
    nextColor() { return PALETTE[state.topics.length % PALETTE.length]; },

    bucket(t) {
      const n = now(), d = today();
      if (t.deadline && t.deadline < n) return 'overdue';
      if (t.planned === d) return 'planned';
      if (t.deadline && t.deadline.slice(0, 10) === d) return 'planned';
      if (t.planned && t.planned < d) return 'earlier';
      return null;
    },
    todayBuckets() {
      const out = { overdue: [], planned: [], earlier: [] };
      state.tasks.forEach((t) => {
        if (t.done && !(t.completedAt && t.completedAt.slice(0, 10) === today())) return;
        const b = Q.bucket(t);
        if (b) out[b].push(t);
      });
      const order = (a, b) => (a.deadline || a.planned || '').localeCompare(b.deadline || b.planned || '');
      Object.keys(out).forEach((k) => out[k].sort(order));
      return out;
    },
    targetOf(rem) {
      const t = rem.target;
      if (t.type === 'task') return Q.task(t.id);
      if (t.type === 'note') return Q.note(t.id);
      return Q.topic(t.id);
    },
    targetTopic(rem) {
      const tg = Q.targetOf(rem);
      if (!tg) return null;
      if (rem.target.type === 'topic') return tg;
      if (rem.target.type === 'task') return tg.topicId ? Q.topic(tg.topicId) : null;
      return tg.topicIds && tg.topicIds.length ? Q.topic(tg.topicIds[0]) : null;
    },
    remindersFor(type, id) { return state.reminders.filter((r) => r.target.type === type && r.target.id === id && !r.dismissed); },
    todayReminders() {
      return state.reminders
        .filter((r) => r.at.slice(0, 10) === today() && !r.dismissed && Q.targetOf(r))
        .sort((a, b) => a.at.localeCompare(b.at));
    },
    activeTopics: () => state.topics.filter((t) => Q.cat(t) === 'active'),
    inbox() {
      const tasks = state.tasks.filter((t) => t.inbox).map((t) => ({ kind: 'task', id: t.id, title: t.title, at: t.createdAt, item: t }));
      const notes = state.notes.filter((n) => n.inbox).map((n) => ({ kind: 'note', id: n.id, title: n.title || 'Untitled note', at: n.updatedAt, item: n }));
      return tasks.concat(notes).sort((a, b) => b.at.localeCompare(a.at));
    },
    jiraTopics: (key) => (state.links.jira[key] || []).filter((id) => Q.topic(id)),
    prTopics: (id) => (state.links.prs[id] || []).filter((tid) => Q.topic(tid)),
    topicJira: (tid) => Object.keys(state.links.jira).filter((k) => state.links.jira[k].indexOf(tid) >= 0),
    topicPrs: (tid) => Object.keys(state.links.prs).filter((k) => state.links.prs[k].indexOf(tid) >= 0),
    topicNotes: (tid) => state.notes.filter((n) => n.topicIds.indexOf(tid) >= 0),
    jiraCached: (key) => !!state.cache.jira[key],
    prCached: (id) => !!state.cache.prs[id],
    jira: (key) => D.jira[key],
    pr: (id) => D.prs[id],
    person: (k) => D.people[k] || { name: k, handle: k, initials: String(k).slice(0, 2).toUpperCase() },
    assignedJira() { return state.cache.lists.jira ? Object.keys(D.jira).filter((k) => D.jira[k].assigned && Q.jiraCached(k)) : []; },
    myPrs() { return state.cache.lists.github ? Object.keys(D.prs).filter((k) => D.prs[k].mine && Q.prCached(k)) : []; },
    reviewPrs() { return state.cache.lists.github ? Object.keys(D.prs).filter((k) => D.prs[k].requested && Q.prCached(k)) : []; },
    noteText(n) {
      const d = document.createElement('div');
      /* A space after each block keeps "Heading" and "Paragraph" from running together. */
      d.innerHTML = sanitize(n.html || '').replace(/<\/(p|h2|h3|li|blockquote|pre)>|<br\s*\/?>/gi, (m) => m + ' ');
      return d.textContent.replace(/\s+/g, ' ').trim();
    },
    sync(id) { if (!state.noteSync[id]) state.noteSync[id] = { state: 'synced' }; return state.noteSync[id]; },
    source(kind) {
      const key = kind === 'jira' ? 'jira' : 'github';
      if (!state.conn[key]) return 'disconnected';
      if (!state.sim.online) return 'offline';
      return state.sim[key];
    }
  };

  /* -------------------------------------------- sanitizing app rich text */
  const ALLOWED = { P: 1, H1: 1, H2: 1, H3: 1, B: 1, STRONG: 1, I: 1, EM: 1, U: 1, UL: 1, OL: 1, LI: 1, A: 1, BR: 1, DIV: 1, SPAN: 1, MARK: 1 };
  function sanitize(input) {
    const tpl = document.createElement('template');
    tpl.innerHTML = String(input || '');
    (function walk(node) {
      Array.prototype.slice.call(node.childNodes).forEach((child) => {
        if (child.nodeType === 1) {
          if (!ALLOWED[child.tagName]) {
            if (child.tagName === 'SCRIPT' || child.tagName === 'STYLE' || child.tagName === 'IFRAME') { child.remove(); return; }
            const frag = document.createDocumentFragment();
            while (child.firstChild) frag.appendChild(child.firstChild);
            child.replaceWith(frag);
            walk(node);
            return;
          }
          Array.prototype.slice.call(child.attributes).forEach((a) => {
            const n = a.name.toLowerCase();
            const keep = (child.tagName === 'A' && n === 'href' && /^(https?:|mailto:)/i.test(a.value.trim())) ||
              (child.tagName === 'UL' && n === 'class' && a.value === 'checklist') ||
              (child.tagName === 'LI' && n === 'data-checked') ||
              (child.tagName === 'MARK' && n === 'class');
            if (!keep) child.removeAttribute(a.name);
          });
          if (child.tagName === 'A') { child.setAttribute('rel', 'noopener noreferrer'); child.setAttribute('target', '_blank'); }
          walk(child);
        } else if (child.nodeType !== 3) {
          child.remove();
        }
      });
    })(tpl.content);
    return tpl.innerHTML;
  }

  /* --------------------------------------------------------------- router */
  function route() {
    const h = (location.hash || '').replace(/^#/, '');
    const qi = h.indexOf('?');
    const p = qi >= 0 ? h.slice(0, qi) : h;
    const qs = qi >= 0 ? h.slice(qi + 1) : '';
    const parts = p.split('/').filter(Boolean);
    return { name: parts[0] || '', id: parts[1] ? decodeURIComponent(parts[1]) : null, q: new URLSearchParams(qs), path: '/' + parts.join('/') };
  }
  function go(path) { const h = path.charAt(0) === '#' ? path : '#' + path; if (location.hash === h) render(); else location.hash = h; }
  function replaceQuery(mutate) {
    const r = route();
    mutate(r.q);
    const qs = r.q.toString();
    history.replaceState(null, '', '#' + r.path + (qs ? '?' + qs : ''));
  }
  const wide = () => window.matchMedia('(min-width: 1181px)').matches;
  const narrowNav = () => window.matchMedia('(max-width: 860px)').matches;

  /* -------------------------------------------------- focus preservation */
  function captureFocus() {
    const a = document.activeElement;
    if (!a || a === document.body) return null;
    const key = a.id ? { id: a.id } : (a.dataset && a.dataset.fk ? { fk: a.dataset.fk } : null);
    if (!key) return null;
    let sel = null;
    try { if (typeof a.selectionStart === 'number') sel = [a.selectionStart, a.selectionEnd]; } catch (e) { sel = null; }
    return { key, sel };
  }
  function restoreFocus(info) {
    if (!info) return;
    const a = document.activeElement;
    const el = info.key.id ? document.getElementById(info.key.id) : document.querySelector('[data-fk="' + info.key.fk + '"]');
    if (!el || el === a) return;
    el.focus({ preventScroll: true });
    if (info.sel && typeof el.setSelectionRange === 'function') { try { el.setSelectionRange(info.sel[0], info.sel[1]); } catch (e) { /* not a text field */ } }
  }

  /* ---------------------------------------------------------------- render */
  const T = {};
  const shellHtml = '<div class="app" id="app">' +
    '<aside class="sidebar" id="sidebar" aria-label="Sidebar"></aside>' +
    '<div class="main"><header class="topbar" id="topbar"></header>' +
    '<div class="global-banners" id="global-banners"></div>' +
    '<main class="view" id="view" tabindex="-1"></main></div></div>' +
    '<div id="scrim-host"></div><div id="overlays"></div><div id="popover-host"></div>' +
    '<div class="toasts" id="toasts" role="status" aria-live="polite"></div><div id="alarm-host"></div>';

  const TITLES = { today: 'Today', inbox: 'Inbox', topics: 'Topics', topic: 'Topic', notes: 'Notes', jira: 'Jira', prs: 'Pull requests', settings: 'Settings', trash: 'Trash', notfound: 'Not found' };
  function pageTitle(r, name) {
    if (name === 'topic' && Q.topic(r.id)) return Q.topic(r.id).name;
    if (name === 'notes' && r.id && Q.note(r.id)) return Q.note(r.id).title || 'Untitled note';
    if (name === 'jira' && r.id) return r.id;
    if (name === 'prs' && r.id && D.prs && D.prs[r.id]) return 'Pull request #' + D.prs[r.id].number;
    return TITLES[name] || 'Thread';
  }
  function render() {
    if (!state) return;
    const r = route();
    const root = document.getElementById('root');
    if (r.name === 'welcome' || (!r.name && !state.session.signedIn)) {
      root.dataset.mode = 'welcome';
      closeAllDialogs();
      root.innerHTML = flat(T.views.welcome(r));
      document.title = 'Sign in · Thread prototype';
      return;
    }
    if (!state.session.signedIn) { state.session.signedIn = true; persist(); }
    if (!r.name) { go('/' + (state.prefs.startPage || 'today')); return; }
    if (root.dataset.mode !== 'app') { root.innerHTML = shellHtml; root.dataset.mode = 'app'; }
    const focus = captureFocus();
    const name = T.views[r.name] && !/^(welcome|sidebar|topbar|globalBanners)$/.test(r.name) ? r.name : 'notfound';
    document.getElementById('app').classList.toggle('nav-open', ui.navOpen);
    const sb = document.getElementById('sidebar');
    sb.innerHTML = flat(T.views.sidebar(r));
    if (narrowNav()) { if (ui.navOpen) sb.removeAttribute('inert'); else sb.setAttribute('inert', ''); } else sb.removeAttribute('inert');
    document.getElementById('topbar').innerHTML = flat(T.views.topbar(r));
    document.getElementById('global-banners').innerHTML = flat(T.views.globalBanners(r));
    document.getElementById('scrim-host').innerHTML = ui.navOpen && narrowNav() ? '<div class="scrim" data-action="close-nav"></div>' : '';
    const host = document.getElementById('view');
    const out = T.views[name](r, host);
    if (out != null) host.innerHTML = flat(out);
    if (T.afterRender) T.afterRender(r, name);
    document.title = pageTitle(r, name) + ' · Thread prototype';
    rerenderDialogs();
    restoreFocus(focus);
    T.updateFocusClock && T.updateFocusClock();
  }

  /* --------------------------------------------------------------- dialogs */
  const openDialogs = new Map();
  function openDialog(id, renderFn, opts) {
    opts = opts || {};
    let el = document.getElementById(id);
    const active = document.activeElement;
    const returnKey = active ? (active.id ? { id: active.id } : (active.dataset && active.dataset.fk ? { fk: active.dataset.fk } : null)) : null;
    if (!el) {
      el = document.createElement('dialog');
      el.id = id;
      document.getElementById('overlays').appendChild(el);
      el.addEventListener('close', () => {
        const rec = openDialogs.get(id);
        openDialogs.delete(id);
        el.remove();
        if (rec && rec.onClose) rec.onClose();
        if (rec && rec.returnEl && document.contains(rec.returnEl)) rec.returnEl.focus();
        else if (rec && rec.returnKey) restoreFocus({ key: rec.returnKey });
      });
    }
    el.className = opts.className || 'dlg';
    if (opts.label) el.setAttribute('aria-label', opts.label);
    if (opts.labelledby) el.setAttribute('aria-labelledby', opts.labelledby);
    openDialogs.set(id, { el, render: renderFn, onClose: opts.onClose, manual: !!opts.manual, returnEl: active, returnKey });
    el.innerHTML = flat(renderFn());
    if (!el.open) el.showModal();
    const af = el.querySelector('[autofocus]');
    if (af) af.focus();
    return el;
  }
  function rerenderDialogs() {
    openDialogs.forEach((rec) => {
      if (rec.manual) return;
      const f = captureFocus();
      const scrollers = Array.prototype.map.call(rec.el.querySelectorAll('[data-keep-scroll]'), (n) => [n.dataset.keepScroll, n.scrollTop]);
      rec.el.innerHTML = flat(rec.render());
      scrollers.forEach((s) => { const n = rec.el.querySelector('[data-keep-scroll="' + s[0] + '"]'); if (n) n.scrollTop = s[1]; });
      restoreFocus(f);
    });
  }
  function closeDialog(id) { const el = document.getElementById(id); if (el && el.open) el.close(); }
  function closeAllDialogs() { openDialogs.forEach((rec) => { if (rec.el.open) rec.el.close(); }); }
  const isDialogOpen = (id) => id ? openDialogs.has(id) : openDialogs.size > 0;

  /* ---------------------------------------------------------------- toasts */
  function toast(msg, opts) {
    opts = opts || {};
    const host = document.getElementById('toasts');
    if (!host) return;
    const el = document.createElement('div');
    el.className = 'toast';
    el.innerHTML = flat(html`<p>${msg}</p>${opts.action ? html`<button class="btn btn-sm" type="button" data-t="act">${opts.action.label}</button>` : ''}<button class="btn btn-sm btn-ghost btn-icon" type="button" data-t="x" aria-label="Dismiss message">${icon('x', 'icon-sm')}</button>`);
    let timer;
    const remove = () => { clearTimeout(timer); el.remove(); };
    const arm = () => { clearTimeout(timer); timer = setTimeout(remove, opts.ms || 5200); };
    el.addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.t === 'act' && opts.action) opts.action.fn();
      remove();
    });
    el.addEventListener('mouseenter', () => clearTimeout(timer));
    el.addEventListener('mouseleave', arm);
    el.addEventListener('focusin', () => clearTimeout(timer));
    el.addEventListener('focusout', arm);
    host.appendChild(el);
    while (host.children.length > 3) host.firstChild.remove();
    arm();
  }

  /* ------------------------------------------- simulated external sources */
  const SRC_KEY = { jira: 'jira', github: 'github' };
  function canFetch(source) { return state.conn[SRC_KEY[source]] && state.sim.online && state.sim[SRC_KEY[source]] === 'ok'; }

  /* Cached-first refresh: content stays visible while this runs. */
  function refresh(source, key) {
    const id = source + ':' + (key || 'list');
    if (ui.refreshing[id] || !canFetch(source)) return;
    ui.refreshing[id] = true;
    setTimeout(() => {
      ui.refreshing[id] = false;
      if (!canFetch(source)) { render(); return; }
      ui.fresh[id] = true;
      commit((s) => {
        if (source === 'jira') {
          if (key) s.cache.jira[key] = { at: s.sim.now };
          else { s.cache.lists.jira = s.sim.now; Object.keys(D.jira).forEach((k) => { if (D.jira[k].assigned) s.cache.jira[k] = { at: s.sim.now }; }); }
        } else {
          if (key) s.cache.prs[key] = { at: s.sim.now };
          else { s.cache.lists.github = s.sim.now; Object.keys(D.prs).forEach((k) => { if (D.prs[k].mine || D.prs[k].requested) s.cache.prs[k] = { at: s.sim.now }; }); }
        }
      });
    }, 1100);
  }
  /* First download of an item that is not cached on this device. */
  function fetchItem(source, key) {
    const id = source + ':' + key;
    if (ui.loading[id] || !canFetch(source)) return;
    ui.loading[id] = true;
    setTimeout(() => {
      ui.loading[id] = false;
      if (!canFetch(source)) { render(); return; }
      ui.fresh[id] = true;
      commit((s) => { if (source === 'jira') s.cache.jira[key] = { at: s.sim.now }; else s.cache.prs[key] = { at: s.sim.now }; });
    }, 800);
  }

  /* ------------------------------------------------- simulated note sync */
  const saveTimers = {};
  const syncTimers = {};
  function noteChanged(id) {
    const ns = Q.sync(id);
    if (ns.state !== 'saving') { ns.state = 'saving'; render(); }
    clearTimeout(saveTimers[id]);
    saveTimers[id] = setTimeout(() => saveNote(id), 450);
  }
  function saveNote(id) {
    const ns = Q.sync(id);
    if (state.sim.saveFail) { ns.state = 'local-failed'; render(); return; }
    ns.state = 'local';
    ns.savedAt = state.sim.now;
    persist();
    render();
    if (!ns.conflict) setTimeout(() => startSync(id), 400);
  }
  function startSync(id) {
    const ns = Q.sync(id);
    if (ns.conflict || ns.state === 'saving' || ns.state === 'local-failed') return;
    if (!state.sim.online) { ns.state = 'local'; persist(); render(); return; }
    ns.state = 'syncing';
    render();
    clearTimeout(syncTimers[id]);
    syncTimers[id] = setTimeout(() => {
      const cur = Q.sync(id);
      if (cur.state !== 'syncing') return;
      if (!state.sim.online) cur.state = 'local';
      else if (state.sim.syncFail) cur.state = 'sync-failed';
      else { cur.state = 'synced'; cur.syncedAt = state.sim.now; }
      persist();
      render();
    }, 1200);
  }
  function syncPending() {
    state.notes.forEach((n) => {
      const ns = state.noteSync[n.id];
      if (ns && (ns.state === 'local' || ns.state === 'sync-failed') && !ns.conflict) startSync(n.id);
    });
  }

  /* ------------------------------------------------------------- shortcuts */
  const isTyping = (el) => !!el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName));

  function bindEvents() {
    document.addEventListener('click', (e) => {
      const a = e.target.closest('[data-action]');
      if (!a || a.disabled || a.getAttribute('aria-disabled') === 'true') return;
      const fn = T.actions[a.dataset.action];
      if (!fn) return;
      if (a.tagName === 'A' && !a.dataset.navigate) e.preventDefault();
      fn(a, e);
    });
    document.addEventListener('change', (e) => {
      const el = e.target.closest('[data-change]');
      if (el && T.changes[el.dataset.change]) T.changes[el.dataset.change](el, e);
    });
    document.addEventListener('input', (e) => {
      const el = e.target.closest('[data-input]');
      if (el && T.inputs[el.dataset.input]) T.inputs[el.dataset.input](el, e);
    });
    document.addEventListener('submit', (e) => {
      const f = e.target.closest('form[data-form]');
      if (!f) return;
      e.preventDefault();
      if (T.forms[f.dataset.form]) T.forms[f.dataset.form](f, e);
    });
    document.addEventListener('toggle', (e) => {
      const d = e.target;
      if (d && d.matches && d.matches('details[data-sec]')) ui.sections[d.dataset.sec] = d.open;
    }, true);
    document.addEventListener('keydown', (e) => {
      const k = e.key ? e.key.toLowerCase() : '';
      if ((e.metaKey || e.ctrlKey) && k === 'k') {
        e.preventDefault();
        if (document.getElementById('root').dataset.mode === 'app') T.actions['open-search']();
        return;
      }
      if (e.key === 'Escape') {
        if (T.closePopover && T.closePopover()) { e.preventDefault(); return; }
        if (ui.navOpen) { ui.navOpen = false; render(); const b = document.getElementById('nav-toggle'); if (b) b.focus(); return; }
      }
      if (e.metaKey || e.ctrlKey || e.altKey || isTyping(e.target) || isDialogOpen()) return;
      if (document.getElementById('root').dataset.mode !== 'app') return;
      if (k === 'c') { e.preventDefault(); T.actions['open-capture'](); }
      else if (k === '/') { e.preventDefault(); T.actions['open-search'](); }
    });
    window.addEventListener('hashchange', onHash);
    const mq = window.matchMedia('(min-width: 1181px)');
    const mq2 = window.matchMedia('(max-width: 860px)');
    const onMq = () => { if (!narrowNav()) ui.navOpen = false; render(); };
    if (mq.addEventListener) { mq.addEventListener('change', onMq); mq2.addEventListener('change', onMq); }
    window.addEventListener('storage', (e) => {
      if (e.key === STORE_KEY && e.newValue) { try { const p = JSON.parse(e.newValue); if (p && p.v === 1) { state = p; render(); } } catch (err) { /* ignore */ } }
    });
  }

  function onHash() {
    const r = route();
    const pathChanged = r.path !== ui.lastPath;
    ui.lastPath = r.path;
    if (ui.navOpen) ui.navOpen = false;
    if (T.closePopover) T.closePopover();
    render();
    if (pathChanged) {
      window.scrollTo(0, 0);
      const v = document.getElementById('view');
      if (v && document.activeElement && !document.activeElement.closest('dialog')) v.focus({ preventScroll: true });
    }
    T.onRoute && T.onRoute(r, pathChanged);
    handleOpenParams();
  }

  /* One-shot deep link helpers, for example ?open=search or ?task=k2 */
  function handleOpenParams() {
    const r = route();
    const open = r.q.get('open');
    const task = r.q.get('task');
    if (!open && !task) return;
    replaceQuery((q) => { q.delete('open'); q.delete('task'); });
    setTimeout(() => {
      if (task && T.openTask) T.openTask(task);
      if (open === 'search') T.actions['open-search']();
      if (open === 'capture') T.actions['open-capture']();
      if (open === 'focus') T.actions['toggle-focus']();
      if (open === 'demo') T.actions['open-demo']();
    }, 30);
  }

  function resetState(kind) {
    const signed = state ? state.session.signedIn : false;
    state = freshState(kind);
    state.session.signedIn = signed;
    persistNow();
  }

  function start() {
    window.ThreadIcons.mount();
    state = load();
    bindEvents();
    ui.lastPath = route().path;
    render();
    T.onRoute && T.onRoute(route(), true);
    handleOpenParams();
    setInterval(() => { T.tick && T.tick(); }, 1000);
  }

  Object.assign(T, {
    html, raw, esc, flat, icon, glyph, Q, ui, commit, render, persist, persistNow, uid, route, go, replaceQuery, wide, narrowNav,
    openDialog, closeDialog, closeAllDialogs, isDialogOpen, rerenderDialogs, toast, refresh, fetchItem, canFetch,
    noteChanged, saveNote, startSync, syncPending, sanitize, resetState, freshState,
    now, today, addDays, addMinutes, fmtTime, fmtDay, fmtWhen, ago, minutesBetween, pad, DOW, MON,
    PALETTE, CAT_LABEL, CAT_HELP, D, STORE_KEY,
    views: {}, actions: {}, changes: {}, inputs: {}, forms: {}, start
  });
  /* Live getters. Object.assign would copy their value once, so define them. */
  Object.defineProperty(T, 'state', { get: () => state, enumerable: true });
  Object.defineProperty(T, 'storageOk', { get: () => storageOk, enumerable: true });
  window.Thread = T;
})();
