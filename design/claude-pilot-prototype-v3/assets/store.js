/* Thread v3 demo store: one state object in localStorage, shared by every page.
   This is prototype plumbing, not a proposal for the production data model or sync. */
(function () {
  const T = window.T;
  const KEY = 'thread-v3-demo';
  const listeners = [];

  function load() {
    try {
      const raw = localStorage.getItem(KEY);
      if (raw) {
        const s = JSON.parse(raw);
        if (s && s.version === T.SEED_VERSION) return s;
      }
    } catch (e) { /* storage blocked or corrupt: fall back to seed */ }
    return T.makeSeed('example');
  }

  T.state = load();
  T.storageOk = true;

  T.persist = function () {
    try {
      localStorage.setItem(KEY, JSON.stringify(T.state));
      T.storageOk = true;
    } catch (e) {
      T.storageOk = false;
    }
    return T.storageOk;
  };
  T.commit = function (source) {
    T.persist();
    listeners.forEach((fn) => fn(source || 'local'));
  };
  T.subscribe = function (fn) { listeners.push(fn); };
  T.reset = function (kind) {
    const session = T.state.session;
    T.state = T.makeSeed(kind || 'example');
    T.state.session = session;
    T.commit('reset');
  };
  window.addEventListener('storage', (e) => {
    if (e.key !== KEY || !e.newValue) return;
    try {
      T.state = JSON.parse(e.newValue);
      listeners.forEach((fn) => fn('external'));
    } catch (err) { /* ignore */ }
  });

  T.uid = function (prefix) {
    T.state.seq = (T.state.seq || 100) + 1;
    return prefix + T.state.seq;
  };

  /* ---------------- Dates (demo clock, local naive times) ---------------- */
  const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  const pad = (n) => String(n).padStart(2, '0');
  const D = (T.D = {
    parse(s) {
      const parts = String(s).split('T');
      const [y, m, d] = parts[0].split('-').map(Number);
      const [hh, mi] = (parts[1] || '00:00').split(':').map(Number);
      return new Date(y, m - 1, d, hh || 0, mi || 0);
    },
    key(date) { return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()); },
    iso(date) { return D.key(date) + 'T' + pad(date.getHours()) + ':' + pad(date.getMinutes()); },
    now() { return D.parse(T.state.demo.now); },
    today() { return T.state.demo.now.slice(0, 10); },
    addDays(key, n) { const d = D.parse(key); d.setDate(d.getDate() + n); return D.key(d); },
    time(iso) { return iso ? iso.slice(11, 16) : ''; },
    longDate(key) { const d = D.parse(key); return DAYS[d.getDay()] + ', ' + d.getDate() + ' ' + MONTHS[d.getMonth()]; },
    shortDate(key) { const d = D.parse(key); return DAYS[d.getDay()].slice(0, 3) + ' ' + d.getDate() + ' ' + MONTHS[d.getMonth()].slice(0, 3); },
    /* mid = true when the word sits inside a sentence ("Planned tomorrow"). */
    day(key, mid) {
      const t = D.today();
      const word = key === t ? 'Today' : key === D.addDays(t, -1) ? 'Yesterday' : key === D.addDays(t, 1) ? 'Tomorrow' : null;
      if (!word) return D.shortDate(key);
      return mid ? word.toLowerCase() : word;
    },
    when(iso, mid) { return D.day(iso.slice(0, 10), mid) + ' ' + D.time(iso); },
    ago(iso) {
      const mins = Math.round((D.now() - D.parse(iso)) / 60000);
      if (mins < 1) return 'just now';
      if (mins < 60) return mins + ' min ago';
      if (mins < 60 * 24 && iso.slice(0, 10) === D.today()) return 'today ' + D.time(iso);
      return D.when(iso, true);
    }
  });

  /* ---------------- Selectors ---------------- */
  const S = (T.sel = {
    topic(id) { return T.state.topics.find((t) => t.id === id) || null; },
    status(id) { return T.state.statuses.find((s) => s.id === id) || null; },
    statusOf(topic) { return S.status(topic.statusId) || T.state.statuses[0]; },
    cat(topic) { const s = S.statusOf(topic); return s ? s.category : 'planned'; },
    task(id) { return T.state.tasks.find((t) => t.id === id) || null; },
    note(id) { return T.state.notes.find((n) => n.id === id) || null; },
    reminder(id) { return T.state.reminders.find((r) => r.id === id) || null; },
    jira(key) { return T.jiraIssues.find((j) => j.key === key) || null; },
    pr(id) { return T.pullRequests.find((p) => p.id === id) || null; },
    topicTasks(id) { return T.state.tasks.filter((t) => t.topicId === id); },
    progress(id) {
      const ts = S.topicTasks(id);
      return { done: ts.filter((t) => t.done).length, total: ts.length, tasks: ts };
    },
    topicsInOrder() {
      const order = T.state.statuses.map((s) => s.id);
      return T.state.topics.slice().sort((a, b) => order.indexOf(a.statusId) - order.indexOf(b.statusId));
    },
    activeTopics() { return S.topicsInOrder().filter((t) => S.cat(t) === 'active'); },
    statusUseCount(id) { return T.state.topics.filter((t) => t.statusId === id).length; },
    notesForTopic(id) { return T.state.notes.filter((n) => n.topicIds.includes(id)); },
    linksOf(kind, id) {
      return (T.state.links[kind + ':' + id] || []).map(S.topic).filter(Boolean);
    },
    externalsForTopic(topicId, kind) {
      return Object.keys(T.state.links)
        .filter((k) => k.startsWith(kind + ':') && T.state.links[k].includes(topicId))
        .map((k) => k.slice(kind.length + 1));
    },
    remindersFor(kind, id) {
      return T.state.reminders.filter((r) => r.target.kind === kind && r.target.id === id)
        .sort((a, b) => a.at.localeCompare(b.at));
    },
    remindersForTopic(topicId) {
      return T.state.reminders.filter((r) => {
        if (r.target.kind === 'topic') return r.target.id === topicId;
        if (r.target.kind === 'task') { const t = S.task(r.target.id); return t && t.topicId === topicId; }
        if (r.target.kind === 'note') { const n = S.note(r.target.id); return n && n.topicIds.includes(topicId); }
        return false;
      }).sort((a, b) => a.at.localeCompare(b.at));
    },
    targetOf(r) {
      const k = r.target.kind;
      if (k === 'task') { const t = S.task(r.target.id); return t ? { kind: k, label: t.title, topic: S.topic(t.topicId), obj: t } : null; }
      if (k === 'note') { const n = S.note(r.target.id); return n ? { kind: k, label: n.title || 'Untitled note', topic: S.topic(n.topicIds[0]), obj: n } : null; }
      const tp = S.topic(r.target.id);
      return tp ? { kind: 'topic', label: tp.name, topic: tp, obj: tp } : null;
    },
    /* Today grouping (proposal): each Task appears once.
       Overdue = deadline passed. Today = planned today or due later today. Earlier planned = planned before today. */
    taskGroup(t) {
      const today = D.today();
      if (t.deadline && D.parse(t.deadline) < D.now()) return 'overdue';
      if (t.plannedDay === today || (t.deadline && t.deadline.slice(0, 10) === today)) return 'today';
      if (t.plannedDay && t.plannedDay < today) return 'earlier';
      return null;
    },
    todayGroups() {
      const today = D.today();
      const g = { overdue: [], today: [], earlier: [] };
      T.state.tasks.forEach((t) => {
        if (t.inbox && !t.plannedDay && !t.deadline) return;
        const showDone = t.done && T.state.prefs.keepCompleted && t.completedAt && t.completedAt.slice(0, 10) === today;
        if (t.done && !showDone) return;
        let grp = S.taskGroup(t);
        if (t.done && (grp === 'overdue' || !grp)) grp = 'today';
        if (grp) g[grp].push(t);
      });
      g.overdue.sort((a, b) => a.deadline.localeCompare(b.deadline));
      g.today.sort((a, b) => Number(a.done) - Number(b.done) || (a.deadline || 'z').localeCompare(b.deadline || 'z'));
      g.earlier.sort((a, b) => a.plannedDay.localeCompare(b.plannedDay));
      return g;
    },
    todayReminders() {
      const today = D.today();
      const now = T.state.demo.now;
      return T.state.reminders
        .filter((r) => r.at.slice(0, 10) === today && S.targetOf(r))
        .sort((a, b) => a.at.localeCompare(b.at))
        .map((r) => ({ r, past: r.at <= now }));
    },
    inboxItems() {
      const items = [];
      T.state.tasks.forEach((t) => { if (t.inbox) items.push({ kind: 'task', id: t.id, title: t.title, at: t.createdAt, obj: t }); });
      T.state.notes.forEach((n) => { if (n.inbox) items.push({ kind: 'note', id: n.id, title: n.title || 'Untitled note', at: n.updatedAt, obj: n }); });
      return items.sort((a, b) => b.at.localeCompare(a.at));
    },
    reviewRequested() { return T.state.connections.github ? T.pullRequests.filter((p) => p.reviewRequested) : []; },
    myPRs() { return T.state.connections.github ? T.pullRequests.filter((p) => p.mine) : []; },
    jiraDownloaded(j) { return j.cached || T.state.demo.jiraDownloaded.includes(j.key); }
  });

  /* ---------------- Actions ---------------- */
  const PALETTE = ['lavender', 'mint', 'sky', 'peach', 'butter'];
  const A = (T.act = {
    toggleTask(id) {
      const t = S.task(id);
      if (!t) return;
      t.done = !t.done;
      t.completedAt = t.done ? T.state.demo.now : null;
      T.commit();
    },
    /* Undo of a just-created task: removes it without a Trash entry. */
    discardTask(id) {
      T.state.tasks = T.state.tasks.filter((t) => t.id !== id);
      T.commit();
    },
    updateTask(id, patch) {
      const t = S.task(id);
      if (!t) return;
      Object.assign(t, patch);
      T.commit();
    },
    createTask({ title, topicId, plannedDay }) {
      const t = { id: T.uid('k'), title, topicId: topicId || null, done: false, plannedDay: plannedDay || null,
        deadline: null, inbox: !topicId, createdAt: T.state.demo.now };
      T.state.tasks.push(t);
      T.commit();
      return t;
    },
    createNote({ title, topicIds }) {
      const ids = topicIds || [];
      const n = { id: T.uid('n'), title: title || '', topicIds: ids, inbox: ids.length === 0, updatedAt: T.state.demo.now, body: '<p><br></p>' };
      T.state.notes.unshift(n);
      T.state.demo.noteSync[n.id] = T.state.demo.online ? 'synced' : 'offline';
      T.commit();
      return n;
    },
    createTopic({ name }) {
      const planned = T.state.statuses.find((s) => s.category === 'planned') || T.state.statuses[0];
      const tp = { id: T.uid('t-'), name, color: PALETTE[T.state.topics.length % PALETTE.length], statusId: planned.id,
        goal: '', leftOff: '', nextStep: '' };
      T.state.topics.push(tp);
      T.commit();
      return tp;
    },
    updateTopic(id, patch) {
      const tp = S.topic(id);
      if (!tp) return;
      Object.assign(tp, patch);
      T.commit();
    },
    setTopicStatus(id, statusId) { A.updateTopic(id, { statusId }); },
    triage(items, topicId) {
      items.forEach(({ kind, id }) => {
        if (kind === 'task') {
          const t = S.task(id);
          if (!t) return;
          if (topicId) t.topicId = topicId;
          t.inbox = false;
        } else {
          const n = S.note(id);
          if (!n) return;
          if (topicId && !n.topicIds.includes(topicId)) n.topicIds.push(topicId);
          n.inbox = false;
        }
      });
      T.commit();
    },
    untriage(items, topicId) {
      items.forEach(({ kind, id }) => {
        if (kind === 'task') {
          const t = S.task(id);
          if (!t) return;
          if (topicId && t.topicId === topicId) t.topicId = null;
          t.inbox = true;
        } else {
          const n = S.note(id);
          if (!n) return;
          if (topicId) n.topicIds = n.topicIds.filter((x) => x !== topicId);
          n.inbox = true;
        }
      });
      T.commit();
    },
    setExternalLinks(kind, id, topicIds) {
      const k = kind + ':' + id;
      if (topicIds.length) T.state.links[k] = topicIds.slice();
      else delete T.state.links[k];
      T.commit();
    },
    linkExternal(kind, id, topicId) {
      const k = kind + ':' + id;
      const cur = T.state.links[k] || [];
      if (!cur.includes(topicId)) T.state.links[k] = cur.concat(topicId);
      T.commit();
    },
    unlinkExternal(kind, id, topicId) {
      const k = kind + ':' + id;
      const next = (T.state.links[k] || []).filter((x) => x !== topicId);
      if (next.length) T.state.links[k] = next;
      else delete T.state.links[k];
      T.commit();
    },
    setNoteTopics(noteId, topicIds) {
      const n = S.note(noteId);
      if (!n) return;
      n.topicIds = topicIds.slice();
      if (topicIds.length) n.inbox = false;
      T.commit();
    },
    linkNote(noteId, topicId) {
      const n = S.note(noteId);
      if (!n || n.topicIds.includes(topicId)) return;
      n.topicIds.push(topicId);
      n.inbox = false;
      T.commit();
    },
    unlinkNote(noteId, topicId) {
      const n = S.note(noteId);
      if (!n) return;
      n.topicIds = n.topicIds.filter((x) => x !== topicId);
      T.commit();
    },
    trash(kind, id) {
      const coll = kind === 'note' ? T.state.notes : kind === 'task' ? T.state.tasks : T.state.reminders;
      const idx = coll.findIndex((x) => x.id === id);
      if (idx < 0) return null;
      const item = coll.splice(idx, 1)[0];
      let topicIds = [];
      if (kind === 'note') topicIds = item.topicIds.slice();
      if (kind === 'task' && item.topicId) topicIds = [item.topicId];
      if (kind === 'reminder' && item.target.kind === 'topic') topicIds = [item.target.id];
      const entry = { id: T.uid('tr'), kind, deletedAt: T.state.demo.now, context: { topicIds }, item };
      T.state.trash.unshift(entry);
      T.commit();
      return entry;
    },
    /* Topic to Trash (owner decision, 2026-11-13). Its Tasks, their Reminders and the Topic's own Reminders go with it.
       Notes are never deleted: only their link to this Topic is removed, so a note linked only here becomes standalone.
       Jira and PR links are removed; external records are not touched. Restore brings all of it back. */
    trashTopic(id) {
      const index = T.state.topics.findIndex((x) => x.id === id);
      if (index < 0) return null;
      const topic = T.state.topics.splice(index, 1)[0];
      const tasks = T.state.tasks.filter((t) => t.topicId === id);
      const taskIds = tasks.map((t) => t.id);
      T.state.tasks = T.state.tasks.filter((t) => t.topicId !== id);
      const goesWith = (r) => (r.target.kind === 'topic' && r.target.id === id) || (r.target.kind === 'task' && taskIds.includes(r.target.id));
      const reminders = T.state.reminders.filter(goesWith);
      T.state.reminders = T.state.reminders.filter((r) => !goesWith(r));
      /* Remember where this Topic sat in each list, so Restore keeps a note's first Topic first. */
      const noteIds = [];
      const at = {};
      T.state.notes.forEach((n) => {
        if (!n.topicIds.includes(id)) return;
        noteIds.push(n.id);
        at[n.id] = n.topicIds.indexOf(id);
        n.topicIds = n.topicIds.filter((x) => x !== id);
      });
      const linkKeys = [];
      Object.keys(T.state.links).forEach((k) => {
        if (!T.state.links[k].includes(id)) return;
        linkKeys.push(k);
        at[k] = T.state.links[k].indexOf(id);
        const next = T.state.links[k].filter((x) => x !== id);
        if (next.length) T.state.links[k] = next;
        else delete T.state.links[k];
      });
      const status = S.status(topic.statusId);
      const entry = { id: T.uid('tr'), kind: 'topic', deletedAt: T.state.demo.now,
        context: { topicIds: [], index, noteIds, linkKeys, at, statusCategory: status ? status.category : 'planned' },
        item: topic, bundle: { tasks, reminders } };
      T.state.trash.unshift(entry);
      T.commit();
      return entry;
    },
    restore(trashId) {
      const idx = T.state.trash.findIndex((x) => x.id === trashId);
      if (idx < 0) return null;
      const entry = T.state.trash.splice(idx, 1)[0];
      const item = entry.item;
      const lost = [];
      if (entry.kind === 'note') {
        item.topicIds = item.topicIds.filter((id) => { const ok = !!S.topic(id); if (!ok) lost.push(id); return ok; });
        T.state.notes.unshift(item);
      } else if (entry.kind === 'task') {
        if (item.topicId && !S.topic(item.topicId)) { lost.push(item.topicId); item.topicId = null; }
        T.state.tasks.push(item);
      } else if (entry.kind === 'topic') {
        const ctx = entry.context;
        /* Its status may have been deleted meanwhile: fall back to a status of the same category. */
        if (!S.status(item.statusId)) {
          const same = T.state.statuses.find((s) => s.category === ctx.statusCategory) || T.state.statuses[0];
          item.statusId = same.id;
        }
        T.state.topics.splice(Math.min(ctx.index, T.state.topics.length), 0, item);
        entry.bundle.tasks.forEach((t) => T.state.tasks.push(t));
        entry.bundle.reminders.forEach((r) => T.state.reminders.push(r));
        const putBack = (list, key) => { if (list.includes(item.id)) return list; const next = list.slice(); next.splice(Math.min((ctx.at || {})[key] ?? next.length, next.length), 0, item.id); return next; };
        ctx.noteIds.forEach((nid) => {
          const n = S.note(nid);
          if (!n) { lost.push(nid); return; } /* that note was deleted on its own afterwards */
          n.topicIds = putBack(n.topicIds, nid);
          n.inbox = false;
        });
        ctx.linkKeys.forEach((k) => { T.state.links[k] = putBack(T.state.links[k] || [], k); });
      } else {
        T.state.reminders.push(item);
      }
      T.commit();
      return { entry, lost };
    },
    purge(trashId) {
      T.state.trash = T.state.trash.filter((x) => x.id !== trashId);
      T.commit();
    },
    addStatus(name, category) {
      const s = { id: T.uid('s-'), name, category };
      T.state.statuses.push(s);
      T.commit();
      return s;
    },
    updateStatus(id, patch) {
      const s = S.status(id);
      if (!s) return;
      Object.assign(s, patch);
      T.commit();
    },
    moveStatus(id, delta) {
      const arr = T.state.statuses;
      const i = arr.findIndex((s) => s.id === id);
      const j = i + delta;
      if (i < 0 || j < 0 || j >= arr.length) return;
      const [s] = arr.splice(i, 1);
      arr.splice(j, 0, s);
      T.commit();
    },
    deleteStatus(id, replacementId) {
      T.state.topics.forEach((t) => { if (t.statusId === id) t.statusId = replacementId; });
      T.state.statuses = T.state.statuses.filter((s) => s.id !== id);
      T.commit();
    },
    addReminder({ at, text, target }) {
      const r = { id: T.uid('r'), at, text, target };
      T.state.reminders.push(r);
      T.commit();
      return r;
    },
    dismissReminder(id) {
      if (!T.state.demo.dismissedReminders.includes(id)) T.state.demo.dismissedReminders.push(id);
      T.commit();
    },
    advanceToNextReminder() {
      const now = T.state.demo.now;
      const next = T.state.reminders
        .filter((r) => r.at > now && S.targetOf(r))
        .sort((a, b) => a.at.localeCompare(b.at))[0];
      if (!next) return null;
      T.state.demo.now = next.at;
      if (!T.state.demo.firedReminders.includes(next.id)) T.state.demo.firedReminders.push(next.id);
      T.commit();
      return next;
    },
    resetClock() {
      T.state.demo.now = '2026-11-13T13:05';
      T.state.demo.firedReminders = [];
      T.state.demo.dismissedReminders = [];
      T.commit();
    },
    /* Conflict resolution. Nothing is lost: the version not kept goes to Trash, or both stay as two notes. */
    resolveConflict(id, choice) {
      const n = S.note(id);
      if (!n) return null;
      const other = T.otherDeviceVersion(n);
      const copy = (title, body, at) => ({ id: T.uid('n'), title, topicIds: n.topicIds.slice(), inbox: false, updatedAt: at, body });
      const toTrash = (item, reason, from) => T.state.trash.unshift({ id: T.uid('tr'), kind: 'note', deletedAt: T.state.demo.now,
        context: { topicIds: n.topicIds.slice(), reason, from }, item });
      let extra = null;
      if (choice === 'local') {
        toTrash(copy(other.title, other.body, other.editedAt), 'Not kept when you resolved a conflict. This was the ' + other.device + ' version.', other.device + ' version');
      } else if (choice === 'other') {
        toTrash(copy(n.title, n.body, n.updatedAt), 'Not kept when you resolved a conflict. This was the version from this device.', 'This device version');
        n.title = other.title;
        n.body = other.body;
      } else {
        extra = copy(other.title + ' (' + other.device + ' copy)', other.body, other.editedAt);
        T.state.notes.unshift(extra);
      }
      n.updatedAt = T.state.demo.now;
      delete T.state.demo.noteDemo[id];
      N.set(id, 'saving');
      if (extra) N.set(extra.id, 'saving');
      T.commit('local');
      setTimeout(() => { N.run(id); if (extra) N.run(extra.id); }, 450);
      return extra;
    },
    setPref(key, val) { T.state.prefs[key] = val; T.commit(); },
    setIntegration(kind, val) { T.state.demo[kind] = val; T.commit(); },
    setConnection(kind, on) {
      T.state.connections[kind] = on;
      T.state.demo[kind === 'jira' ? 'jira' : 'github'] = 'fresh';
      T.commit();
    },
    signIn(mode, workspace) {
      if (workspace && workspace !== T.state.workspace) T.state = T.makeSeed(workspace);
      T.state.session = { mode };
      T.commit();
    },
    signOut() { T.state.session = null; T.commit(); }
  });

  /* ---------------- Note save/sync simulation ----------------
     States: saving | offline | syncing | synced | local-failed | sync-failed | conflict
     "Synced" is only shown after the simulated server acknowledgement. */
  const timers = {};
  const N = (T.noteSync = {
    get(id) { return T.state.demo.noteSync[id] || 'synced'; },
    set(id, st) { T.state.demo.noteSync[id] = st; },
    pinned(id) { return T.state.demo.noteDemo[id] || ''; },
    run(id) {
      clearTimeout(timers[id]);
      const pin = N.pinned(id);
      if (pin === 'local-failed' || !T.storageOk) { N.set(id, 'local-failed'); T.commit('sync'); return; }
      if (pin === 'sync-failed' || pin === 'conflict') { N.set(id, pin); T.commit('sync'); return; }
      if (T.state.session && T.state.session.mode === 'skip') { N.set(id, 'offline'); T.commit('sync'); return; }
      if (!T.state.demo.online) { N.set(id, 'offline'); T.commit('sync'); return; }
      N.set(id, 'syncing');
      T.commit('sync');
      timers[id] = setTimeout(() => {
        if (N.get(id) !== 'syncing') return;
        if (!T.state.demo.online) { N.set(id, 'offline'); T.commit('sync'); return; }
        N.set(id, 'synced');
        T.commit('sync');
      }, 1400);
    },
    edited(id, patch) {
      const n = S.note(id);
      if (!n) return;
      Object.assign(n, patch, { updatedAt: T.state.demo.now });
      if (N.pinned(id) && !['local-failed', 'sync-failed', 'conflict'].includes(N.pinned(id))) delete T.state.demo.noteDemo[id];
      N.set(id, 'saving');
      T.commit('editor');
      clearTimeout(timers[id]);
      timers[id] = setTimeout(() => N.run(id), 450);
    },
    pin(id, st) {
      clearTimeout(timers[id]);
      if (st === 'live') {
        delete T.state.demo.noteDemo[id];
        N.run(id);
        return;
      }
      T.state.demo.noteDemo[id] = st;
      N.set(id, st);
      T.commit('sync');
    },
    retry(id) {
      delete T.state.demo.noteDemo[id];
      N.set(id, 'saving');
      T.commit('sync');
      timers[id] = setTimeout(() => N.run(id), 450);
    },
    setOnline(on) {
      T.state.demo.online = on;
      T.commit('sync');
      Object.keys(T.state.demo.noteSync).forEach((id) => {
        const st = N.get(id);
        if (on && st === 'offline') N.run(id);
        if (!on && st === 'syncing') { N.set(id, 'offline'); }
      });
      T.commit('sync');
    },
    overall() {
      const order = ['conflict', 'local-failed', 'sync-failed', 'saving', 'offline', 'syncing'];
      const states = T.state.notes.map((n) => N.get(n.id));
      for (const o of order) if (states.includes(o)) return o;
      return T.state.demo.online ? 'synced' : 'offline';
    },
    problems() {
      return T.state.notes.filter((n) => ['conflict', 'local-failed', 'sync-failed'].includes(N.get(n.id)));
    },
    resume() {
      Object.keys(T.state.demo.noteSync).forEach((id) => {
        const st = N.get(id);
        if (st === 'saving' || st === 'syncing') N.run(id);
      });
    }
  });

  /* ---------------- Local search ---------------- */
  T.searchAll = function (q) {
    const s = q.trim().toLowerCase();
    if (!s) return null;
    const has = (txt) => String(txt || '').toLowerCase().includes(s);
    const strip = (html) => String(html || '').replace(/<[^>]+>/g, ' ');
    return {
      topics: T.state.topics.filter((t) => has(t.name) || has(t.nextStep) || has(t.goal)).slice(0, 6),
      tasks: T.state.tasks.filter((t) => has(t.title)).slice(0, 6),
      notes: T.state.notes.filter((n) => has(n.title) || has(strip(n.body))).slice(0, 6),
      jira: T.state.connections.jira ? T.jiraIssues.filter((j) => S.jiraDownloaded(j) && (has(j.key) || has(j.title))).slice(0, 6) : [],
      prs: T.state.connections.github ? T.pullRequests.filter((p) => has(p.title) || has('#' + p.number) || has(p.repo)).slice(0, 6) : []
    };
  };
})();
