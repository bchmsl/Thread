/* Thread v3 shell: the bottom status line (views, capture, search, focus, sync), shared components,
   dialogs and action wiring. Every page loads this after icons.js, data.js and store.js. */
(function () {
  const T = window.T;
  const S = T.sel;
  const D = T.D;

  /* ---------------- Safe HTML ---------------- */
  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = (T.esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ESC[c]));
  function Raw(s) { this.s = s; }
  Raw.prototype.toString = function () { return this.s; };
  T.raw = (s) => new Raw(String(s));
  const fmt = (v) => {
    if (v == null || v === false) return '';
    if (v instanceof Raw) return v.s;
    if (Array.isArray(v)) return v.map(fmt).join('');
    return esc(v);
  };
  const html = (T.html = (strings, ...vals) =>
    new Raw(strings.reduce((out, str, i) => out + str + (i < vals.length ? fmt(vals[i]) : ''), '')));
  const I = (name, size) => T.raw(T.icon(name, size));
  T.I = I;

  /* Rich text from our own editor is still sanitized before display. */
  const ALLOWED = { H1: 1, H2: 1, H3: 1, P: 1, B: 1, STRONG: 1, I: 1, EM: 1, U: 1, UL: 1, OL: 1, LI: 1, A: 1, BR: 1, DIV: 1, SPAN: 1, CODE: 1 };
  T.sanitize = function (input) {
    const doc = new DOMParser().parseFromString('<div>' + (input || '') + '</div>', 'text/html');
    const root = doc.body.firstChild;
    (function walk(node) {
      Array.from(node.childNodes).forEach((ch) => {
        if (ch.nodeType === 3) return;
        if (ch.nodeType !== 1 || !ALLOWED[ch.tagName]) {
          if (ch.nodeType === 1 && !['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT'].includes(ch.tagName)) {
            while (ch.firstChild) node.insertBefore(ch.firstChild, ch);
          }
          ch.remove();
          return;
        }
        Array.from(ch.attributes).forEach((a) => {
          const n = a.name.toLowerCase();
          const keep = (ch.tagName === 'A' && n === 'href' && /^(https?:|mailto:)/i.test(a.value)) ||
            (n === 'class' && a.value === 'checklist') || n === 'data-checked';
          if (!keep) ch.removeAttribute(a.name);
        });
        if (ch.tagName === 'A') { ch.setAttribute('rel', 'noopener noreferrer'); ch.setAttribute('target', '_blank'); }
        walk(ch);
      });
    })(root);
    return root.innerHTML;
  };
  /* Block ends become spaces so "States" + "Active" do not run together. */
  /* "Card freeze flow", "A and B", or "3 Topics" for toasts. */
  T.topicsPhrase = (ids) => {
    const names = ids.map((id) => T.sel.topic(id)).filter(Boolean).map((t) => t.name);
    return names.length === 1 ? names[0] : names.length === 2 ? names[0] + ' and ' + names[1] : names.length + ' Topics';
  };
  T.plain = (h) => {
    const d = document.createElement('div');
    d.innerHTML = T.sanitize(h).replace(/<\/(h1|h2|h3|p|li|div)>|<br\s*\/?>/gi, '$&\u0001');
    /* Block ends read as sentence breaks: "States. Active. Frozen." instead of "States Active Frozen". */
    return (d.textContent || '').replace(/\s+/g, ' ').replace(/\s*\u0001[\s\u0001]*/g, (m, i, s) => {
      const prev = s.slice(0, i).trimEnd().slice(-1);
      return !prev || i + m.length >= s.length ? ' ' : /[.!?:;,]/.test(prev) ? ' ' : '. ';
    }).trim();
  };

  /* ---------------- Topic identity: hex sticker ---------------- */
  const HEX = '50,4 89.8,27 89.8,73 50,96 10.2,73 10.2,27';
  const STOP = new Set(['to', 'the', 'a', 'an', 'of', 'and', 'for', 'in', 'on', 'with']);
  T.mono = function (name) {
    const words = String(name || '').split(/\s+/).filter((w) => w && !STOP.has(w.toLowerCase()));
    if (!words.length) return '?';
    if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
    return (words[0][0] + words[words.length - 1][0]).toUpperCase();
  };
  const HEX_PX = { xs: 16, sm: 24, md: 34, lg: 84 };
  T.hex = function (topic, size) {
    size = size || 'sm';
    const px = HEX_PX[size];
    const cut = size === 'lg' || size === 'md' ? '<polygon class="hex__cut" points="' + HEX + '"/>' : '';
    const txt = size === 'xs' ? '' : '<text class="hex__t" x="50" y="50" dy="0.36em" text-anchor="middle">' + esc(T.mono(topic.name)) + '</text>';
    return T.raw('<svg class="hex hex--' + size + '" viewBox="-12 -12 124 124" width="' + px + '" height="' + px +
      '" aria-hidden="true" focusable="false" style="--hc:var(--topic-' + esc(topic.color) + ')">' + cut +
      '<polygon class="hex__fill" points="' + HEX + '"/>' + txt + '</svg>');
  };
  T.hexEmpty = (size) => T.raw('<svg class="hex hex--' + (size || 'xs') + ' hex--none" viewBox="-12 -12 124 124" width="' +
    HEX_PX[size || 'xs'] + '" height="' + HEX_PX[size || 'xs'] + '" aria-hidden="true"><polygon class="hex__fill" points="' + HEX + '"/></svg>');
  T.topicVar = (topic) => '--tc:var(--topic-' + topic.color + ')';
  T.topicTag = function (topic, opts) {
    opts = opts || {};
    if (!topic) return html`<span class="ttag ttag--none">${T.hexEmpty('xs')}<span>${opts.noneLabel || 'Standalone'}</span></span>`;
    if (opts.plain) return html`<span class="ttag" style="${T.topicVar(topic)}">${T.hex(topic, 'xs')}<span>${topic.name}</span></span>`;
    return html`<a class="ttag" href="topic.html?id=${topic.id}" style="${T.topicVar(topic)}">${T.hex(topic, 'xs')}<span>${topic.name}</span></a>`;
  };

  /* ---------------- Status: pattern swatch + name ---------------- */
  const CAT_LABEL = { planned: 'Planned', active: 'Active', paused: 'Paused', done: 'Done' };
  T.CAT_LABEL = CAT_LABEL;
  T.swatch = (cat) => T.raw('<span class="sw sw--' + cat + '" aria-hidden="true">' +
    (cat === 'done' ? '<svg viewBox="0 0 20 12" width="20" height="12"><path d="M6.5 6.2l2.4 2.3 4.6-4.9" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/></svg>' : '') + '</span>');
  T.statusLabel = function (st) {
    if (!st) return '';
    const extra = st.name.toLowerCase() !== CAT_LABEL[st.category].toLowerCase()
      ? html`<span class="vh">, ${CAT_LABEL[st.category]} category</span>` : '';
    return html`<span class="st st--${st.category}">${T.swatch(st.category)}<span class="st__name">${st.name}</span>${extra}</span>`;
  };

  /* ---------------- Task progress: one segment per personal Task ---------------- */
  T.progress = function (topic) {
    const p = S.progress(topic.id);
    if (!p.total) return html`<span class="prog prog--none">No tasks yet</span>`;
    const sorted = p.tasks.slice().sort((a, b) => Number(b.done) - Number(a.done));
    const segs = p.total <= 12
      ? sorted.map((t) => (t.done ? '<i class="on"></i>' : '<i></i>')).join('')
      : '<b style="--p:' + (p.done / p.total) + '"></b>';
    return html`<span class="prog" style="${T.topicVar(topic)}"><span class="prog__segs" aria-hidden="true">${T.raw(segs)}</span><span class="prog__txt">${p.done} of ${p.total}<span class="vh"> personal tasks done</span></span></span>`;
  };

  /* ---------------- Task row ---------------- */
  T.taskMeta = function (t, opts) {
    opts = opts || {};
    const bits = [];
    if (!opts.hideTopic) bits.push(T.topicTag(S.topic(t.topicId)));
    if (t.deadline) {
      const over = !t.done && D.parse(t.deadline) < D.now();
      const sameDay = t.deadline.slice(0, 10) === D.today();
      const when = sameDay ? D.time(t.deadline) : D.when(t.deadline, true);
      bits.push(html`<span class="meta ${over ? 'meta--over' : ''}">${I(over ? 'alert' : 'clock', 14)}${over ? 'Overdue, due ' + when : 'Due ' + when}</span>`);
    }
    if (t.plannedDay && (opts.showPlanned || t.plannedDay !== D.today())) {
      bits.push(html`<span class="meta">${I('today', 14)}Planned ${D.day(t.plannedDay, true)}</span>`);
    }
    S.remindersFor('task', t.id).forEach((r) => {
      bits.push(html`<span class="meta meta--rem">${I('bell', 14)}${r.at.slice(0, 10) === D.today() ? D.time(r.at) : D.when(r.at)}</span>`);
    });
    if (t.inbox) bits.push(html`<span class="meta">${I('inbox', 14)}In Inbox</span>`);
    return bits;
  };
  T.taskRow = function (t, opts) {
    opts = opts || {};
    return html`<li class="trow ${t.done ? 'is-done' : ''}">
      <input class="check" type="checkbox" ${t.done ? 'checked' : ''} data-change="toggle-task" data-id="${t.id}" data-k="chk-${t.id}"
        aria-label="${t.done ? 'Reopen' : 'Complete'}: ${t.title}">
      <div class="trow__body">
        <button class="trow__title" type="button" data-act="open-task" data-id="${t.id}" data-k="ttl-${t.id}">${t.title}</button>
        <div class="trow__meta">${T.taskMeta(t, opts)}</div>
      </div>
    </li>`;
  };

  T.empty = function ({ icon, title, text, action }) {
    return html`<div class="empty">
      <span class="empty__icon">${I(icon || 'sparkle', 26)}</span>
      <h3 class="empty__title">${title}</h3>
      ${text ? html`<p class="empty__text">${text}</p>` : ''}
      ${action || ''}
    </div>`;
  };
  T.demoBar = (label, controls) => html`<div class="demo" role="group" aria-label="Demo controls: ${label}">
    <span class="demo__tag">${I('flask', 14)}Demo</span><span class="demo__label">${label}</span>${controls}</div>`;

  /* ---------------- Action wiring ---------------- */
  const acts = {};
  T.on = (name, fn) => { acts[name] = fn; };
  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-act]');
    if (!el || el.disabled) return;
    const fn = acts[el.dataset.act];
    if (fn) { fn(el, e); }
  });
  document.addEventListener('change', (e) => {
    const el = e.target.closest('[data-change]');
    if (!el) return;
    const fn = acts[el.dataset.change];
    if (fn) fn(el, e);
  });
  document.addEventListener('submit', (e) => {
    const el = e.target.closest('[data-submit]');
    if (!el) return;
    e.preventDefault();
    const fn = acts[el.dataset.submit];
    if (fn) fn(el, e);
  });

  T.on('toggle-task', (el) => {
    const t = S.task(el.dataset.id);
    T.act.toggleTask(el.dataset.id);
    if (t) T.toast(t.done ? 'Completed: ' + t.title : 'Reopened: ' + t.title, { undo: () => T.act.toggleTask(t.id) });
  });
  T.on('open-task', (el) => T.openTask(el.dataset.id));
  T.on('go', (el) => { location.href = el.dataset.href; });

  /* ---------------- Toasts and banners ---------------- */
  let toastTimer;
  let toastEl = null;
  /* A modal dialog makes the rest of the page inert, so the toast lives in the
     topmost open dialog while one is open. Dialog re-renders call placeToast. */
  const toastHost = () => { const open = document.querySelectorAll('dialog[open]'); return open.length ? open[open.length - 1] : document.body; };
  T.placeToast = function () {
    if (!toastEl || !toastEl.classList.contains('is-on')) return;
    const h = toastHost();
    if (toastEl.parentNode !== h) h.appendChild(toastEl);
  };
  T.toast = function (msg, opts) {
    opts = opts || {};
    const box = toastEl;
    if (!box) return;
    box.innerHTML = '';
    const p = document.createElement('p');
    p.className = 'toast__msg';
    p.textContent = msg;
    box.appendChild(p);
    const hide = () => { clearTimeout(toastTimer); box.classList.remove('is-on'); };
    const addBtn = (label, fn) => {
      const b = document.createElement('button');
      b.type = 'button'; b.className = 'btn btn--text'; b.textContent = label;
      b.addEventListener('click', () => { hide(); fn(); });
      box.appendChild(b);
    };
    if (opts.undo) addBtn('Undo', opts.undo);
    if (opts.open) addBtn(opts.openLabel || 'Open', opts.open);
    clearTimeout(toastTimer);
    const host = toastHost();
    if (box.parentNode !== host) { box.classList.remove('is-on'); host.appendChild(box); void box.offsetWidth; }
    box.classList.add('is-on');
    const ms = opts.ms || 6000;
    /* Stays open while the pointer or keyboard focus is on it. */
    const arm = () => { toastTimer = setTimeout(() => (box.matches(':hover, :focus-within') ? arm() : hide()), ms); };
    arm();
  };

  function renderReminderBanner() {
    const host = document.getElementById('banner');
    if (!host) return;
    const fired = T.state.demo.firedReminders
      .filter((id) => !T.state.demo.dismissedReminders.includes(id))
      .map(S.reminder).filter(Boolean).filter((r) => S.targetOf(r));
    host.innerHTML = fired.map((r) => {
      const tg = S.targetOf(r);
      return html`<div class="rbanner" role="status">
        <span class="rbanner__time">${D.time(r.at)}</span>
        <div class="rbanner__body"><p class="rbanner__text">${I('bell', 16)} ${r.text}</p>
          <p class="rbanner__meta">Reminder for ${tg.kind} ${tg.kind === 'topic' ? '' : html`<b>${tg.label}</b>`} ${tg.topic ? T.topicTag(tg.topic) : ''}</p></div>
        <div class="rbanner__acts">
          <button class="btn btn--line" type="button" data-act="open-reminder-target" data-id="${r.id}">Open ${tg.kind}</button>
          <button class="btn btn--text" type="button" data-act="dismiss-reminder" data-id="${r.id}">Dismiss</button>
        </div></div>`.s;
    }).join('');
  }
  T.on('dismiss-reminder', (el) => T.act.dismissReminder(el.dataset.id));
  T.on('open-reminder-target', (el) => {
    const r = S.reminder(el.dataset.id);
    if (!r) return;
    T.openTarget(r.target);
  });
  T.openTarget = function (target) {
    if (target.kind === 'task') T.openTask(target.id);
    else if (target.kind === 'note') location.href = 'notes.html?id=' + target.id;
    else location.href = 'topic.html?id=' + target.id;
  };

  /* ---------------- Generic dialogs ---------------- */
  T.dialog = function (inner, opts) {
    opts = opts || {};
    const d = document.createElement('dialog');
    d.className = 'dlg ' + (opts.cls || '');
    d.setAttribute('aria-labelledby', 'dlg-title-' + (++T._dlg || (T._dlg = 1)));
    d.innerHTML = String(inner).replace('id="dlg-title"', 'id="dlg-title-' + T._dlg + '"');
    document.body.appendChild(d);
    d.addEventListener('close', () => { d.remove(); if (opts.onClose) opts.onClose(d.returnValue); });
    d.addEventListener('click', (e) => { if (e.target === d) d.close('cancel'); });
    d.showModal();
    return d;
  };

  T.confirm = function ({ title, body, confirmLabel, danger }) {
    return new Promise((resolve) => {
      const d = T.dialog(html`<form method="dialog" class="dlg__in">
        <h2 class="dlg__title" id="dlg-title">${title}</h2>
        ${body ? html`<p class="dlg__text">${body}</p>` : ''}
        <div class="dlg__acts"><button class="btn btn--line" value="cancel">Cancel</button>
        <button class="btn ${danger ? 'btn--danger' : 'btn--primary'}" value="ok">${confirmLabel || 'Confirm'}</button></div></form>`,
      { onClose: (v) => resolve(v === 'ok') });
      d.querySelector('[value="cancel"]').focus();
    });
  };

  /* Topic picker. multiple=true for Notes/Jira/PRs, single for Tasks. */
  T.pickTopics = function ({ title, multiple, selected, allowNone, hint, confirmLabel }) {
    selected = selected || [];
    return new Promise((resolve) => {
      const topics = S.topicsInOrder();
      const type = multiple ? 'checkbox' : 'radio';
      const rows = topics.map((tp) => html`<label class="pick">
        <input type="${type}" name="pick" value="${tp.id}" ${selected.includes(tp.id) ? 'checked' : ''}>
        ${T.hex(tp, 'sm')}<span class="pick__name">${tp.name}</span>${T.statusLabel(S.statusOf(tp))}</label>`);
      const none = allowNone ? html`<label class="pick"><input type="radio" name="pick" value="" ${selected.length ? '' : 'checked'}>
        ${T.hexEmpty('sm')}<span class="pick__name">No Topic (standalone)</span></label>` : '';
      const d = T.dialog(html`<form method="dialog" class="dlg__in">
        <h2 class="dlg__title" id="dlg-title">${title}</h2>
        ${hint ? html`<p class="dlg__text">${hint}</p>` : ''}
        <fieldset class="picklist"><legend class="vh">${title}</legend>${none}${rows}</fieldset>
        ${topics.length ? '' : html`<p class="dlg__text">No Topics yet. Create one from the capture line.</p>`}
        <div class="dlg__acts"><button class="btn btn--line" value="cancel">Cancel</button>
        <button class="btn btn--primary" value="ok">${confirmLabel || 'Save links'}</button></div></form>`,
      { onClose: (v) => {
        if (v !== 'ok') return resolve(null);
        resolve(Array.from(d.querySelectorAll('input[name="pick"]:checked')).map((i) => i.value).filter(Boolean));
      } });
      const first = d.querySelector('input[name="pick"]');
      if (first) first.focus();
    });
  };

  /* Pick an existing item to link (no copies are made). */
  T.pickItem = function ({ title, items, empty }) {
    return new Promise((resolve) => {
      const rows = items.map((it) => html`<label class="pick"><input type="radio" name="pick" value="${it.id}">
        <span class="pick__icon">${I(it.icon, 18)}</span><span class="pick__name">${it.label}</span><span class="pick__sub">${it.sub || ''}</span></label>`);
      const d = T.dialog(html`<form method="dialog" class="dlg__in">
        <h2 class="dlg__title" id="dlg-title">${title}</h2>
        <p class="dlg__text">Links the existing item. Nothing is copied.</p>
        ${items.length ? html`<fieldset class="picklist"><legend class="vh">${title}</legend>${rows}</fieldset>` : html`<p class="dlg__text">${empty}</p>`}
        <div class="dlg__acts"><button class="btn btn--line" value="cancel">Cancel</button>
        <button class="btn btn--primary" value="ok" ${items.length ? '' : 'disabled'}>Link</button></div></form>`,
      { onClose: (v) => {
        const c = d.querySelector('input[name="pick"]:checked');
        resolve(v === 'ok' && c ? c.value : null);
      } });
      const first = d.querySelector('input[name="pick"]');
      if (first) first.focus();
    });
  };

  /* Reminder editor for a Task, Note or Topic. */
  T.addReminderFor = function (target, defaultText) {
    const day = D.today();
    const d = T.dialog(html`<form method="dialog" class="dlg__in" data-submit="noop">
      <h2 class="dlg__title" id="dlg-title">New reminder</h2>
      <p class="dlg__text">Shows in Today and as an in-app banner at that time. Delivery while Thread is closed is not part of v1.</p>
      <div class="fgrid">
        <label class="field"><span class="field__label">Day</span><input class="input" type="date" name="day" value="${day}" required></label>
        <label class="field"><span class="field__label">Time</span><input class="input" type="time" name="time" value="15:00" required></label>
      </div>
      <label class="field"><span class="field__label">Text</span><input class="input" name="text" value="${defaultText || ''}" required></label>
      <div class="dlg__acts"><button class="btn btn--line" value="cancel" formnovalidate>Cancel</button>
      <button class="btn btn--primary" value="ok">Add reminder</button></div></form>`,
    { onClose: (v) => {
      if (v !== 'ok') return;
      const f = d.querySelector('form').elements;
      const r = T.act.addReminder({ at: f.day.value + 'T' + f.time.value, text: f.text.value.trim() || 'Reminder', target });
      T.toast('Reminder set for ' + D.when(r.at, true));
    } });
    const form = d.querySelector('form');
    form.removeAttribute('data-submit');
    d.querySelector('input[name="time"]').focus();
  };

  /* ---------------- Task details sheet ---------------- */
  let taskDlg = null;
  let taskId = null;
  T.openTask = function (id) {
    if (!S.task(id)) { T.toast('That task is not here any more. It may be in Trash.'); return; }
    taskId = id;
    if (!taskDlg) {
      taskDlg = document.createElement('dialog');
      taskDlg.className = 'dlg dlg--sheet';
      taskDlg.setAttribute('aria-labelledby', 'task-sheet-title');
      document.body.appendChild(taskDlg);
      taskDlg.addEventListener('close', () => { taskId = null; setParam('task', null); });
      taskDlg.addEventListener('click', (e) => { if (e.target === taskDlg) taskDlg.close(); });
    }
    renderTaskSheet();
    if (!taskDlg.open) taskDlg.showModal();
    setParam('task', id);
    const ttl = taskDlg.querySelector('[data-k="ts-title"]');
    if (ttl) ttl.focus();
  };
  function todayEffect(t) {
    const g = S.taskGroup(t);
    if (t.done) return 'Completed. It stays visible in Today until tomorrow, then leaves.';
    if (g === 'overdue') return 'Shows in Today under Overdue because the deadline passed.';
    if (g === 'today') return t.plannedDay === D.today() ? 'Shows in Today under Today because it is planned for today.' : 'Shows in Today under Today because it is due today.';
    if (g === 'earlier') return 'Shows in Today under Earlier planned. It is not overdue; nothing moves it automatically.';
    if (t.plannedDay) return 'Planned for ' + D.day(t.plannedDay, true) + '. It shows in Today on that day.';
    return 'Not in Today. Plan a day or set a deadline to see it there.';
  }
  function renderTaskSheet() {
    if (!taskDlg || !taskId) return;
    const t = S.task(taskId);
    if (!t) { taskDlg.close(); return; }
    const keep = captureFocus(taskDlg);
    const topics = S.topicsInOrder();
    const rems = S.remindersFor('task', t.id);
    const dl = t.deadline ? t.deadline.split('T') : ['', ''];
    taskDlg.innerHTML = html`<div class="sheet">
      <header class="sheet__head">
        <h2 class="sheet__title" id="task-sheet-title">${I('task', 18)} Task</h2>
        <button class="iconbtn" type="button" data-act="close-task" aria-label="Close task details">${I('x', 20)}</button>
      </header>
      <div class="sheet__body">
        <div class="ts-title">
          <input class="check check--lg" type="checkbox" ${t.done ? 'checked' : ''} data-change="toggle-task" data-id="${t.id}" data-k="ts-check" aria-label="${t.done ? 'Reopen task' : 'Complete task'}">
          <textarea class="ts-title__input ${t.done ? 'is-done' : ''}" rows="2" data-change="task-title" data-id="${t.id}" data-k="ts-title" aria-label="Task title">${t.title}</textarea>
        </div>
        <button class="btn btn--primary btn--wide" type="button" data-act="toggle-task" data-id="${t.id}" data-k="ts-primary">${t.done ? 'Reopen task' : 'Complete task'}</button>
        <p class="ts-effect">${I('today', 16)}<span>${todayEffect(t)}</span></p>
        ${t.inbox ? html`<p class="ts-effect">${I('inbox', 16)}<span>Also in Inbox until you file it or keep it standalone.</span></p>` : ''}

        <div class="field">
          <label class="field__label" for="ts-topic">Topic</label>
          <select class="input" id="ts-topic" data-change="task-topic" data-id="${t.id}" data-k="ts-topic">
            <option value="">No Topic (standalone)</option>
            ${topics.map((tp) => html`<option value="${tp.id}" ${tp.id === t.topicId ? 'selected' : ''}>${tp.name}</option>`)}
          </select>
          <p class="field__hint">A task belongs to one Topic or none.</p>
        </div>

        <fieldset class="ts-dates"><legend class="field__label">Planned day, deadline and reminder are separate and optional</legend>
          <div class="field">
            <label class="field__label field__label--sub" for="ts-plan">${I('today', 15)} Planned day <span class="field__aside">the day you mean to work on it</span></label>
            <div class="row">
              <input class="input" type="date" id="ts-plan" value="${t.plannedDay || ''}" data-change="task-plan" data-id="${t.id}" data-k="ts-plan">
              <button class="chip" type="button" data-act="task-plan-set" data-id="${t.id}" data-day="0">Today</button>
              <button class="chip" type="button" data-act="task-plan-set" data-id="${t.id}" data-day="1">Tomorrow</button>
              ${t.plannedDay ? html`<button class="chip chip--quiet" type="button" data-act="task-plan-set" data-id="${t.id}" data-day="">Clear</button>` : ''}
            </div>
          </div>
          <div class="field">
            <label class="field__label field__label--sub" for="ts-dl-day">${I('clock', 15)} Deadline <span class="field__aside">when it must be done</span></label>
            <div class="row">
              <input class="input" type="date" id="ts-dl-day" value="${dl[0]}" data-change="task-deadline" data-id="${t.id}" data-k="ts-dl-day">
              <input class="input input--time" type="time" aria-label="Deadline time" value="${dl[1] || ''}" data-change="task-deadline" data-id="${t.id}" data-k="ts-dl-time">
              ${t.deadline ? html`<button class="chip chip--quiet" type="button" data-act="task-deadline-clear" data-id="${t.id}">Clear</button>` : ''}
            </div>
          </div>
          <div class="field">
            <span class="field__label field__label--sub">${I('bell', 15)} Reminder <span class="field__aside">an in-app nudge at a time</span></span>
            <ul class="ts-rems">${rems.map((r) => html`<li class="ts-rem"><span class="ts-rem__time">${D.when(r.at)}</span><span class="ts-rem__text">${r.text}</span>
              <button class="iconbtn iconbtn--sm" type="button" data-act="delete-reminder" data-id="${r.id}" aria-label="Delete reminder ${D.when(r.at)}">${I('trash', 16)}</button></li>`)}</ul>
            <button class="btn btn--line btn--sm" type="button" data-act="task-add-reminder" data-id="${t.id}">${I('plus', 16)} Add reminder</button>
          </div>
        </fieldset>
      </div>
      <footer class="sheet__foot">
        ${t.topicId && !(T.view === 'topics' && T.params.get('id') === t.topicId) ? html`<a class="btn btn--text" href="topic.html?id=${t.topicId}">${I('topics', 16)} Open Topic</a>` : html`<span></span>`}
        <button class="btn btn--text btn--danger-text" type="button" data-act="task-trash" data-id="${t.id}">${I('trash', 16)} Move to Trash</button>
      </footer></div>`.s;
    restoreFocus(taskDlg, keep);
  }
  T.on('close-task', () => taskDlg && taskDlg.close());
  T.on('task-title', (el) => { const v = el.value.trim(); if (v) T.act.updateTask(el.dataset.id, { title: v }); });
  T.on('task-topic', (el) => {
    const t = S.task(el.dataset.id);
    T.act.updateTask(el.dataset.id, { topicId: el.value || null, inbox: el.value ? false : t.inbox });
  });
  T.on('task-plan', (el) => T.act.updateTask(el.dataset.id, { plannedDay: el.value || null }));
  T.on('task-plan-set', (el) => {
    const v = el.dataset.day === '' ? null : D.addDays(D.today(), Number(el.dataset.day));
    T.act.updateTask(el.dataset.id, { plannedDay: v });
  });
  T.on('task-deadline', (el) => {
    const box = el.closest('.row');
    const day = box.querySelector('input[type="date"]').value;
    const time = box.querySelector('input[type="time"]').value || '18:00';
    T.act.updateTask(el.dataset.id, { deadline: day ? day + 'T' + time : null });
  });
  T.on('task-deadline-clear', (el) => T.act.updateTask(el.dataset.id, { deadline: null }));
  T.on('task-add-reminder', (el) => { const t = S.task(el.dataset.id); T.addReminderFor({ kind: 'task', id: t.id }, t.title); });
  T.on('delete-reminder', (el) => {
    const entry = T.act.trash('reminder', el.dataset.id);
    if (entry) T.toast('Reminder moved to Trash', { undo: () => T.act.restore(entry.id) });
  });
  T.on('task-trash', (el) => {
    const t = S.task(el.dataset.id);
    const entry = T.act.trash('task', el.dataset.id);
    if (taskDlg) taskDlg.close();
    if (entry) T.toast('Moved to Trash: ' + t.title, { undo: () => T.act.restore(entry.id) });
  });

  /* ---------------- Search palette (Cmd/Ctrl+K) ---------------- */
  let searchDlg = null;
  let searchSel = 0;
  T.openSearch = function (q) {
    if (searchDlg && searchDlg.open) { searchDlg.querySelector('input').focus(); return; }
    searchDlg = document.createElement('dialog');
    searchDlg.className = 'dlg dlg--palette';
    searchDlg.setAttribute('aria-label', 'Search');
    searchDlg.innerHTML = html`<div class="pal">
      <div class="pal__bar">${I('search', 20)}
        <input class="pal__input" type="search" placeholder="Search Topics, Tasks, Notes, Jira and PRs" aria-label="Search this device"
          role="combobox" aria-expanded="true" aria-controls="pal-results" aria-autocomplete="list" autocomplete="off" value="${q || ''}">
        <kbd class="kbd">Esc</kbd></div>
      <div class="pal__results" id="pal-results" role="listbox" aria-label="Results"></div>
      <p class="pal__foot">Searches this device: Topics, Tasks, Notes and downloaded Jira and pull request details.</p></div>`.s;
    document.body.appendChild(searchDlg);
    const input = searchDlg.querySelector('input');
    input.addEventListener('input', () => { searchSel = 0; renderResults(); });
    input.addEventListener('keydown', (e) => {
      const opts = searchDlg.querySelectorAll('[role="option"]');
      if (e.key === 'ArrowDown') { e.preventDefault(); searchSel = Math.min(searchSel + 1, opts.length - 1); markSel(); }
      if (e.key === 'ArrowUp') { e.preventDefault(); searchSel = Math.max(searchSel - 1, 0); markSel(); }
      if (e.key === 'Enter') { e.preventDefault(); const o = opts[searchSel]; if (o) o.click(); }
    });
    searchDlg.addEventListener('close', () => { searchDlg.remove(); searchDlg = null; setParam('search', null); });
    searchDlg.addEventListener('click', (e) => { if (e.target === searchDlg) searchDlg.close(); });
    searchDlg.showModal();
    renderResults();
    input.focus();
  };
  function markSel() {
    const opts = searchDlg.querySelectorAll('[role="option"]');
    opts.forEach((o, i) => o.setAttribute('aria-selected', String(i === searchSel)));
    const cur = opts[searchSel];
    const input = searchDlg.querySelector('input');
    if (cur) { input.setAttribute('aria-activedescendant', cur.id); cur.scrollIntoView({ block: 'nearest' }); }
  }
  function renderResults() {
    const box = searchDlg.querySelector('.pal__results');
    const q = searchDlg.querySelector('input').value;
    const r = T.searchAll(q);
    let n = 0;
    const opt = (label, sub, lead, attrs) => html`<button type="button" class="pal__opt" role="option" id="opt-${n++}" ${T.raw(attrs)}>
      <span class="pal__lead">${lead}</span><span class="pal__label">${label}</span><span class="pal__sub">${sub}</span></button>`;
    const group = (title, items) => items.length ? html`<div class="pal__group" role="group" aria-label="${title}"><h3 class="pal__gtitle">${title}</h3>${items}</div>` : '';
    if (!r) {
      const views = [['Today', 'today.html', 'today'], ['Inbox', 'inbox.html', 'inbox'], ['Topics', 'topics.html', 'topics'], ['Notes', 'notes.html', 'notes'],
        ['Jira', 'jira.html', 'jira'], ['Pull requests', 'prs.html', 'pr'], ['Settings', 'settings.html', 'settings'], ['Trash', 'trash.html', 'trash']];
      box.innerHTML = group('Go to', views.map(([l, h, i]) => opt(l, '', I(i, 18), 'data-act="go" data-href="' + h + '"'))).s;
    } else {
      const total = r.topics.length + r.tasks.length + r.notes.length + r.jira.length + r.prs.length;
      if (!total) {
        box.innerHTML = html`<div class="pal__none"><p><b>No local results for “${q}”.</b></p>
          <p>Jira search of tickets not on this device is a separate step on the Jira page.</p>
          <a class="btn btn--line" href="jira.html?tab=search&q=${encodeURIComponent(q)}">${I('jira', 16)} Search Jira for “${q}”</a></div>`.s;
        return;
      }
      box.innerHTML = html`
        ${group('Topics', r.topics.map((t) => opt(t.name, S.statusOf(t).name, T.hex(t, 'sm'), 'data-act="go" data-href="topic.html?id=' + t.id + '"')))}
        ${group('Tasks', r.tasks.map((t) => opt(t.title, t.done ? 'Completed' : (S.topic(t.topicId) || { name: 'Standalone' }).name, I('task', 18), 'data-act="pal-task" data-id="' + t.id + '"')))}
        ${group('Notes', r.notes.map((nn) => opt(nn.title || 'Untitled note', nn.topicIds.length ? nn.topicIds.map((id) => (S.topic(id) || {}).name).join(', ') : 'Standalone', I('notes', 18), 'data-act="go" data-href="notes.html?id=' + nn.id + '"')))}
        ${group('Jira (downloaded)', r.jira.map((j) => opt(j.key + ' ' + j.title, j.status, I('jira', 18), 'data-act="go" data-href="jira.html?key=' + j.key + '"')))}
        ${group('Pull requests (downloaded)', r.prs.map((p) => opt(p.title + ' #' + p.number, p.repo, I('pr', 18), 'data-act="go" data-href="prs.html?id=' + p.id + (p.reviewRequested ? '&tab=review' : '') + '"')))}`.s;
    }
    markSel();
  }
  T.on('pal-task', (el) => { const id = el.dataset.id; if (searchDlg) searchDlg.close(); T.openTask(id); });

  /* ---------------- Focus timer (post-pilot concept) ---------------- */
  const F = () => T.state.focus;
  function focusRemaining() {
    const f = F();
    if (f.running && f.endsAt) return Math.max(0, f.endsAt - Date.now());
    return f.remainingMs;
  }
  const mmss = (ms) => { const s = Math.ceil(ms / 1000); return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0'); };
  function focusLabel(v) {
    if (!v) return 'No Task or Topic';
    const [k, id] = v.split(':');
    if (k === 'topic') { const t = S.topic(id); return t ? t.name : 'Removed Topic'; }
    const t = S.task(id); return t ? t.title : 'Removed task';
  }
  T.focusAct = {
    start() {
      const f = F();
      f.phase = 'work'; f.running = true; f.endsAt = Date.now() + f.workMin * 60000; T.commit('focus');
    },
    pause() { const f = F(); f.remainingMs = focusRemaining(); f.running = false; f.endsAt = null; T.commit('focus'); },
    resume() { const f = F(); f.running = true; f.endsAt = Date.now() + f.remainingMs; T.commit('focus'); },
    stop(record) {
      const f = F();
      if (record && f.phase === 'work') {
        const mins = Math.max(1, Math.round((f.workMin * 60000 - focusRemaining()) / 60000));
        f.history.unshift({ endedAt: T.state.demo.now, minutes: mins, label: focusLabel(f.target), stopped: true });
      }
      f.phase = 'idle'; f.running = false; f.endsAt = null; f.remainingMs = f.workMin * 60000; T.commit('focus');
    }
  };
  function focusTick() {
    const f = F();
    if (f.running && focusRemaining() <= 0) {
      if (f.phase === 'work') {
        f.history.unshift({ endedAt: T.state.demo.now, minutes: f.workMin, label: focusLabel(f.target) });
        f.phase = 'break'; f.endsAt = Date.now() + f.breakMin * 60000;
        T.toast('Focus session done. Break: ' + f.breakMin + ' min.');
      } else {
        f.phase = 'idle'; f.running = false; f.endsAt = null; f.remainingMs = f.workMin * 60000;
        T.toast('Break over.');
      }
      T.commit('focus');
    }
    const chip = document.getElementById('focus-chip-text');
    if (chip) {
      chip.textContent = f.phase === 'idle' ? 'Focus' : mmss(focusRemaining());
      chip.parentElement.classList.toggle('is-running', f.phase !== 'idle');
      chip.parentElement.setAttribute('aria-label', f.phase === 'idle' ? 'Focus timer' : 'Focus timer, ' + chip.textContent + ' left');
    }
    const big = document.getElementById('focus-big');
    if (big) big.textContent = mmss(focusRemaining());
  }
  setInterval(focusTick, 1000);

  let focusDlg = null;
  T.openFocus = function () {
    if (!focusDlg) {
      focusDlg = document.createElement('dialog');
      focusDlg.className = 'dlg dlg--pop dlg--focus';
      focusDlg.setAttribute('aria-labelledby', 'focus-title');
      document.body.appendChild(focusDlg);
      focusDlg.addEventListener('close', () => setParam('focus', null));
      focusDlg.addEventListener('click', (e) => { if (e.target === focusDlg) focusDlg.close(); });
    }
    renderFocus();
    if (!focusDlg.open) focusDlg.showModal();
    setParam('focus', '1');
  };
  function renderFocus() {
    if (!focusDlg) return;
    const f = F();
    const keep = captureFocus(focusDlg);
    const open = T.state.tasks.filter((t) => !t.done);
    const phase = f.phase === 'work' ? (f.running ? 'Focusing' : 'Paused') : f.phase === 'break' ? (f.running ? 'Break' : 'Break paused') : 'Ready';
    focusDlg.innerHTML = html`<div class="focus">
      <header class="focus__head"><h2 class="focus__title" id="focus-title">${I('timer', 18)} Focus</h2>
        <span class="badge badge--concept">Post-pilot concept</span>
        <button class="iconbtn" type="button" data-act="close-focus" aria-label="Close focus timer">${I('x', 20)}</button></header>
      <p class="focus__time"><span id="focus-big">${mmss(focusRemaining())}</span><span class="focus__phase">${phase}</span></p>
      <p class="focus__for">For: <b>${focusLabel(f.target)}</b></p>
      <div class="focus__ctl">
        ${f.phase === 'idle' ? html`<button class="btn btn--primary" type="button" data-act="focus-start" data-k="f-start">Start focus</button>` : ''}
        ${f.phase !== 'idle' && f.running ? html`<button class="btn btn--line" type="button" data-act="focus-pause" data-k="f-pause">${I('pause', 16)} Pause</button>` : ''}
        ${f.phase !== 'idle' && !f.running ? html`<button class="btn btn--primary" type="button" data-act="focus-resume" data-k="f-resume">Resume</button>` : ''}
        ${f.phase !== 'idle' ? html`<button class="btn btn--line" type="button" data-act="focus-stop" data-k="f-stop">${I('stop', 16)} Stop</button>` : ''}
      </div>
      <div class="fgrid">
        <label class="field"><span class="field__label">Focus min</span><input class="input" type="number" min="1" max="90" value="${f.workMin}" data-change="focus-work" data-k="f-work" ${f.phase === 'idle' ? '' : 'disabled'}></label>
        <label class="field"><span class="field__label">Break min</span><input class="input" type="number" min="1" max="30" value="${f.breakMin}" data-change="focus-break" data-k="f-break" ${f.phase === 'idle' ? '' : 'disabled'}></label>
      </div>
      <label class="field"><span class="field__label">Attach to (optional)</span>
        <select class="input" data-change="focus-target" data-k="f-target">
          <option value="">No Task or Topic</option>
          <optgroup label="Topics">${T.state.topics.map((t) => html`<option value="topic:${t.id}" ${f.target === 'topic:' + t.id ? 'selected' : ''}>${t.name}</option>`)}</optgroup>
          <optgroup label="Open tasks">${open.map((t) => html`<option value="task:${t.id}" ${f.target === 'task:' + t.id ? 'selected' : ''}>${t.title}</option>`)}</optgroup>
        </select></label>
      <h3 class="focus__h">Recent sessions</h3>
      ${f.history.length ? html`<ul class="focus__hist">${f.history.slice(0, 4).map((h) => html`<li><span>${D.when(h.endedAt)}</span><span>${h.minutes} min${h.stopped ? ' (stopped)' : ''}</span><span class="focus__hl">${h.label}</span></li>`)}</ul>`
        : html`<p class="focus__none">No sessions yet.</p>`}
      <p class="focus__note">Timer uses saved timestamps, so it keeps running across pages. Durations here are demo values.</p></div>`.s;
    restoreFocus(focusDlg, keep);
  }
  T.on('close-focus', () => focusDlg && focusDlg.close());
  T.on('open-focus', () => T.openFocus());
  T.on('focus-start', () => T.focusAct.start());
  T.on('focus-pause', () => T.focusAct.pause());
  T.on('focus-resume', () => T.focusAct.resume());
  T.on('focus-stop', () => T.focusAct.stop(true));
  T.on('focus-work', (el) => { const f = F(); f.workMin = Math.max(1, Math.min(90, Number(el.value) || 25)); f.remainingMs = f.workMin * 60000; T.commit('focus'); });
  T.on('focus-break', (el) => { const f = F(); f.breakMin = Math.max(1, Math.min(30, Number(el.value) || 5)); T.commit('focus'); });
  T.on('focus-target', (el) => { F().target = el.value; T.commit('focus'); });

  /* ---------------- Sync popover ---------------- */
  const SYNC_TEXT = {
    synced: ['cloudCheck', 'All synced', 'ok'],
    syncing: ['cloudUp', 'Syncing', 'busy'],
    saving: ['device', 'Saving locally', 'busy'],
    offline: ['cloudOff', 'Saved locally, offline', 'warn'],
    'sync-failed': ['alert', 'Sync failed', 'bad'],
    'local-failed': ['alert', 'Not saved locally', 'bad'],
    conflict: ['compare', 'Conflict to resolve', 'bad']
  };
  T.SYNC_TEXT = SYNC_TEXT;
  function syncInfo() {
    if (T.state.session && T.state.session.mode === 'skip') return ['device', 'Local only', 'warn'];
    return SYNC_TEXT[T.noteSync.overall()] || SYNC_TEXT.synced;
  }
  let syncDlg = null;
  T.openSync = function () {
    if (!syncDlg) {
      syncDlg = document.createElement('dialog');
      syncDlg.className = 'dlg dlg--pop dlg--sync';
      syncDlg.setAttribute('aria-labelledby', 'sync-title');
      document.body.appendChild(syncDlg);
      syncDlg.addEventListener('click', (e) => { if (e.target === syncDlg) syncDlg.close(); });
    }
    renderSync();
    if (!syncDlg.open) syncDlg.showModal();
  };
  function renderSync() {
    if (!syncDlg) return;
    const [icon, text, tone] = syncInfo();
    const probs = T.noteSync.problems();
    const skip = T.state.session && T.state.session.mode === 'skip';
    syncDlg.innerHTML = html`<div class="syncp">
      <header class="focus__head"><h2 class="focus__title" id="sync-title">${I(icon, 18)} ${text}</h2>
        <button class="iconbtn" type="button" data-act="close-sync" aria-label="Close">${I('x', 20)}</button></header>
      <p class="syncp__text">${skip ? 'You skipped sign-in. Everything stays on this device and nothing syncs.'
        : 'Saved locally means it is safe on this device. Synced means the server confirmed it.'}</p>
      ${probs.length ? html`<h3 class="focus__h">Needs attention</h3><ul class="syncp__list">${probs.map((n) => html`<li><a href="notes.html?id=${n.id}">${I('notes', 16)} ${n.title || 'Untitled note'}</a><span class="sync sync--bad">${SYNC_TEXT[T.noteSync.get(n.id)][1]}</span></li>`)}</ul>` : ''}
      ${T.demoBar('Network', html`<div class="seg" role="radiogroup" aria-label="Network (demo)">
        <button type="button" role="radio" aria-checked="${T.state.demo.online}" data-act="set-online" data-v="1">Online</button>
        <button type="button" role="radio" aria-checked="${!T.state.demo.online}" data-act="set-online" data-v="0">Offline</button></div>`)}
    </div>`.s;
  }
  T.on('close-sync', () => syncDlg && syncDlg.close());
  T.on('open-sync', () => T.openSync());
  T.on('set-online', (el) => { T.noteSync.setOnline(el.dataset.v === '1'); T.toast(el.dataset.v === '1' ? 'Back online. Syncing saved changes.' : 'Offline. Edits save on this device.'); });

  /* ---------------- The status line ---------------- */
  const VIEWS = [
    { id: 'today', href: 'today.html', label: 'Today', icon: 'today' },
    { id: 'inbox', href: 'inbox.html', label: 'Inbox', icon: 'inbox' },
    { id: 'topics', href: 'topics.html', label: 'Topics', icon: 'topics' },
    { id: 'notes', href: 'notes.html', label: 'Notes', icon: 'notes' },
    { id: 'jira', href: 'jira.html', label: 'Jira', icon: 'jira' },
    { id: 'prs', href: 'prs.html', label: 'Pull requests', short: 'PRs', icon: 'pr' }
  ];
  const MORE = [
    { id: 'settings', href: 'settings.html', label: 'Settings', icon: 'settings' },
    { id: 'trash', href: 'trash.html', label: 'Trash', icon: 'trash' }
  ];
  T.view = document.body.dataset.view || document.body.dataset.page;
  const allViews = VIEWS.concat(MORE);
  const current = allViews.find((v) => v.id === T.view) || VIEWS[0];

  function buildLine() {
    const line = document.createElement('div');
    line.className = 'line';
    line.id = 'line';
    line.tabIndex = -1;
    line.setAttribute('role', 'region');
    line.setAttribute('aria-label', 'Workspace bar');
    line.innerHTML = html`
      <a class="line__brand" href="index.html" title="Prototype home and sign-in">${T.raw(brandMark())}<span class="line__demo">Demo</span></a>
      <nav class="line__views" aria-label="Views">${VIEWS.map((v) => html`<a class="lv" href="${v.href}" title="${v.label}" ${v.id === T.view ? T.raw('aria-current="page"') : ''}>
        ${I(v.icon, 17)}<span class="lv__label">${v.label}</span>${v.id === 'inbox' ? html`<span class="lv__count" id="inbox-count"></span>` : ''}</a>`)}</nav>
      <button class="lv lv--current" type="button" data-act="open-views" aria-haspopup="dialog">${I(current.icon, 17)}${current.short ? html`<span aria-hidden="true">${current.short}</span><span class="vh">${current.label}</span>` : html`<span>${current.label}</span>`}${I('chevDown', 15)}<span class="lv__count" id="inbox-count-m"></span></button>
      <form class="cap" data-submit="capture" autocomplete="off">
        <label class="vh" for="cap-type">Capture type</label>
        <select class="cap__type" id="cap-type" data-change="cap-type">
          <option value="task">Task</option><option value="note">Note</option><option value="topic">Topic</option></select>
        <label class="vh" for="cap-title">Capture title</label>
        <input class="cap__input" id="cap-title" name="title" placeholder="Capture a task" enterkeyhint="done">
        <span class="cap__dest"><span class="cap__arrow" aria-hidden="true">→</span>
          <label class="vh" for="cap-dest">Capture destination</label>
          <select class="cap__destsel" id="cap-dest"></select>
          <span class="cap__fixed" id="cap-fixed" hidden>Topics</span></span>
        <button class="cap__go" type="submit" aria-label="Capture">${I('arrowRight', 18)}</button>
      </form>
      <div class="line__tools">
        <button class="tool tool--search" type="button" data-act="open-search" aria-label="Search (Command K)" title="Search (⌘K)" aria-keyshortcuts="Meta+K Control+K">${I('search', 18)}<span class="tool__label">Search</span><kbd class="kbd">⌘K</kbd></button>
        <button class="tool tool--focus" type="button" data-act="open-focus" aria-label="Focus timer" title="Focus timer">${I('timer', 18)}<span class="tool__label" id="focus-chip-text">Focus</span></button>
        <button class="tool tool--sync" type="button" data-act="open-sync" id="sync-chip">${I('cloudCheck', 18)}<span class="tool__label">All synced</span></button>
        <a class="tool tool--icon" href="trash.html" aria-label="Trash" ${T.view === 'trash' ? T.raw('aria-current="page"') : ''}>${I('trash', 18)}</a>
        <a class="tool tool--icon" href="settings.html" aria-label="Settings" ${T.view === 'settings' ? T.raw('aria-current="page"') : ''}>${I('settings', 18)}</a>
      </div>`.s;
    document.body.appendChild(line);
    /* Toasts sit above the line; it grows while the narrow capture is open. */
    if (window.ResizeObserver) new ResizeObserver(() => document.documentElement.style.setProperty('--line-live', line.offsetHeight + 'px')).observe(line);
    const sel = line.querySelector('#cap-type');
    sel.value = T.state.prefs.captureType || 'task';
    updateLine();
  }
  function brandMark() {
    return '<svg class="brand" viewBox="-12 -12 124 124" width="22" height="22" aria-hidden="true"><polygon points="' + HEX +
      '" fill="none" stroke="currentColor" stroke-width="12" stroke-linejoin="round"/><polygon points="50,30 67,40 67,60 50,70 33,60 33,40" fill="currentColor"/></svg>';
  }
  T.captureTopic = null;
  function updateLine() {
    const n = S.inboxItems().length;
    ['inbox-count', 'inbox-count-m'].forEach((id) => {
      const el = document.getElementById(id);
      if (el) { el.textContent = n ? String(n) : ''; el.hidden = !n; }
    });
    const viewsBtn = document.querySelector('.lv--current');
    if (viewsBtn) viewsBtn.setAttribute('aria-label', current.label + ', all views' + (n ? ', ' + n + ' in Inbox' : ''));
    const type = document.getElementById('cap-type');
    const dest = document.getElementById('cap-dest');
    const fixed = document.getElementById('cap-fixed');
    const input = document.getElementById('cap-title');
    if (type && dest) {
      const prev = dest.dataset.touched ? dest.value : (T.captureTopic || 'inbox');
      dest.innerHTML = html`<option value="inbox">Inbox</option>${S.topicsInOrder().map((t) => html`<option value="${t.id}">${t.name}</option>`)}`.s;
      dest.value = Array.from(dest.options).some((o) => o.value === prev) ? prev : 'inbox';
      const isTopic = type.value === 'topic';
      dest.hidden = isTopic;
      fixed.hidden = !isTopic;
      /* The C shortcut hint only helps with a keyboard and a wide layout. */
      const hint = matchMedia('(pointer: fine) and (min-width: 900px)').matches ? '  (press C)' : '';
      input.placeholder = 'Capture a ' + type.value + hint;
    }
    const [icon, text, tone] = syncInfo();
    const chip = document.getElementById('sync-chip');
    if (chip) {
      chip.className = 'tool tool--sync tone-' + tone;
      chip.innerHTML = T.icon(icon, 18) + '<span class="tool__label">' + esc(text) + '</span>';
      chip.setAttribute('aria-label', 'Save and sync status: ' + text);
    }
    focusTick();
  }
  T.updateLine = updateLine;
  T.on('cap-type', (el) => { T.state.prefs.captureType = el.value; T.persist(); updateLine(); });
  document.addEventListener('change', (e) => { if (e.target.id === 'cap-dest') e.target.dataset.touched = '1'; });
  T.on('capture', (form) => {
    const title = form.title.value.trim();
    const type = document.getElementById('cap-type').value;
    const destSel = document.getElementById('cap-dest');
    const dest = destSel.value === 'inbox' ? null : destSel.value;
    if (!title) { form.title.focus(); T.toast('Type a title first.'); return; }
    if (type === 'task') {
      const t = T.act.createTask({ title, topicId: dest });
      T.toast(dest ? 'Task added to ' + S.topic(dest).name : 'Task added to Inbox', { open: () => T.openTask(t.id) });
    } else if (type === 'note') {
      const nn = T.act.createNote({ title, topicIds: dest ? [dest] : [] });
      T.toast(dest ? 'Note added to ' + S.topic(dest).name : 'Note added to Inbox', { open: () => { location.href = 'notes.html?id=' + nn.id; } });
    } else {
      const tp = T.act.createTopic({ name: title });
      T.toast('Topic added to Topics', { open: () => { location.href = 'topic.html?id=' + tp.id; } });
    }
    form.title.value = '';
    form.title.focus();
  });
  T.focusCapture = function (type) {
    const sel = document.getElementById('cap-type');
    if (type && sel) { sel.value = type; T.state.prefs.captureType = type; T.persist(); updateLine(); }
    const input = document.getElementById('cap-title');
    const line = document.querySelector('.line');
    if (line) { line.classList.remove('is-flash'); void line.offsetWidth; line.classList.add('is-flash'); }
    if (input) input.focus();
  };
  T.on('focus-capture', (el) => T.focusCapture(el.dataset.type));
  T.on('open-search', () => T.openSearch());

  /* Narrow views sheet */
  T.on('open-views', () => {
    const n = S.inboxItems().length;
    const d = T.dialog(html`<div class="dlg__in views">
      <h2 class="dlg__title" id="dlg-title">Go to</h2>
      <nav aria-label="All views"><ul class="views__list">${allViews.map((v) => html`<li><a class="views__a" href="${v.href}" ${v.id === T.view ? T.raw('aria-current="page"') : ''}>
        ${I(v.icon, 20)}<span>${v.label}</span>${v.id === 'inbox' && n ? html`<span class="lv__count">${n}</span>` : ''}</a></li>`)}</ul></nav>
      <div class="views__tools"><button class="btn btn--line" type="button" data-act="views-search">${I('search', 16)} Search</button>
        <button class="btn btn--line" type="button" data-act="views-sync">${I('cloudCheck', 16)} Sync status</button>
        <a class="btn btn--text" href="index.html">Prototype home</a></div></div>`, { cls: 'dlg--bottom' });
    const cur = d.querySelector('[aria-current="page"]') || d.querySelector('a');
    if (cur) cur.focus();
    T._viewsDlg = d;
  });
  T.on('views-search', () => { if (T._viewsDlg) T._viewsDlg.close(); T.openSearch(); });
  T.on('views-sync', () => { if (T._viewsDlg) T._viewsDlg.close(); T.openSync(); });

  /* ---------------- Keyboard ---------------- */
  document.addEventListener('keydown', (e) => {
    const typing = e.target.closest && e.target.closest('input, textarea, select, [contenteditable="true"]');
    if ((e.metaKey || e.ctrlKey) && (e.key === 'k' || e.key === 'K')) {
      e.preventDefault();
      if (searchDlg && searchDlg.open) searchDlg.close(); else T.openSearch();
      return;
    }
    if (typing || e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.key === 'c' || e.key === 'C') {
      if (document.querySelector('dialog[open]')) return;
      e.preventDefault();
      T.focusCapture();
    }
  });

  /* ---------------- URL params and focus restore ---------------- */
  T.params = new URLSearchParams(location.search);
  function setParam(key, val) {
    const u = new URL(location.href);
    if (val == null) u.searchParams.delete(key); else u.searchParams.set(key, val);
    history.replaceState(null, '', u.pathname.split('/').pop() + u.search + u.hash);
  }
  T.setParam = setParam;
  function captureFocus(root) {
    const a = document.activeElement;
    if (!a || !root.contains(a)) return null;
    const k = a.closest('[data-k]');
    /* Keep half-typed text in the focused field when something else re-renders the page. */
    const typed = a.tagName === 'TEXTAREA' || (a.tagName === 'INPUT' && /^(text|search|url|email)$/.test(a.type));
    return k ? { k: k.dataset.k, start: a.selectionStart, end: a.selectionEnd, value: typed ? a.value : undefined } : null;
  }
  function restoreFocus(root, keep) {
    if (!keep) return;
    const el = root.querySelector('[data-k="' + (window.CSS && CSS.escape ? CSS.escape(keep.k) : keep.k) + '"]');
    if (el) {
      if (keep.value !== undefined && 'value' in el && el.value !== keep.value) el.value = keep.value;
      el.focus({ preventScroll: true });
      try { if (keep.start != null && el.setSelectionRange) el.setSelectionRange(keep.start, keep.end); } catch (e) { /* not a text field */ }
    }
  }
  T.captureFocus = captureFocus;
  T.restoreFocus = restoreFocus;

  /* ---------------- Page lifecycle ---------------- */
  T.page = null;
  T.definePage = function (page) { T.page = page; };
  function renderPage(source) {
    const main = document.getElementById('main');
    if (!main || !T.page) return;
    if (source === 'focus') return; /* the timer lives in the status line and its popover only */
    if (T.page.skip && T.page.skip(source)) return;
    const keep = captureFocus(main);
    T.page.render(main, source);
    restoreFocus(main, keep);
  }
  T.rerender = renderPage;
  T.subscribe((source) => {
    if (document.querySelector('.line')) updateLine();
    renderReminderBanner();
    renderPage(source);
    if (taskDlg && taskDlg.open) renderTaskSheet();
    if (focusDlg && focusDlg.open && source !== 'tick') renderFocus();
    if (syncDlg && syncDlg.open) renderSync();
    T.placeToast();
  });

  document.addEventListener('DOMContentLoaded', () => {
    if (document.body.dataset.page !== 'index') {
      buildLine();
      const toast = document.createElement('div');
      toast.id = 'toast'; toast.className = 'toast'; toast.setAttribute('role', 'status'); toast.setAttribute('aria-live', 'polite');
      document.body.appendChild(toast);
      toastEl = toast;
      /* 'close' does not bubble, so listen in the capture phase. */
      document.addEventListener('close', (e) => { if (e.target.contains(toast)) document.body.appendChild(toast); }, true);
    }
    renderReminderBanner();
    renderPage('init');
    T.noteSync.resume();
    if (T.params.get('task')) T.openTask(T.params.get('task'));
    if (T.params.get('search')) T.openSearch(T.params.get('q') || '');
    if (T.params.get('focus')) T.openFocus();
    if (T.params.get('capture')) { T.setParam('capture', null); T.focusCapture(); }
  });
})();
