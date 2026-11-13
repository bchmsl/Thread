/* Thread v2 prototype: one shared demo state in localStorage.
   Every page reads and writes this single object, so an action in one view shows in all views.
   All records are synthetic sample data. Nothing here talks to a network. */
(function () {
  'use strict';
  var T = (window.Thread = window.Thread || {});
  var KEY = 'thread-v2-prototype-state';
  var VERSION = 3;

  /* Fixed demo calendar day so Today grouping is reproducible. The demo clock time is editable in Settings. */
  var DEMO_DATE = '2026-11-13';

  var COLORS = {
    lavender: '#CDB8F8',
    mint: '#A6E3CC',
    sky: '#A8D4F8',
    peach: '#F9C4A5',
    rose: '#F5BACF',
    sand: '#E6D4B2'
  };

  var CATEGORIES = {
    planned: { label: 'Planned', hint: 'Not started. Not in Active topics.' },
    active: { label: 'Active', hint: 'In progress. Shows in Today’s Active topics and the tab strip.' },
    paused: { label: 'Paused', hint: 'Waiting or blocked. Not in Active topics.' },
    done: { label: 'Done', hint: 'Finished. Can be reopened.' }
  };

  function statuses() {
    return [
      { id: 's-planned', name: 'Planned', cat: 'planned' },
      { id: 's-active', name: 'Active', cat: 'active' },
      { id: 's-review', name: 'In review', cat: 'active' },
      { id: 's-paused', name: 'Paused', cat: 'paused' },
      { id: 's-done', name: 'Done', cat: 'done' }
    ];
  }

  function baseState(mode) {
    return {
      v: VERSION,
      session: { signedIn: true, mode: mode, user: 'Demo user' },
      prefs: { topicsView: 'list', startPage: 'today', weekStart: 'mon', reminderTime: '09:00', showDone: true, sections: {} },
      sim: { clock: '11:20', jira: 'fresh', github: 'fresh', jiraConnected: mode === 'example', githubConnected: mode === 'example' },
      statuses: statuses(),
      topics: [],
      tasks: [],
      notes: [],
      reminders: [],
      jira: [],
      prs: [],
      trash: [],
      focus: { work: 25, brk: 5, run: null, history: [] }
    };
  }

  function seedExample() {
    var s = baseState('example');

    s.topics = [
      { id: 't-qr', name: 'QR checkout', color: 'lavender', statusId: 's-active',
        goal: 'Pay by scanning a merchant QR code, with clear timeout and error handling.',
        leftOff: 'Scanner hands the payload to the confirm screen. The timeout path still shows a blank state.',
        next: 'Test timeout and retry on a slow network' },
      { id: 't-wallet', name: 'Offline card wallet', color: 'mint', statusId: 's-review',
        goal: 'Show saved cards when the device has no connection.',
        leftOff: 'Cache layer merged behind a feature flag. Review is open on the UI part.',
        next: 'Answer review comments on the cache PR' },
      { id: 't-bills', name: 'Bill payment errors', color: 'sky', statusId: 's-active',
        goal: 'Explain declined bill payments in plain words.',
        leftOff: 'Collected the five most common decline reasons from support.',
        next: 'Review error copy with the content designer' },
      { id: 't-push', name: 'Push token migration', color: 'peach', statusId: 's-paused',
        goal: 'Move push token registration to the new endpoint.',
        leftOff: 'Client is ready behind a flag. Blocked on the backend contract.',
        next: 'Follow up on the API contract' },
      { id: 't-settings', name: 'Compose settings screen', color: 'rose', statusId: 's-planned',
        goal: 'Rewrite the settings screens in Compose.',
        leftOff: '',
        next: 'List the screens to migrate' },
      { id: 't-crash', name: 'Crash-free sessions to 99.8%', color: 'sand', statusId: 's-done',
        goal: 'Fix the top three crashes from the last release.',
        leftOff: 'All three fixes shipped in 4.12.',
        next: '' }
    ];

    s.tasks = [
      { id: 'k-codes', title: 'Confirm backend error codes', topicId: 't-qr', done: false, planned: '2026-11-12', deadline: '2026-11-12T18:00', inbox: false },
      { id: 'k-timeout', title: 'Test timeout and retry on a slow network', topicId: 't-qr', done: false, planned: DEMO_DATE, deadline: null, inbox: false },
      { id: 'k-camera', title: 'Add camera permission fallback', topicId: 't-qr', done: false, planned: null, deadline: null, inbox: false },
      { id: 'k-scanner', title: 'Connect scanner to the confirm screen', topicId: 't-qr', done: true, completedOn: '2026-11-11', planned: '2026-11-11', deadline: null, inbox: false },
      { id: 'k-review', title: 'Answer review comments on the cache PR', topicId: 't-wallet', done: false, planned: DEMO_DATE, deadline: DEMO_DATE + 'T17:00', inbox: false },
      { id: 'k-banner', title: 'Check offline banner copy with design', topicId: 't-wallet', done: false, planned: '2026-11-11', deadline: null, inbox: false },
      { id: 'k-flag', title: 'Remove the old cache feature flag', topicId: 't-wallet', done: true, completedOn: '2026-11-10', planned: null, deadline: null, inbox: false },
      { id: 'k-copy', title: 'Review error copy for declined bill payments', topicId: 't-bills', done: false, planned: DEMO_DATE, deadline: null, inbox: false },
      { id: 'k-reasons', title: 'Map decline reasons to messages', topicId: 't-bills', done: false, planned: null, deadline: null, inbox: false },
      { id: 'k-expense', title: 'Submit the conference expense report', topicId: null, done: false, planned: null, deadline: DEMO_DATE + 'T19:00', inbox: false },
      { id: 'k-rollout', title: 'Draft the push token rollout plan', topicId: 't-push', done: false, planned: null, deadline: null, inbox: false },
      { id: 'k-screens', title: 'List the screens to migrate', topicId: 't-settings', done: false, planned: '2026-11-16', deadline: null, inbox: false },
      { id: 'k-inbox-copy', title: 'Check QR timeout message copy', topicId: null, done: false, planned: null, deadline: null, inbox: true, captured: '2026-11-13T08:52' },
      { id: 'k-inbox-detekt', title: 'Ask about the Detekt rule for wildcard imports', topicId: null, done: false, planned: null, deadline: null, inbox: true, captured: '2026-11-12T17:40' }
    ];

    s.notes = [
      { id: 'n-qr', title: 'QR checkout decisions', topicIds: ['t-qr'], inbox: false, updated: '2026-11-13T09:05', sync: 'synced',
        html: '<h2>Retry behavior</h2><p>Keep the scanned payload after a timeout so nobody has to scan twice. Retry reuses the same <b>request ID</b> unless the backend says otherwise.</p><ul class="checklist"><li data-checked="true">Agree on a 30 second client timeout</li><li data-checked="false">Confirm whether retry needs a new request ID</li></ul><h2>Open questions</h2><ul><li>Which error codes are safe to retry?</li><li>Do we show the merchant name before confirmation?</li></ul>' },
      { id: 'n-cache', title: 'Offline cache review notes', topicIds: ['t-wallet', 't-qr'], inbox: false, updated: '2026-11-12T16:20', sync: 'synced',
        html: '<p>Shared by two topics: the QR flow reads saved cards from the same cache.</p><h2>Review feedback</h2><ul><li>Expire cached cards after <i>7 days</i> without a refresh.</li><li>Show a quiet banner, not a dialog, when data is stale.</li></ul><h2>Follow-ups</h2><ul class="checklist"><li data-checked="false">Add a test for the expired-cache path</li></ul>' },
      { id: 'n-push', title: 'Push token API questions', topicIds: ['t-push'], inbox: false, updated: '2026-11-13T11:02', sync: 'conflict',
        html: '<h2>For the backend team</h2><ul><li>Is the old endpoint removed in the same release?</li><li>Does the new endpoint accept the device ID we send today?</li><li>What does a 409 mean here?</li></ul>',
        conflict: { at: '2026-11-13T10:48', device: 'Office laptop (demo)',
          html: '<h2>For the backend team</h2><ul><li>Is the old endpoint removed in the same release?</li><li>Does the new endpoint accept the device ID we send today?</li></ul><p>Answer from the sync: rollout behind a server flag first.</p>' } },
      { id: 'n-oneonone', title: 'Weekly 1:1 prep', topicIds: [], inbox: false, updated: '2026-11-10T10:00', sync: 'synced',
        html: '<p>Things to bring up this week.</p><ul><li>Pairing on the QR flow tests</li><li>Conference talk proposal</li></ul>' },
      { id: 'n-inbox-sync', title: 'Ideas from the payments sync', topicIds: [], inbox: true, updated: '2026-11-13T08:30', sync: 'synced', captured: '2026-11-13T08:30',
        html: '<p>Merchant name before confirm? Ask design. Maybe one retry screen for both QR and bills.</p>' }
    ];

    s.reminders = [
      { id: 'r-codes', at: DEMO_DATE + 'T11:15', text: 'Ping backend about error codes', target: { kind: 'task', id: 'k-codes' }, dismissed: false },
      { id: 'r-cache', at: DEMO_DATE + 'T14:00', text: 'Re-read cache review notes before the sync', target: { kind: 'note', id: 'n-cache' }, dismissed: false },
      { id: 'r-contract', at: DEMO_DATE + 'T16:30', text: 'Follow up on the API contract', target: { kind: 'topic', id: 't-push' }, dismissed: false },
      { id: 'r-screens', at: '2026-11-16T10:00', text: 'Start the settings migration list', target: { kind: 'task', id: 'k-screens' }, dismissed: false }
    ];

    s.jira = [
      { key: 'LARK-418', title: 'QR scanner: handle denied camera permission', type: 'Story', status: 'In Progress', priority: 'High',
        assignee: 'You (demo)', reporter: 'Mira Lund', sprint: 'Sprint 46', components: ['Payments', 'Scanner'], fixVersion: '4.13',
        updated: '2026-11-13T09:42', mine: true, topicIds: ['t-qr'],
        desc: [
          { t: 'p', text: 'When the user denies camera access, the scanner shows a black preview with no way forward.' },
          { t: 'h', text: 'Acceptance criteria' },
          { t: 'ul', items: ['Explain why the camera is needed', 'Offer a button to open system settings', 'Allow manual merchant code entry'] },
          { t: 'unsupported', label: 'Status panel from a Jira app' },
          { t: 'code', text: 'ScannerError.PermissionDenied -> showPermissionRationale()' }
        ],
        comments: [
          { author: 'Mira Lund', at: '2026-11-12T15:10', text: 'Design for the rationale screen is in the shared file, page 3.' },
          { author: 'Theo Basil', at: '2026-11-13T09:40', text: 'Manual entry can reuse the existing merchant code field.' }
        ],
        moreComments: 3,
        related: [{ key: 'LARK-421', rel: 'relates to' }, { key: 'LARK-433', rel: 'blocks' }] },
      { key: 'LARK-421', title: 'QR payment: map backend error codes to messages', type: 'Task', status: 'To Do', priority: 'Medium',
        assignee: 'You (demo)', reporter: 'Yara Quint', sprint: 'Sprint 46', components: ['Payments'], fixVersion: '4.13',
        updated: '2026-11-12T17:05', mine: true, topicIds: ['t-qr'],
        desc: [
          { t: 'p', text: 'The backend returns numeric codes. Map each to a short message and a retry decision.' },
          { t: 'ul', items: ['4001: merchant not found, no retry', '5003: upstream timeout, retry once', 'Unknown: generic message, no retry'] }
        ],
        comments: [{ author: 'Yara Quint', at: '2026-11-12T17:05', text: 'Full code list is still being confirmed by the backend team.' }],
        moreComments: 0,
        related: [{ key: 'LARK-418', rel: 'relates to' }] },
      { key: 'LARK-433', title: 'QR payment: timeout and retry policy', type: 'Story', status: 'In Review', priority: 'High',
        assignee: 'You (demo)', reporter: 'Mira Lund', sprint: 'Sprint 46', components: ['Payments'], fixVersion: '4.13',
        updated: '2026-11-13T08:15', mine: true, topicIds: ['t-qr'],
        desc: [
          { t: 'p', text: 'Use a 30 second client timeout. Keep the scanned payload and offer one retry.' },
          { t: 'h', text: 'Out of scope' },
          { t: 'p', text: 'Server-side idempotency changes.' }
        ],
        comments: [],
        moreComments: 0,
        related: [{ key: 'LARK-418', rel: 'is blocked by' }] },
      { key: 'LARK-377', title: 'Offline wallet: cache saved cards on device', type: 'Story', status: 'In Progress', priority: 'Medium',
        assignee: 'You (demo)', reporter: 'Oskar Fenn', sprint: 'Sprint 46', components: ['Wallet'], fixVersion: '4.13',
        updated: '2026-11-12T11:30', mine: true, topicIds: ['t-wallet'],
        desc: [{ t: 'p', text: 'Cache card metadata (never full card numbers) and show it when offline.' }],
        comments: [{ author: 'Oskar Fenn', at: '2026-11-12T11:30', text: 'Security review approved the stored fields.' }],
        moreComments: 1,
        related: [] },
      { key: 'LARK-390', title: 'Bill payment: show the reason for a declined payment', type: 'Story', status: 'To Do', priority: 'Medium',
        assignee: 'You (demo)', reporter: 'Ines Marlow', sprint: 'Sprint 47', components: ['Bills'], fixVersion: '4.14',
        updated: '2026-11-11T14:00', mine: true, topicIds: [],
        desc: [
          { t: 'p', text: 'Support gets many tickets about declined bill payments. Show the reason and the next step.' },
          { t: 'ul', items: ['Insufficient funds', 'Provider unavailable', 'Daily limit reached'] }
        ],
        comments: [],
        moreComments: 0,
        related: [] },
      { key: 'LARK-402', title: 'Push registration: new endpoint contract', type: 'Task', status: 'Blocked', priority: 'High',
        assignee: 'Theo Basil', reporter: 'Theo Basil', sprint: 'Sprint 46', components: ['Notifications'], fixVersion: '4.14',
        updated: '2026-11-10T12:00', mine: false, topicIds: ['t-push'],
        desc: [{ t: 'p', text: 'Agree on request and response shapes for the new token registration endpoint.' }],
        comments: [{ author: 'Theo Basil', at: '2026-11-10T12:00', text: 'Waiting on the platform team review.' }],
        moreComments: 0,
        related: [] },
      { key: 'LARK-455', title: 'Settings: migrate notification preferences to Compose', type: 'Story', status: 'Backlog', priority: 'Low',
        assignee: 'Yara Quint', reporter: 'Yara Quint', sprint: 'None', components: ['Settings'], fixVersion: 'None',
        updated: '2026-11-09T09:00', mine: false, topicIds: [], remoteOnly: true,
        desc: [{ t: 'p', text: 'Port the notification preference screen. Keep the same toggles.' }],
        comments: [],
        moreComments: 0,
        related: [] },
      { key: 'LARK-299', title: 'Receipts: share a payment receipt as an image', type: 'Story', status: 'Done', priority: 'Low',
        assignee: 'Mira Lund', reporter: 'Mira Lund', sprint: 'Sprint 44', components: ['Payments'], fixVersion: '4.12',
        updated: '2026-10-28T16:00', mine: false, topicIds: [], remoteOnly: true,
        desc: [{ t: 'p', text: 'Render the receipt view to a bitmap and open the share sheet.' }],
        comments: [],
        moreComments: 0,
        related: [] }
    ];

    s.prs = [
      { id: 'pr-1287', number: 1287, repo: 'sample-labs/wallet-android', title: 'Handle QR payment timeout with one retry', author: 'You (demo)',
        draft: false, ci: 'passing', review: 'changes', mine: true, requested: false, branch: 'feature/qr-timeout', base: 'main',
        updated: '2026-11-13T09:10', topicIds: ['t-qr'],
        checks: [{ name: 'Unit tests', state: 'passing' }, { name: 'Lint', state: 'passing' }, { name: 'Build debug APK', state: 'passing' }],
        reviewers: [{ name: 'Mira Lund', state: 'changes' }, { name: 'Theo Basil', state: 'pending' }],
        body: [{ t: 'p', text: 'Adds a 30 second timeout to the QR payment call and one retry that reuses the scanned payload.' }, { t: 'ul', items: ['Timeout handled in the repository layer', 'Retry state kept in the view model'] }],
        comments: [{ author: 'Mira Lund', at: '2026-11-13T08:55', text: 'Please move the retry counter out of the composable.' }] },
      { id: 'pr-1291', number: 1291, repo: 'sample-labs/wallet-android', title: 'Cache saved cards for the offline wallet', author: 'You (demo)',
        draft: false, ci: 'running', review: 'approved', mine: true, requested: false, branch: 'feature/wallet-cache', base: 'main',
        updated: '2026-11-13T10:30', topicIds: ['t-wallet'],
        checks: [{ name: 'Unit tests', state: 'passing' }, { name: 'Lint', state: 'passing' }, { name: 'Build debug APK', state: 'running' }],
        reviewers: [{ name: 'Oskar Fenn', state: 'approved' }],
        body: [{ t: 'p', text: 'Stores card metadata in the local database and reads it when the network call fails.' }],
        comments: [] },
      { id: 'pr-1302', number: 1302, repo: 'sample-labs/wallet-android', title: 'Map QR error codes to user messages', author: 'You (demo)',
        draft: true, ci: 'failing', review: 'none', mine: true, requested: false, branch: 'feature/qr-error-codes', base: 'main',
        updated: '2026-11-12T18:20', topicIds: [],
        checks: [{ name: 'Unit tests', state: 'failing' }, { name: 'Lint', state: 'passing' }, { name: 'Build debug APK', state: 'passing' }],
        reviewers: [],
        body: [{ t: 'p', text: 'Work in progress. The code list is not final yet.' }],
        comments: [] },
      { id: 'pr-1294', number: 1294, repo: 'sample-labs/wallet-android', title: 'Migrate the bill receipt screen to Compose', author: 'Oskar Fenn',
        draft: false, ci: 'passing', review: 'pending', mine: false, requested: true, branch: 'feature/receipt-compose', base: 'main',
        updated: '2026-11-13T07:45', topicIds: [],
        checks: [{ name: 'Unit tests', state: 'passing' }, { name: 'Lint', state: 'passing' }, { name: 'Screenshot tests', state: 'passing' }],
        reviewers: [{ name: 'You (demo)', state: 'pending' }],
        body: [{ t: 'p', text: 'Replaces the XML receipt layout. Screenshot tests updated.' }],
        comments: [{ author: 'Oskar Fenn', at: '2026-11-13T07:45', text: 'The share button moved to the top bar, as agreed.' }] },
      { id: 'pr-88', number: 88, repo: 'sample-labs/payments-sdk', title: 'Add a timeout parameter to the QR client', author: 'Ines Marlow',
        draft: false, ci: 'failing', review: 'pending', mine: false, requested: true, branch: 'qr-timeout-param', base: 'main',
        updated: '2026-11-12T19:05', topicIds: ['t-qr'],
        checks: [{ name: 'Unit tests', state: 'failing' }, { name: 'Binary compatibility', state: 'passing' }],
        reviewers: [{ name: 'You (demo)', state: 'pending' }],
        body: [{ t: 'p', text: 'Lets the app pass its own timeout instead of the 60 second default.' }],
        comments: [] }
    ];

    s.trash = [
      { id: 'tr-spike', kind: 'note', deletedAt: '2026-11-11T16:05', context: 'From QR checkout',
        item: { id: 'n-spike', title: 'Scanner spike: library comparison', topicIds: ['t-qr'], inbox: false, updated: '2026-11-04T12:00', sync: 'synced',
          html: '<p>Compared two scanning libraries. Picked the one with offline models.</p>' } },
      { id: 'tr-release', kind: 'task', deletedAt: '2026-11-12T09:30', context: 'From Offline card wallet',
        item: { id: 'k-release', title: 'Draft release notes for 4.12', topicId: 't-wallet', done: false, planned: null, deadline: null, inbox: false } }
    ];

    s.focus.history = [
      { at: DEMO_DATE + 'T09:10', minutes: 25, label: 'QR checkout', completed: true },
      { at: DEMO_DATE + 'T09:40', minutes: 25, label: 'Test timeout and retry on a slow network', completed: true },
      { at: '2026-11-12T15:00', minutes: 12, label: 'No task', completed: false }
    ];
    return s;
  }

  function seedEmpty() {
    return baseState('empty');
  }

  /* ---------- persistence ---------- */
  var state = null;
  var listeners = [];
  var storageOk = true;

  function load() {
    try {
      var raw = window.localStorage.getItem(KEY);
      if (!raw) return null;
      var parsed = JSON.parse(raw);
      if (!parsed || parsed.v !== VERSION) return null;
      return parsed;
    } catch (e) {
      storageOk = false;
      return null;
    }
  }

  function persist() {
    try {
      window.localStorage.setItem(KEY, JSON.stringify(state));
      storageOk = true;
    } catch (e) {
      storageOk = false;
    }
  }

  function emit(info) {
    for (var i = 0; i < listeners.length; i++) {
      try { listeners[i](info || {}); } catch (e) { if (window.console) console.error(e); }
    }
  }

  var store = {
    get: function () {
      if (!state) {
        state = load();
        if (!state) { state = seedExample(); persist(); }
      }
      return state;
    },
    exists: function () { return !!load(); },
    storageOk: function () { return storageOk; },
    update: function (fn, info) {
      var s = store.get();
      fn(s);
      persist();
      emit(info);
    },
    replace: function (next, info) {
      state = next;
      persist();
      emit(info);
    },
    reset: function (mode) {
      store.replace(mode === 'empty' ? seedEmpty() : seedExample(), { reset: true });
    },
    subscribe: function (fn) { listeners.push(fn); },
    /* another tab changed the state */
    reloadFromStorage: function () {
      var fresh = load();
      if (fresh) { state = fresh; emit({ external: true }); }
    }
  };

  window.addEventListener('storage', function (e) {
    if (e.key === KEY) store.reloadFromStorage();
  });

  /* ---------- time helpers (workspace-local wall time) ---------- */
  function parseLocal(str) {
    if (!str) return null;
    var m = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/.exec(str);
    if (!m) return null;
    return new Date(+m[1], +m[2] - 1, +m[3], m[4] ? +m[4] : 0, m[5] ? +m[5] : 0);
  }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function toLocalDate(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function toLocalStamp(d) { return toLocalDate(d) + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes()); }
  function now() { return parseLocal(DEMO_DATE + 'T' + (store.get().sim.clock || '11:20')); }

  var DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  var MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

  function fmtTime(str) {
    var d = typeof str === 'string' ? parseLocal(str) : str;
    return d ? pad(d.getHours()) + ':' + pad(d.getMinutes()) : '';
  }
  /* Short relative day: Today, Tomorrow, Yesterday, else "Wed 11 Nov". */
  function fmtDay(str) {
    var d = typeof str === 'string' ? parseLocal(str) : str;
    if (!d) return '';
    var today = parseLocal(DEMO_DATE);
    var diff = Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()) - today) / 86400000);
    if (diff === 0) return 'Today';
    if (diff === 1) return 'Tomorrow';
    if (diff === -1) return 'Yesterday';
    return DAYS[d.getDay()].slice(0, 3) + ' ' + d.getDate() + ' ' + MONTHS[d.getMonth()].slice(0, 3);
  }
  function fmtStamp(str) {
    if (!str) return '';
    var hasTime = str.length > 10;
    return fmtDay(str) + (hasTime ? ' ' + fmtTime(str) : '');
  }

  /* ---------- selectors (domain rules live here, not in views) ---------- */
  function statusById(id) {
    var list = store.get().statuses;
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return list[0];
  }
  function topicById(id) {
    if (!id) return null;
    var list = store.get().topics;
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }
  function taskById(id) {
    var list = store.get().tasks;
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }
  function noteById(id) {
    var list = store.get().notes;
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }
  function jiraByKey(key) {
    var list = store.get().jira;
    for (var i = 0; i < list.length; i++) if (list[i].key === key) return list[i];
    return null;
  }
  function prById(id) {
    var list = store.get().prs;
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }
  function topicCat(topic) { return topic ? statusById(topic.statusId).cat : null; }
  function topicColor(topic) { return topic ? (COLORS[topic.color] || COLORS.lavender) : null; }

  function activeTopics() {
    return store.get().topics.filter(function (t) { return topicCat(t) === 'active'; });
  }
  function topicsInOrder(includeDone) {
    var s = store.get();
    var order = {};
    s.statuses.forEach(function (st, i) { order[st.id] = i; });
    return s.topics
      .filter(function (t) { return includeDone || topicCat(t) !== 'done'; })
      .slice()
      .sort(function (a, b) { return (order[a.statusId] - order[b.statusId]) || a.name.localeCompare(b.name); });
  }
  function topicTasks(topicId) {
    return store.get().tasks.filter(function (k) { return k.topicId === topicId; });
  }
  function topicProgress(topicId) {
    var list = topicTasks(topicId);
    var done = list.filter(function (k) { return k.done; }).length;
    return { done: done, total: list.length };
  }
  function topicNotes(topicId) {
    return store.get().notes.filter(function (n) { return n.topicIds.indexOf(topicId) !== -1; });
  }
  function topicReminders(topicId) {
    return store.get().reminders.filter(function (r) { return reminderTopicId(r) === topicId; });
  }
  function reminderTopicId(r) {
    if (r.target.kind === 'topic') return r.target.id;
    if (r.target.kind === 'task') { var k = taskById(r.target.id); return k ? k.topicId : null; }
    return null;
  }
  function reminderTargetLabel(r) {
    if (r.target.kind === 'task') { var k = taskById(r.target.id); return k ? k.title : 'Deleted task'; }
    if (r.target.kind === 'note') { var n = noteById(r.target.id); return n ? n.title : 'Deleted note'; }
    var t = topicById(r.target.id); return t ? t.name : 'Deleted topic';
  }
  function taskReminder(taskId) {
    var list = store.get().reminders;
    for (var i = 0; i < list.length; i++) {
      if (list[i].target.kind === 'task' && list[i].target.id === taskId && !list[i].dismissed) return list[i];
    }
    return null;
  }

  /* Today grouping. Each task lands in exactly one group:
     1. Overdue: deadline already passed.
     2. Planned today.
     3. Due today: deadline later today, not planned today (proposal).
     4. Earlier planned: planned day already passed, deadline not passed.
     Done tasks stay in their group (struck through) only when completed today. */
  function todayGroups() {
    var t0 = now();
    var groups = { overdue: [], today: [], due: [], earlier: [] };
    store.get().tasks.forEach(function (k) {
      if (k.done && k.completedOn !== DEMO_DATE) return;
      var g = taskTodayGroup(k, t0);
      if (g) groups[g].push(k);
    });
    function byDone(a, b) { return (a.done ? 1 : 0) - (b.done ? 1 : 0); }
    groups.overdue.sort(function (a, b) { return byDone(a, b) || (a.deadline < b.deadline ? -1 : 1); });
    groups.today.sort(byDone);
    groups.due.sort(function (a, b) { return byDone(a, b) || (a.deadline < b.deadline ? -1 : 1); });
    groups.earlier.sort(function (a, b) { return byDone(a, b) || (a.planned < b.planned ? -1 : 1); });
    return groups;
  }
  function taskTodayGroup(k, t0) {
    t0 = t0 || now();
    var dl = k.deadline ? parseLocal(k.deadline) : null;
    if (dl && k.deadline.length <= 10) dl = new Date(dl.getFullYear(), dl.getMonth(), dl.getDate(), 23, 59);
    if (dl && dl < t0) return 'overdue';
    if (k.planned === DEMO_DATE) return 'today';
    if (dl && toLocalDate(dl) === DEMO_DATE) return 'due';
    if (k.planned && k.planned < DEMO_DATE) return 'earlier';
    return null;
  }
  var GROUP_LABEL = { overdue: 'Overdue', today: 'Planned today', due: 'Due today', earlier: 'Earlier planned' };

  function todayReminders() {
    var t0 = now();
    return store.get().reminders
      .filter(function (r) { return r.at.slice(0, 10) === DEMO_DATE; })
      .sort(function (a, b) { return a.at < b.at ? -1 : 1; })
      .map(function (r) {
        var state = r.dismissed ? 'dismissed' : (parseLocal(r.at) <= t0 ? 'due' : 'upcoming');
        return { r: r, state: state };
      });
  }
  function dueReminders() {
    return todayReminders().filter(function (x) { return x.state === 'due'; });
  }

  function inboxItems() {
    var s = store.get();
    var items = [];
    s.tasks.forEach(function (k) { if (k.inbox) items.push({ kind: 'task', id: k.id, title: k.title, captured: k.captured || '', obj: k }); });
    s.notes.forEach(function (n) { if (n.inbox) items.push({ kind: 'note', id: n.id, title: n.title, captured: n.captured || n.updated, obj: n }); });
    items.sort(function (a, b) { return a.captured < b.captured ? 1 : -1; });
    return items;
  }

  function uid(prefix) { return prefix + '-' + Math.random().toString(36).slice(2, 8); }

  function noteText(html) {
    var div = document.createElement('div');
    div.innerHTML = sanitize(html || '');
    /* keep words from separate blocks apart in excerpts and search */
    Array.prototype.forEach.call(div.querySelectorAll('p,h2,h3,li,div,br'), function (e) { e.insertAdjacentText('afterend', ' '); });
    return (div.textContent || '').replace(/\s+/g, ' ').trim();
  }

  /* Allowlist sanitizer for note HTML before it enters the editor or a preview. */
  var ALLOWED = { H2: 1, H3: 1, P: 1, B: 1, STRONG: 1, I: 1, EM: 1, U: 1, UL: 1, OL: 1, LI: 1, A: 1, BR: 1, DIV: 1, SPAN: 1 };
  function sanitize(html) {
    var doc = new DOMParser().parseFromString('<div>' + (html || '') + '</div>', 'text/html');
    var root = doc.body.firstChild;
    (function walk(node) {
      var kids = Array.prototype.slice.call(node.childNodes);
      kids.forEach(function (child) {
        if (child.nodeType === 3) return;
        if (child.nodeType !== 1 || !ALLOWED[child.tagName]) {
          if (child.nodeType === 1 && !/^(SCRIPT|STYLE|IFRAME|OBJECT|EMBED|TEMPLATE)$/.test(child.tagName)) {
            while (child.firstChild) node.insertBefore(child.firstChild, child);
          }
          node.removeChild(child);
          return;
        }
        Array.prototype.slice.call(child.attributes).forEach(function (attr) {
          var name = attr.name.toLowerCase();
          var keep = (child.tagName === 'A' && name === 'href' && /^https?:\/\//i.test(attr.value)) ||
            (child.tagName === 'UL' && name === 'class' && attr.value === 'checklist') ||
            (child.tagName === 'LI' && name === 'data-checked');
          if (!keep) child.removeAttribute(attr.name);
        });
        if (child.tagName === 'A') { child.setAttribute('rel', 'noopener noreferrer'); child.setAttribute('target', '_blank'); }
        walk(child);
      });
    })(root);
    return root.innerHTML;
  }

  T.store = store;
  T.state = store.get;
  T.time = { DEMO_DATE: DEMO_DATE, now: now, parse: parseLocal, fmtTime: fmtTime, fmtDay: fmtDay, fmtStamp: fmtStamp, toDate: toLocalDate, toStamp: toLocalStamp, pad: pad, DAYS: DAYS, MONTHS: MONTHS };
  T.COLORS = COLORS;
  T.CATEGORIES = CATEGORIES;
  T.sel = {
    statusById: statusById, topicById: topicById, taskById: taskById, noteById: noteById, jiraByKey: jiraByKey, prById: prById,
    topicCat: topicCat, topicColor: topicColor, activeTopics: activeTopics, topicsInOrder: topicsInOrder,
    topicTasks: topicTasks, topicProgress: topicProgress, topicNotes: topicNotes, topicReminders: topicReminders,
    reminderTargetLabel: reminderTargetLabel, taskReminder: taskReminder,
    todayGroups: todayGroups, taskTodayGroup: taskTodayGroup, GROUP_LABEL: GROUP_LABEL,
    todayReminders: todayReminders, dueReminders: dueReminders, inboxItems: inboxItems
  };
  T.util = { uid: uid, noteText: noteText, sanitize: sanitize };
})();
