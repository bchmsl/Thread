/* Settings: connections, Topic statuses (custom names mapped to four fixed categories), preferences, demo controls. */
(function () {
  const T = window.T;
  const S = T.sel;
  const html = T.html;
  const I = T.I;
  const X = T.ext;
  const CATS = ['planned', 'active', 'paused', 'done'];
  const CAT_HINT = { planned: 'not started', active: 'shows in Today', paused: 'on hold', done: 'finished' };

  function block(id, title, text, body) {
    return html`<section class="set" id="${id}" aria-labelledby="${id}-h">
      <div><h2 class="set__h" id="${id}-h">${title}</h2>${text ? html`<p class="set__p">${text}</p>` : ''}</div>
      <div class="set__body">${body}</div></section>`;
  }
  const catOptions = (sel) => CATS.map((c) => html`<option value="${c}" ${c === sel ? 'selected' : ''}>${T.CAT_LABEL[c]}</option>`);

  function connections() {
    const row = (sys, name, icon, what) => {
      const on = X.connected(sys);
      return html`<div class="conn"><span class="conn__icon">${I(icon, 20)}</span>
        <div class="conn__body"><p class="conn__name">${name}</p>
          <p class="conn__st ${on ? 'is-on' : ''}">${I(on ? 'check' : 'unlink', 14)} ${on ? 'Connected, read-only (demo)' : 'Not connected'}</p>
          <p class="pref__hint">${what}</p></div>
        <button class="btn ${on ? 'btn--text btn--danger-text' : 'btn--line'}" type="button" data-act="set-conn" data-sys="${sys}" data-k="conn-${sys}">${on ? 'Disconnect' : 'Connect (demo)'}</button></div>`;
    };
    return html`${row('jira', 'Jira', 'jira', 'Assigned tickets and search. Descriptions and comments are kept on this device only.')}
      ${row('github', 'GitHub', 'pr', 'Your open pull requests and review requests.')}
      <p class="callout">${I('lock', 15)} The demo never asks for a password or token. A real connection would open the provider's own sign-in page and ask for read-only access.</p>`;
  }

  function statuses() {
    const list = T.state.statuses;
    const only = list.length <= 1;
    return html`
      <p class="ser-legend">${CATS.map((c) => html`<span class="st st--${c}">${T.swatch(c)}<span class="st__name">${T.CAT_LABEL[c]}</span></span><span>${CAT_HINT[c]}</span>`)}</p>
      <ul class="ser-list">${list.map((s, i) => html`<li class="ser">
        <span class="ser__order">
          <button class="iconbtn iconbtn--sm" type="button" data-act="st-move" data-id="${s.id}" data-d="-1" data-k="up-${s.id}" ${i === 0 ? 'disabled' : ''} aria-label="Move ${s.name} up">${I('arrowUp', 16)}</button>
          <button class="iconbtn iconbtn--sm" type="button" data-act="st-move" data-id="${s.id}" data-d="1" data-k="dn-${s.id}" ${i === list.length - 1 ? 'disabled' : ''} aria-label="Move ${s.name} down">${I('arrowDown', 16)}</button></span>
        <span><label class="vh" for="stn-${s.id}">Status name</label>
          <input class="input input--sm" id="stn-${s.id}" value="${s.name}" data-change="st-name" data-id="${s.id}" data-k="stn-${s.id}" maxlength="24"></span>
        <span class="ser__cat">${T.swatch(s.category)}<label class="vh" for="stc-${s.id}">Category for ${s.name}</label>
          <select class="input input--sm" id="stc-${s.id}" data-change="st-cat" data-id="${s.id}" data-k="stc-${s.id}">${catOptions(s.category)}</select></span>
        <span class="ser__use">${S.statusUseCount(s.id) ? 'Used by ' + S.statusUseCount(s.id) : 'Not used'}</span>
        <button class="iconbtn iconbtn--sm" type="button" data-act="st-del" data-id="${s.id}" data-k="del-${s.id}" ${only ? 'disabled' : ''} aria-label="Delete ${s.name}">${I('trash', 16)}</button>
      </li>`)}</ul>
      <form class="ser-add" data-submit="st-add" autocomplete="off">
        <label class="vh" for="st-new">New status name</label>
        <input class="input" id="st-new" name="name" placeholder="New status, for example Waiting on QA" maxlength="24" data-k="st-new" required>
        <label class="vh" for="st-new-cat">Category</label>
        <select class="input" id="st-new-cat" name="cat" data-k="st-new-cat">${catOptions('active')}</select>
        <button class="btn btn--primary" type="submit">Add status</button>
      </form>`;
  }

  function prefs() {
    const p = T.state.prefs;
    return html`
      <div class="pref"><div><p class="pref__label" id="pv-l">Topics opens as</p><p class="pref__hint">You can switch any time on the Topics page.</p></div>
        <div class="seg" role="radiogroup" aria-labelledby="pv-l">
          <button type="button" role="radio" aria-checked="${p.topicsView !== 'board'}" data-act="pref-view" data-v="list" data-k="pv-list">List</button>
          <button type="button" role="radio" aria-checked="${p.topicsView === 'board'}" data-act="pref-view" data-v="board" data-k="pv-board">Board</button></div></div>
      <div class="pref"><div><label class="pref__label" for="pref-keep">Keep completed tasks in Today</label><p class="pref__hint">They stay, struck through, until the end of the day.</p></div>
        <input class="switch" type="checkbox" role="switch" id="pref-keep" data-change="pref-keep" data-k="pref-keep" ${p.keepCompleted ? 'checked' : ''}></div>
      <div class="pref"><div><label class="pref__label" for="pref-cap">Capture starts as</label><p class="pref__hint">The type picked in the bottom bar when a page opens.</p></div>
        <select class="input" id="pref-cap" data-change="pref-cap" data-k="pref-cap">
          ${['task', 'note', 'topic'].map((v) => html`<option value="${v}" ${p.captureType === v ? 'selected' : ''}>${v[0].toUpperCase() + v.slice(1)}</option>`)}</select></div>`;
  }

  function demo() {
    const online = T.state.demo.online;
    return html`<div class="demo-grid">
      ${T.demoBar('Network', html`<div class="seg" role="radiogroup" aria-label="Network (demo)">
        <button type="button" role="radio" aria-checked="${online}" data-act="set-online" data-v="1" data-k="net-on">Online</button>
        <button type="button" role="radio" aria-checked="${!online}" data-act="set-online" data-v="0" data-k="net-off">Offline</button></div>`)}
      ${X.demo('jira')}${X.demo('github')}
      ${T.demoBar('Data', html`<button class="btn btn--line btn--sm" type="button" data-act="demo-reset" data-v="example">${I('refresh', 15)} Reset example data</button>
        <button class="btn btn--line btn--sm" type="button" data-act="demo-reset" data-v="empty">Start empty</button>
        <button class="btn btn--text btn--sm" type="button" data-act="demo-clock">Reset clock</button>`)}
    </div>`;
  }

  function account() {
    const s = T.state.session;
    const txt = !s ? 'Not signed in. The demo still works on this device.' : s.mode === 'skip' ? 'Local only. You skipped sign-in, so nothing syncs.' : 'Signed in as Demo user (synthetic).';
    return html`<div class="pref"><div><p class="pref__label">${txt}</p><p class="pref__hint">Workspace: ${T.state.workspace === 'empty' ? 'empty' : 'example data'}.</p></div>
      ${s ? html`<button class="btn btn--line" type="button" data-act="sign-out">Sign out</button>` : html`<a class="btn btn--line" href="index.html">Sign in</a>`}</div>`;
  }

  T.definePage({
    skip: (source) => source === 'editor',
    render(main) {
      main.innerHTML = html`
        <header class="ph"><div><h1 class="ph__title">Settings</h1><p class="ph__sub">Everything here applies to this demo workspace.</p></div></header>
        ${block('connections', 'Connections', 'Jira and GitHub are read-only in v1.', connections())}
        ${block('statuses', 'Topic statuses', 'Name statuses your way. Each maps to one of four categories, which decide where a Topic shows up.', statuses())}
        ${block('prefs', 'Preferences', '', prefs())}
        ${block('appearance', 'Appearance', '', html`<p class="pref__label">Dark</p><p class="pref__hint">Dark is the only theme in the pilot.</p>`)}
        ${block('demo', 'Demo controls', 'Simulate states. These are not product settings.', demo())}
        ${block('account', 'Account', '', account())}`.s;
      if (location.hash && !T._scrolled) {
        T._scrolled = true;
        const el = document.querySelector(location.hash);
        if (el) el.scrollIntoView();
      }
    }
  });

  /* Connections */
  T.on('set-conn', async (el) => {
    const sys = el.dataset.sys;
    const name = sys === 'jira' ? 'Jira' : 'GitHub';
    if (X.connected(sys)) {
      const ok = await T.confirm({ title: 'Disconnect ' + name + '?', body: 'Topic links stay. ' + name + ' lists and saved copies hide until you connect again.', confirmLabel: 'Disconnect', danger: true });
      if (!ok) return;
      T.act.setConnection(sys, false);
      T.toast(name + ' disconnected');
    } else {
      T.act.setConnection(sys, true);
      T.toast(name + ' connected (demo, read-only)');
    }
  });

  /* Statuses */
  function snapshot() {
    const statuses = JSON.parse(JSON.stringify(T.state.statuses));
    const map = {};
    T.state.topics.forEach((t) => { map[t.id] = t.statusId; });
    return () => { T.state.statuses = statuses; T.state.topics.forEach((t) => { if (map[t.id]) t.statusId = map[t.id]; }); T.commit(); };
  }
  T.on('st-move', (el) => T.act.moveStatus(el.dataset.id, Number(el.dataset.d)));
  T.on('st-name', (el) => {
    const v = el.value.trim();
    const s = S.status(el.dataset.id);
    if (!v) { el.value = s.name; T.toast('A status needs a name'); return; }
    if (v === s.name) return;
    const undo = snapshot();
    T.act.updateStatus(s.id, { name: v });
    T.toast('Renamed to ' + v + '. Topics, Board and Today use the new name.', { undo });
  });
  T.on('st-cat', (el) => {
    const s = S.status(el.dataset.id);
    const undo = snapshot();
    T.act.updateStatus(s.id, { category: el.value });
    const n = S.statusUseCount(s.id);
    T.toast(s.name + ' now counts as ' + T.CAT_LABEL[el.value] + (n ? '. ' + n + (n === 1 ? ' Topic moved' : ' Topics moved') + ' with it.' : '.'), { undo });
  });
  T.on('st-add', (form) => {
    const name = form.elements.name.value.trim();
    if (!name) return;
    if (T.state.statuses.some((s) => s.name.toLowerCase() === name.toLowerCase())) { T.toast('A status with that name already exists'); return; }
    const undo = snapshot();
    T.act.addStatus(name, form.elements.cat.value);
    T.toast('Added ' + name + '. It shows as a lane on the Board.', { undo });
    const el = document.getElementById('st-new');
    if (el) { el.value = ''; el.focus(); }
  });
  T.on('st-del', async (el) => {
    const s = S.status(el.dataset.id);
    const used = S.statusUseCount(s.id);
    const undo = snapshot();
    if (!used) {
      const ok = await T.confirm({ title: 'Delete ' + s.name + '?', body: 'No Topic uses it.', confirmLabel: 'Delete status', danger: true });
      if (!ok) return;
      T.act.deleteStatus(s.id, null);
      T.toast('Deleted ' + s.name, { undo });
      return;
    }
    const others = T.state.statuses.filter((x) => x.id !== s.id);
    const def = others.find((x) => x.category === s.category) || others[0];
    const d = T.dialog(html`<form method="dialog" class="dlg__in">
      <h2 class="dlg__title" id="dlg-title">Delete ${s.name}?</h2>
      <p class="dlg__text">${used} ${used === 1 ? 'Topic uses' : 'Topics use'} it. Pick the status they move to. No Topic is deleted.</p>
      <fieldset class="picklist"><legend class="vh">Move Topics to</legend>
        ${others.map((x) => html`<label class="pick"><input type="radio" name="rep" value="${x.id}" ${x.id === def.id ? 'checked' : ''}>
          ${T.statusLabel(x)}${x.name === T.CAT_LABEL[x.category] ? '' : html`<span class="pick__sub" aria-hidden="true">${T.CAT_LABEL[x.category]}</span>`}</label>`)}</fieldset>
      <div class="dlg__acts"><button class="btn btn--line" value="cancel">Cancel</button>
        <button class="btn btn--danger" value="ok">Delete and move Topics</button></div></form>`,
    { onClose: (v) => {
      if (v !== 'ok') return;
      const rep = d.querySelector('input[name="rep"]:checked').value;
      T.act.deleteStatus(s.id, rep);
      T.toast('Deleted ' + s.name + '. ' + used + (used === 1 ? ' Topic' : ' Topics') + ' moved to ' + S.status(rep).name + '.', { undo });
    } });
    d.querySelector('input[name="rep"]:checked').focus();
  });

  /* Preferences */
  T.on('pref-view', (el) => T.act.setPref('topicsView', el.dataset.v));
  T.on('pref-keep', (el) => T.act.setPref('keepCompleted', el.checked));
  T.on('pref-cap', (el) => {
    T.act.setPref('captureType', el.value);
    const sel = document.getElementById('cap-type');
    if (sel) { sel.value = el.value; T.updateLine(); }
  });

  /* Demo and account */
  T.on('demo-reset', async (el) => {
    const empty = el.dataset.v === 'empty';
    const ok = await T.confirm({ title: empty ? 'Start with an empty workspace?' : 'Reset the example data?',
      body: 'This replaces all demo data in this browser. Nothing else is affected.', confirmLabel: empty ? 'Start empty' : 'Reset data', danger: true });
    if (!ok) return;
    T.reset(el.dataset.v);
    T.toast(empty ? 'Workspace is empty' : 'Example data restored');
  });
  T.on('demo-clock', () => { T.act.resetClock(); T.toast('Demo clock reset to Fri 13 Nov 13:05'); });
  T.on('sign-out', async () => {
    const ok = await T.confirm({ title: 'Sign out?', body: 'Demo data stays in this browser.', confirmLabel: 'Sign out' });
    if (!ok) return;
    T.act.signOut();
    location.href = 'index.html';
  });
})();
