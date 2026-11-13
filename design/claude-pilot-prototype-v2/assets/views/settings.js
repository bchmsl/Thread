/* Settings: demo connections, workspace preferences, ordered Topic statuses, demo account. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const ui = T.ui;
  const esc = T.esc;
  const icon = T.icon;
  const store = T.store;
  const CAT_ORDER = ['planned', 'active', 'paused', 'done'];

  const SOURCES = {
    jira: {
      name: 'Jira', logo: 'ticket', flag: 'jiraConnected', sim: 'jira',
      reads: 'Reads issues assigned to you, issues you open from search, their fields and comments.',
      cache: 'Descriptions and comments are cached on this device only. They are never uploaded to your Thread cloud copy.'
    },
    github: {
      name: 'GitHub', logo: 'pr', flag: 'githubConnected', sim: 'github',
      reads: 'Reads your open pull requests and the ones that request your review, with CI and review state.',
      cache: 'Pull request details are cached on this device only. Topic links are the only thing that syncs.'
    }
  };

  function usage(id) { return T.state().topics.filter((t) => t.statusId === id).length; }
  function catCount(cat) { return T.state().statuses.filter((x) => x.cat === cat).length; }

  function connections() {
    const s = T.state();
    const card = (key) => {
      const src = SOURCES[key];
      const on = s.sim[src.flag];
      return '<div class="conn"><div class="conn-head"><span class="logo">' + icon(src.logo) + '</span><div><strong>' + src.name + '</strong>' +
        (on ? '<span class="conn-state on">' + icon('circleCheck') + 'Connected to a demo account</span>' : '<span class="conn-state off">' + icon('circleDot') + 'Not connected</span>') + '</div></div>' +
        '<p>' + src.reads + '</p><p>' + src.cache + '</p>' +
        '<p class="faint" style="font-size:12.5px">' + icon('lock') + ' Read-only. Thread never changes anything in ' + src.name + '.</p>' +
        '<div class="chips">' + (on ? '<button type="button" class="btn btn-sm" data-act="connOff" data-src="' + key + '">' + icon('unlink') + 'Disconnect</button><a class="btn btn-sm btn-ghost" href="' + (key === 'jira' ? 'jira.html' : 'prs.html') + '">Open ' + (key === 'jira' ? 'Jira' : 'pull requests') + '</a>'
          : '<button type="button" class="btn btn-sm" data-act="connOn" data-src="' + key + '">' + icon('link') + 'Connect ' + src.name + '</button>') + '</div></div>';
    };
    return '<section class="sblock" id="connections" aria-labelledby="connH"><h2 id="connH">Connections</h2>' +
      '<p>Jira and GitHub are read-only sources. Topic links to their records sync; their content stays on this device.</p>' +
      '<div class="conn-grid">' + card('jira') + card('github') + '</div>' +
      '<div class="safe" style="margin-top:14px">' + icon('lock') + '<span>This prototype never asks for a password, API token or key. A real connection opens the provider’s own sign-in page and asks only for read access.</span></div></section>';
  }

  function workspace() {
    const p = T.state().prefs;
    const opt = (v, l, cur) => '<option value="' + v + '"' + (v === cur ? ' selected' : '') + '>' + l + '</option>';
    return '<section class="sblock" id="workspace" aria-labelledby="wsH"><h2 id="wsH">Workspace</h2><p>Preferences for this workspace. They sync with your app-owned content.</p><div class="prefs">' +
      '<div class="pref-row"><label for="prefStart">Open Thread on</label><select class="select" id="prefStart" data-change="pref" data-field="startPage">' +
        opt('today', 'Today', p.startPage) + opt('inbox', 'Inbox', p.startPage) + opt('topics', 'Topics', p.startPage) + opt('notes', 'Notes', p.startPage) + '</select></div>' +
      '<div class="pref-row"><label for="prefView">Topics layout</label><select class="select" id="prefView" data-change="pref" data-field="topicsView">' +
        opt('list', 'List', p.topicsView) + opt('board', 'Board', p.topicsView) + '</select></div>' +
      '<div class="pref-row"><label for="prefRem">Default reminder time</label><input type="time" class="input" id="prefRem" data-change="pref" data-field="reminderTime" value="' + esc(p.reminderTime) + '"></div>' +
      '<div class="pref-row"><span>Done topics</span><label class="chipcheck"><input type="checkbox" data-change="prefDone"' + (p.showDone ? ' checked' : '') + '><span>Show done topics in the list</span></label></div>' +
      '<div class="pref-row"><span>Appearance</span><span class="faint">Dark only. There is no light theme in this version.</span></div>' +
    '</div></section>';
  }

  function statuses() {
    const s = T.state();
    const legend = CAT_ORDER.map((c) => '<div>' + ui.status({ name: T.CATEGORIES[c].label, cat: c }) + '<span>' + esc(T.CATEGORIES[c].hint) + '</span></div>').join('');
    const rows = s.statuses.map((st, i) => {
      const n = usage(st.id);
      return '<li class="srow"><span class="pos" aria-hidden="true">' + (i + 1) + '</span>' +
        '<span class="name-wrap">' + ui.status(st, { noName: true }) + '<label class="vh" for="sname-' + st.id + '">Status name</label><input class="input" id="sname-' + st.id + '" value="' + esc(st.name) + '" data-change="statusName" data-id="' + st.id + '" maxlength="32"></span>' +
        '<label class="vh" for="scat-' + st.id + '">Category for ' + esc(st.name) + '</label><select class="select select-sm catsel" id="scat-' + st.id + '" data-change="statusCat" data-id="' + st.id + '">' +
          CAT_ORDER.map((c) => '<option value="' + c + '"' + (c === st.cat ? ' selected' : '') + '>' + T.CATEGORIES[c].label + '</option>').join('') + '</select>' +
        '<span class="usage">' + (n ? n + ' topic' + (n === 1 ? '' : 's') : 'Not used') + '</span>' +
        '<span class="moves"><button type="button" class="iconbtn iconbtn-sm" data-act="statusMove" data-id="' + st.id + '" data-value="up" aria-label="Move ' + esc(st.name) + ' up"' + (i === 0 ? ' disabled' : '') + '>' + icon('arrowUp') + '</button>' +
          '<button type="button" class="iconbtn iconbtn-sm" data-act="statusMove" data-id="' + st.id + '" data-value="down" aria-label="Move ' + esc(st.name) + ' down"' + (i === s.statuses.length - 1 ? ' disabled' : '') + '>' + icon('arrowDown') + '</button>' +
          '<button type="button" class="iconbtn iconbtn-sm" data-act="statusDelete" data-id="' + st.id + '" aria-label="Delete ' + esc(st.name) + '">' + icon('trash') + '</button></span></li>';
    }).join('');
    return '<section class="sblock" id="statuses" aria-labelledby="stH"><h2 id="stH">Topic statuses</h2>' +
      '<p>Name statuses the way you work. Each one belongs to a category, and the category decides behavior: Active topics show in Today and in the tab strip. The order here is the Board lane order and the List sort.</p>' +
      '<div class="catlegend">' + legend + '</div>' +
      '<ol class="statuslist" aria-label="Statuses in order">' + rows + '</ol>' +
      '<form class="sadd" data-submit="statusAdd"><label class="vh" for="newStatus">New status name</label><input class="input" id="newStatus" name="name" placeholder="New status, for example Blocked" maxlength="32" required>' +
        '<label class="vh" for="newStatusCat">Category</label><select class="select" id="newStatusCat" name="cat">' + CAT_ORDER.map((c) => '<option value="' + c + '"' + (c === 'paused' ? ' selected' : '') + '>' + T.CATEGORIES[c].label + '</option>').join('') + '</select>' +
        '<button type="submit" class="btn">' + icon('plus') + 'Add status</button></form>' +
      '<p class="faint" style="font-size:12.5px;margin-top:12px">Every category keeps at least one status, so a topic can always be planned, active, paused or done. Task completion is a checkbox and does not use these statuses.</p></section>';
  }

  function account() {
    const s = T.state();
    return '<section class="sblock" id="demo" aria-labelledby="demoH" style="border-bottom:0"><h2 id="demoH">Demo account and data</h2>' +
      '<p>' + (s.session.signedIn ? 'Signed in as ' + esc(s.session.user) + ', a demo account.' : 'Signed out. Local work stays on this device.') + ' ' +
        (T.store.storageOk() ? 'Demo data is saved in this browser’s local storage.' : 'This browser blocks local storage, so demo changes last only until you close the tab.') + '</p>' +
      '<div class="chips">' + (s.session.signedIn ? '<button type="button" class="btn" data-act="signOut">' + icon('signOut') + 'Sign out (demo)</button>' : '<button type="button" class="btn" data-act="signIn">Sign in again (demo)</button>') +
        '<button type="button" class="btn btn-ghost" data-act="resetDemo">' + icon('refresh') + 'Reset to the example workspace</button>' +
        '<button type="button" class="btn btn-ghost" data-act="startEmpty">Start with an empty workspace</button></div></section>';
  }

  function render() {
    return T.pageHead('Settings', 'Demo settings. Every change stays in this browser.') +
      '<div class="settings"><nav class="settings-nav" aria-label="Settings sections"><a href="#connections">Connections</a><a href="#workspace">Workspace</a><a href="#statuses">Topic statuses</a><a href="#demo">Demo account</a></nav>' +
      '<div>' + connections() + workspace() + statuses() + account() + '</div></div>';
  }

  /* ---------- connections ---------- */
  T.act.connOn = (el) => {
    const key = el.dataset.src;
    const src = SOURCES[key];
    T.dialog('connDlg', {
      label: 'connH2',
      render: () => '<div class="dlg"><div class="dlg-head"><h2 id="connH2">Connect ' + src.name + '</h2><button type="button" class="iconbtn" data-act="closeDlg" aria-label="Close">' + icon('x') + '</button></div>' +
        '<div class="dlg-body"><p>In the real app, this opens ' + src.name + '’s own sign-in page in your browser. Thread asks only for read access and never sees your password.</p>' +
        '<p class="muted" style="margin-top:10px">In this prototype, it connects a demo account with synthetic data. Nothing leaves this browser.</p></div>' +
        '<div class="dlg-foot"><button type="button" class="btn" data-act="closeDlg">Cancel</button><button type="button" class="btn-key" data-act="connConfirm" data-src="' + key + '">' + icon('link') + '<span>Connect demo account</span></button></div></div>',
      focus: '.btn-key'
    });
  };
  T.act.connConfirm = (el) => {
    const src = SOURCES[el.dataset.src];
    T.closeDialog('connDlg');
    store.update((s) => { s.sim[src.flag] = true; s.sim[src.sim] = 'fresh'; });
    T.toast(src.name + ' connected (demo)');
  };
  T.act.connOff = (el) => {
    const src = SOURCES[el.dataset.src];
    store.update((s) => { s.sim[src.flag] = false; });
    T.toast(src.name + ' disconnected. Topic links stay; they show again when you reconnect.', { undo: () => store.update((s) => { s.sim[src.flag] = true; }) });
  };

  /* ---------- workspace ---------- */
  T.act.pref = (el) => {
    const v = el.value;
    if (!v) return;
    store.update((s) => { s.prefs[el.dataset.field] = v; });
    T.toast('Saved');
  };
  T.act.prefDone = (el) => store.update((s) => { s.prefs.showDone = el.checked; });

  /* ---------- statuses ---------- */
  T.act.statusName = (el) => {
    const v = el.value.trim();
    const st = S.statusById(el.dataset.id);
    if (!v) { el.value = st.name; T.toast('A status needs a name'); return; }
    store.update((s) => { s.statuses.find((x) => x.id === st.id).name = v; });
    T.toast('Renamed to ' + v + '. Topics keep their status.');
  };
  T.act.statusCat = (el) => {
    const st = S.statusById(el.dataset.id);
    const to = el.value;
    if (catCount(st.cat) === 1) {
      el.value = st.cat;
      T.toast(st.name + ' is the only ' + T.CATEGORIES[st.cat].label + ' status. Add another one first.');
      return;
    }
    const n = usage(st.id);
    const from = st.cat;
    store.update((s) => { s.statuses.find((x) => x.id === st.id).cat = to; });
    let effect = '';
    if (n && to === 'active') effect = ' ' + n + ' topic' + (n === 1 ? ' now shows' : 's now show') + ' in Today.';
    if (n && from === 'active' && to !== 'active') effect = ' ' + n + ' topic' + (n === 1 ? ' leaves' : 's leave') + ' Today.';
    T.toast(st.name + ' is now in ' + T.CATEGORIES[to].label + '.' + effect, { undo: () => store.update((s) => { s.statuses.find((x) => x.id === st.id).cat = from; }) });
  };
  T.act.statusMove = (el) => {
    const id = el.dataset.id;
    const dir = el.dataset.value === 'up' ? -1 : 1;
    store.update((s) => {
      const i = s.statuses.findIndex((x) => x.id === id);
      const j = i + dir;
      if (j < 0 || j >= s.statuses.length) return;
      const tmp = s.statuses[i];
      s.statuses[i] = s.statuses[j];
      s.statuses[j] = tmp;
    });
    /* keep focus on the same arrow, or the other one when this arrow became disabled */
    requestAnimationFrame(() => requestAnimationFrame(() => {
      const same = document.querySelector('[data-act="statusMove"][data-id="' + id + '"][data-value="' + el.dataset.value + '"]');
      const other = document.querySelector('[data-act="statusMove"][data-id="' + id + '"]:not([disabled])');
      const f = same && !same.disabled ? same : other;
      if (f) f.focus();
    }));
  };
  T.act.statusAdd = (form) => {
    const fd = new FormData(form);
    const name = String(fd.get('name') || '').trim();
    const cat = String(fd.get('cat'));
    if (!name) return;
    store.update((s) => {
      let at = -1;
      s.statuses.forEach((x, i) => { if (x.cat === cat) at = i; });
      s.statuses.splice(at + 1, 0, { id: T.util.uid('s'), name: name, cat: cat });
    });
    T.toast('Added ' + name + ' to ' + T.CATEGORIES[cat].label);
    requestAnimationFrame(() => requestAnimationFrame(() => { const i = document.getElementById('newStatus'); if (i) { i.value = ''; i.focus(); } }));
  };

  let replaceWith = '';
  T.act.statusDelete = (el) => {
    const st = S.statusById(el.dataset.id);
    if (catCount(st.cat) === 1) { T.toast(st.name + ' is the only ' + T.CATEGORIES[st.cat].label + ' status, so it cannot be deleted.'); return; }
    const n = usage(st.id);
    if (!n) {
      const idx = T.state().statuses.findIndex((x) => x.id === st.id);
      store.update((s) => { s.statuses = s.statuses.filter((x) => x.id !== st.id); });
      T.toast('Deleted ' + st.name, { undo: () => store.update((s) => { s.statuses.splice(idx, 0, st); }) });
      return;
    }
    replaceWith = '';
    T.dialog('statusDlg', {
      label: 'stDelH',
      render: () => {
        const others = T.state().statuses.filter((x) => x.id !== st.id);
        const users = T.state().topics.filter((t) => t.statusId === st.id);
        return '<div class="dlg"><div class="dlg-head"><h2 id="stDelH">Delete “' + esc(st.name) + '”?</h2><button type="button" class="iconbtn" data-act="closeDlg" aria-label="Close">' + icon('x') + '</button></div>' +
          '<div class="dlg-body"><p>' + n + ' topic' + (n === 1 ? ' uses' : 's use') + ' this status. Choose where to move ' + (n === 1 ? 'it' : 'them') + ' first.</p>' +
          '<div class="ttab-row" style="margin:12px 0">' + users.map((t) => ui.ttab(t, { size: 'sm', link: false })).join('') + '</div>' +
          '<label class="field"><span>Move to</span><select class="select" id="replaceSel" data-change="statusReplace"><option value="">Choose a status</option>' +
            others.map((x) => '<option value="' + x.id + '"' + (x.id === replaceWith ? ' selected' : '') + '>' + esc(x.name) + (x.name === T.CATEGORIES[x.cat].label ? '' : ' (' + T.CATEGORIES[x.cat].label + ')') + '</option>').join('') + '</select></label>' +
          '<p class="faint" id="replaceEffect" style="font-size:12.5px;margin-top:8px">' + effectText(st, n) + '</p></div>' +
          '<div class="dlg-foot"><button type="button" class="btn" data-act="closeDlg">Cancel</button><button type="button" class="btn-key" id="replaceGo" data-act="statusDeleteGo" data-id="' + st.id + '"' + (replaceWith ? '' : ' disabled') + '>' + icon('trash') + '<span>Move ' + n + ' and delete</span></button></div></div>';
      },
      focus: '#replaceSel'
    });
  };
  function effectText(st, n) {
    if (!replaceWith) return 'The delete button turns on after you choose.';
    const to = S.statusById(replaceWith);
    if (to.cat === st.cat) return 'Same category, so nothing changes in Today.';
    if (to.cat === 'active') return n + ' topic' + (n === 1 ? '' : 's') + ' will show in Today.';
    if (st.cat === 'active') return n + ' topic' + (n === 1 ? '' : 's') + ' will leave Today.';
    return 'They will move to the ' + to.name + ' lane on the Board.';
  }
  T.act.statusReplace = (el) => {
    replaceWith = el.value;
    const go = document.getElementById('replaceGo');
    if (go) go.disabled = !replaceWith;
    const st = S.statusById(document.getElementById('replaceGo').dataset.id);
    const e = document.getElementById('replaceEffect');
    if (e) e.textContent = effectText(st, usage(st.id));
  };
  T.act.statusDeleteGo = (el) => {
    if (!replaceWith) return;
    const st = S.statusById(el.dataset.id);
    const to = S.statusById(replaceWith);
    const n = usage(st.id);
    T.closeDialog('statusDlg');
    store.update((s) => {
      s.topics.forEach((t) => { if (t.statusId === st.id) t.statusId = to.id; });
      s.statuses = s.statuses.filter((x) => x.id !== st.id);
    });
    T.toast('Deleted ' + st.name + '. Moved ' + n + ' topic' + (n === 1 ? '' : 's') + ' to ' + to.name + '.');
  };

  /* ---------- account ---------- */
  T.act.signOut = () => {
    store.update((s) => { s.session.signedIn = false; });
    T.toast('Signed out of the demo. Your local work is still here.');
  };
  T.act.startEmpty = () => {
    T.dialog('confirmDlg', {
      label: 'emptyH',
      render: () => '<div class="dlg"><div class="dlg-head"><h2 id="emptyH">Start with an empty workspace?</h2></div>' +
        '<div class="dlg-body"><p class="muted">This replaces the demo data in this browser with an empty workspace. You can bring the example back from this page.</p></div>' +
        '<div class="dlg-foot"><button type="button" class="btn" data-act="closeDlg">Cancel</button><button type="button" class="btn-key" data-act="confirmEmpty"><span>Start empty</span></button></div></div>',
      focus: '.btn'
    });
  };
  T.act.confirmEmpty = () => { T.closeDialog('confirmDlg'); store.reset('empty'); T.toast('Empty workspace ready'); };

  T.views.settings = { title: 'Settings', render: render };
})();
