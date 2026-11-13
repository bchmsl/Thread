/* Entry: demo sign-in, first-run workspace choice, a skip path, and the prototype map.
   No field here ever asks for a password or token. */
(function () {
  'use strict';
  const T = window.Thread;
  const esc = T.esc;
  const icon = T.icon;
  const store = T.store;

  /* First run: nothing saved yet. Seed signed out and remember that no workspace was chosen,
     so a reload keeps showing sign-in instead of "Welcome back". */
  if (!store.exists()) store.update((s) => { s.session.signedIn = false; s.session.fresh = true; }, { silent: true });
  const firstRun = !!T.state().session.fresh;
  let step = null;

  const PAGES = { today: 'today.html', inbox: 'inbox.html', topics: 'topics.html', notes: 'notes.html' };
  function startHref() { return PAGES[T.state().prefs.startPage] || 'today.html'; }

  function currentStep() {
    if (step) return step;
    const s = T.state();
    if (!s.session.signedIn) return 'signin';
    if (s.session.fresh) return 'workspace';
    return 'back';
  }

  function art() {
    const tab = (name, color, cat, catName, line) => '<div class="etab" style="--c:' + T.COLORS[color] + '"><span class="ttab">' + esc(name) + '</span>' +
      '<div class="etab-body"><span>' + esc(line) + '</span><span class="status" data-cat="' + cat + '"><span class="tile">' + T.glyph(cat) + '</span>' + catName + '</span></div></div>';
    return '<section class="entry-art" aria-label="About Thread">' +
      '<div class="entry-brand"><span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>Thread</div>' +
      '<div><p class="lede">Pick up every piece of work where you left it.</p>' +
        '<p class="lede-sub">Tasks, notes, Jira issues and pull requests, filed under the topic they belong to. Today shows what needs you now.</p>' +
        '<div class="entry-tabs" aria-hidden="true">' +
          tab('QR checkout', 'lavender', 'active', 'Active', 'Next: test timeout and retry') +
          tab('Offline card wallet', 'mint', 'active', 'In review', 'Next: answer review comments') +
          tab('Push token migration', 'peach', 'paused', 'Paused', 'Waiting on backend') +
        '</div></div>' +
      '<p class="faint" style="font-size:12.5px;display:flex;gap:8px;align-items:center">' + icon('flask') + 'Design prototype v2. Every name, issue and pull request is synthetic.</p>' +
    '</section>';
  }

  function panel() {
    const st = currentStep();
    const s = T.state();
    if (st === 'signin') {
      return '<div><h1>Sign in to Thread</h1><p class="lead">A personal workspace for your topics. This prototype signs in to a demo account.</p></div>' +
        '<button type="button" class="btn-key" data-act="entrySignIn" style="justify-self:start">' + icon('user') + '<span>Continue with the demo account</span></button>' +
        '<div class="safe">' + icon('lock') + '<span>No password, token or key is ever asked for here. The real app uses your identity provider’s own sign-in page.</span></div>' +
        '<p><button type="button" class="linkbtn" data-act="entrySkip">Skip sign-in and look around with example data</button></p>';
    }
    if (st === 'workspace') {
      return '<div><h1>Set up your workspace</h1><p class="lead">Start from an example to see how Thread works, or start empty.</p></div>' +
        '<div class="entry-choices">' +
          '<button type="button" class="choice" data-act="entryChoose" data-value="example"><span class="cicon">' + icon('folder') + '</span><span><strong>Example workspace</strong><span>Six synthetic topics with tasks, notes, reminders, Jira issues and pull requests.</span></span>' + icon('chevronRight') + '</button>' +
          '<button type="button" class="choice" data-act="entryChoose" data-value="empty"><span class="cicon">' + icon('plus') + '</span><span><strong>Start empty</strong><span>No data yet. Capture your first task or topic, and connect Jira or GitHub in Settings.</span></span>' + icon('chevronRight') + '</button>' +
        '</div>';
    }
    return '<div><h1>Welcome back</h1><p class="lead">Your ' + (s.session.mode === 'empty' ? 'workspace' : 'example workspace') + ' is on this device, signed in as ' + esc(s.session.user) + '.</p></div>' +
      '<a class="btn-key" href="' + startHref() + '" style="justify-self:start">' + icon('calendar') + '<span>Open Thread</span></a>' +
      '<div class="entry-choices">' +
        '<button type="button" class="choice" data-act="entryStep" data-value="workspace"><span class="cicon">' + icon('refresh') + '</span><span><strong>Choose a workspace again</strong><span>Replace the demo data with the example or an empty workspace.</span></span>' + icon('chevronRight') + '</button>' +
        '<button type="button" class="choice" data-act="entrySignOut"><span class="cicon">' + icon('signOut') + '</span><span><strong>Sign out (demo)</strong><span>Your local work stays on this device.</span></span>' + icon('chevronRight') + '</button>' +
      '</div>';
  }

  function directory() {
    const links = [
      ['index.html', 'user', 'Sign-in and first run'],
      ['today.html', 'calendar', 'Today'],
      ['inbox.html', 'inbox', 'Inbox'],
      ['today.html?open=capture', 'plus', 'Quick capture'],
      ['topics.html?view=list', 'layoutList', 'Topics list'],
      ['topics.html?view=board', 'layoutBoard', 'Topics board'],
      ['topic.html?id=t-qr', 'folder', 'Topic details'],
      ['notes.html?id=n-qr', 'note', 'Notes editor'],
      ['notes.html?id=n-push', 'alert', 'Note conflict'],
      ['task.html?id=k-codes', 'check', 'Task details'],
      ['today.html?open=reminders', 'bell', 'Reminders'],
      ['jira.html', 'ticket', 'Jira list'],
      ['detail.html?kind=jira&key=LARK-418', 'ticket', 'Jira detail page'],
      ['prs.html', 'pr', 'Pull requests'],
      ['detail.html?kind=pr&id=pr-1291', 'pr', 'Pull request page'],
      ['today.html?open=search', 'search', 'Search'],
      ['settings.html', 'sliders', 'Settings'],
      ['trash.html', 'trash', 'Trash'],
      ['today.html?open=timer', 'timer', 'Focus timer (post-pilot)']
    ];
    return '<nav class="directory" aria-labelledby="dirH"><h2 id="dirH">' + icon('layoutList') + 'Prototype map: open any view directly</h2><ul>' +
      links.map((l) => '<li><a href="' + l[0] + '">' + icon(l[1]) + esc(l[2]) + '</a></li>').join('') + '</ul></nav>';
  }

  function render() {
    return '<div class="entry">' + art() + '<section class="entry-panel" aria-label="Sign in">' + panel() + directory() + '</section></div>';
  }

  T.act.entrySignIn = () => {
    store.update((s) => { s.session.signedIn = true; });
    if (firstRun) { step = 'workspace'; T.render(); focusHeading(); return; }
    T.go(startHref());
  };
  T.act.entrySkip = () => {
    if (firstRun) store.reset('example');
    else store.update((s) => { s.session.signedIn = true; });
    T.go('today.html');
  };
  T.act.entryChoose = (el) => {
    store.reset(el.dataset.value);
    T.go('today.html');
  };
  T.act.entryStep = (el) => { step = el.dataset.value; T.render(); focusHeading(); };
  T.act.entrySignOut = () => {
    store.update((s) => { s.session.signedIn = false; });
    step = 'signin';
    T.toast('Signed out of the demo. Local work stays on this device.');
    focusHeading();
  };
  function focusHeading() {
    requestAnimationFrame(() => requestAnimationFrame(() => {
      const h = document.querySelector('.entry-panel h1');
      if (h) { h.setAttribute('tabindex', '-1'); h.focus(); }
    }));
  }

  T.views.signin = { title: 'Sign in', bare: true, render: render };
})();
