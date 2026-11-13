/* Sign-in / first run, plus direct links to every screen for review. No status line on this page. */
(function () {
  const T = window.T;
  const html = T.html;
  const I = T.I;

  const BRAND = '<svg viewBox="-12 -12 124 124" width="34" height="34" aria-hidden="true"><polygon points="50,4 89.8,27 89.8,73 50,96 10.2,73 10.2,27" fill="none" stroke="currentColor" stroke-width="10" stroke-linejoin="round"/><polygon points="50,30 67,40 67,60 50,70 33,60 33,40" fill="currentColor"/></svg>';
  const JUMP = [
    ['signin', 'Sign-in and first run', 'index.html', 'This page'],
    ['today', 'Today', 'today.html', ''],
    ['plus', 'Quick capture', 'today.html?capture=1', 'Press C'],
    ['inbox', 'Inbox', 'inbox.html', ''],
    ['topics', 'Topics list', 'topics.html?view=list', ''],
    ['topics', 'Topics board', 'topics.html?view=board', ''],
    ['topics', 'Topic details', 'topic.html?id=t-freeze', ''],
    ['notes', 'Notes', 'notes.html', ''],
    ['notes', 'Note editor', 'notes.html?id=n1', ''],
    ['compare', 'Note conflict', 'notes.html?id=n1&demo=conflict', ''],
    ['task', 'Task details and reminders', 'today.html?task=k2', ''],
    ['jira', 'Jira', 'jira.html', ''],
    ['jira', 'Jira search', 'jira.html?tab=search&q=freeze', ''],
    ['jira', 'Jira ticket as a page', 'item.html?kind=jira&id=KITE-412&from=topic:t-freeze', ''],
    ['pr', 'Pull requests', 'prs.html', ''],
    ['pr', 'Review requested', 'prs.html?tab=review', ''],
    ['search', 'Global search', 'today.html?search=1', '⌘K or Ctrl+K'],
    ['settings', 'Settings', 'settings.html', ''],
    ['settings', 'Status editor', 'settings.html#statuses', ''],
    ['trash', 'Trash', 'trash.html', ''],
    ['timer', 'Focus timer', 'today.html?focus=1', 'Post-pilot concept']
  ];

  T.definePage({
    render(main) {
      const s = T.state.session;
      main.innerHTML = html`<div class="signin">
        <section aria-labelledby="si-h">
          <p class="brandbig">${T.raw(BRAND)}Thread <span class="tag">Demo</span></p>
          <h1 class="signin__h" id="si-h">${s ? 'Welcome back' : 'Sign in to your workspace'}</h1>
          <p class="signin__p">Topics, tasks, notes, Jira tickets and pull requests in one place. This prototype runs on synthetic data in this browser.</p>
          ${s ? html`<div class="signin__state">${I(s.mode === 'skip' ? 'device' : 'user', 18)}
              <span>${s.mode === 'skip' ? 'Local only. Nothing syncs.' : 'Signed in as Demo user.'} Workspace: ${T.state.workspace === 'empty' ? 'empty' : 'example data'}.</span></div>
            <div class="signin__acts"><a class="btn btn--primary" href="today.html">Go to Today</a>
              <button class="btn btn--text" type="button" data-act="index-signout">Sign out</button></div>`
          : html`<form data-submit="signin" autocomplete="off">
              <fieldset class="ws"><legend>Workspace</legend>
                <label class="wsopt"><input type="radio" name="ws" value="example" ${T.state.workspace !== 'empty' ? 'checked' : ''}>
                  <span class="wsopt__txt"><b>Example workspace</b><small>Six Topics with synthetic tasks, notes, tickets and pull requests</small></span></label>
                <label class="wsopt"><input type="radio" name="ws" value="empty" ${T.state.workspace === 'empty' ? 'checked' : ''}>
                  <span class="wsopt__txt"><b>Empty workspace</b><small>Start from nothing and see the first-run states</small></span></label>
              </fieldset>
              <div class="signin__acts"><button class="btn btn--primary" type="submit">Continue as demo user</button>
                <button class="btn btn--text" type="button" data-act="signin-skip">Skip for now (local only)</button></div>
            </form>`}
          <p class="safe">${I('lock', 16)} The demo never asks for a password or token. Nothing leaves this browser.</p>
        </section>
        <section aria-labelledby="jump-h">
          <h2 class="jump__h" id="jump-h">Jump to a screen</h2>
          <p class="jump__p">Direct links for review. Every screen shares one demo state saved in this browser.</p>
          <ul class="jump__grid">${JUMP.map(([icon, label, href, small]) => html`<li><a href="${href}">${I(icon, 18)}<span>${label}</span>${small ? html`<small>${small}</small>` : ''}</a></li>`)}</ul>
          <div class="jump__foot"><button class="btn btn--line btn--sm" type="button" data-act="index-reset">${I('refresh', 15)} Reset demo data</button></div>
        </section>
      </div>`.s;
    }
  });

  function pickedWorkspace() {
    const c = document.querySelector('input[name="ws"]:checked');
    return c ? c.value : 'example';
  }
  T.on('signin', () => { T.act.signIn('demo', pickedWorkspace()); location.href = 'today.html'; });
  T.on('signin-skip', () => { T.act.signIn('skip', pickedWorkspace()); location.href = 'today.html'; });
  T.on('index-signout', () => { T.act.signOut(); });
  T.on('index-reset', async () => {
    const ok = await T.confirm({ title: 'Reset demo data?', body: 'This restores the example workspace in this browser.', confirmLabel: 'Reset data', danger: true });
    if (ok) T.reset('example');
  });
})();
