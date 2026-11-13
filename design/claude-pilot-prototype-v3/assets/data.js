/* Thread v3 demo seed. ALL DATA IS SYNTHETIC: invented people, KITE-* ticket keys,
   acme-demo/* repositories and PR numbers. Nothing here refers to a real Jira or GitHub record.
   Demo clock is fixed: Friday 13 November 2026, 13:05. */
(function () {
  const T = (window.T = window.T || {});
  T.SEED_VERSION = 3;

  const statuses = [
    { id: 's-planned', name: 'Planned', category: 'planned' },
    { id: 's-active', name: 'Active', category: 'active' },
    { id: 's-review', name: 'In review', category: 'active' },
    { id: 's-paused', name: 'Paused', category: 'paused' },
    { id: 's-done', name: 'Done', category: 'done' }
  ];

  function exampleWorkspace() {
    return {
      topics: [
        { id: 't-freeze', name: 'Card freeze flow', color: 'lavender', statusId: 's-active',
          goal: 'Let people freeze and unfreeze a card from card details, with a clear result every time.',
          leftOff: 'Freeze works on staging. After unfreeze, the card list still shows the old badge until a refresh.',
          nextStep: 'Make the badge observe card state from the repository.' },
        { id: 't-receipts', name: 'Offline receipts cache', color: 'mint', statusId: 's-review',
          goal: 'Show recent receipts without a connection.',
          leftOff: 'PR #3094 has one approval. Teo asked how expiry works after a time-zone change.',
          nextStep: 'Answer the expiry comments on #3094.' },
        { id: 't-bio', name: 'Biometric sign-in', color: 'sky', statusId: 's-paused',
          goal: 'Offer fingerprint sign-in with a clear PIN fallback.',
          leftOff: 'The prompt works. The fallback copy is waiting for a security review.',
          nextStep: 'Wait for security sign-off, then update the copy.' },
        { id: 't-compose', name: 'Settings screen to Compose', color: 'peach', statusId: 's-planned',
          goal: 'Move the settings screens off XML layouts.',
          leftOff: '',
          nextStep: 'List the screens that still use XML.' },
        { id: 't-build', name: 'Faster debug builds', color: 'butter', statusId: 's-active',
          goal: 'Get a clean debug build under 3 minutes.',
          leftOff: 'Baseline measured: 4 min 12 s for a clean debug build.',
          nextStep: 'Try configuration cache on the app module.' },
        { id: 't-crash', name: 'Cold start crash', color: 'lavender', statusId: 's-done',
          goal: 'Stop the crash when the saved token has expired.',
          leftOff: 'Fix shipped in demo release 3.18.',
          nextStep: '' }
      ],
      tasks: [
        { id: 'k1', title: 'Make the badge observe card state', topicId: 't-freeze', done: false, plannedDay: '2026-11-13', deadline: null, inbox: false, createdAt: '2026-11-12T10:00' },
        { id: 'k2', title: 'Ask backend which errors mean "retry later"', topicId: 't-freeze', done: false, plannedDay: '2026-11-12', deadline: '2026-11-12T18:00', inbox: false, createdAt: '2026-11-11T09:20' },
        { id: 'k3', title: 'Test freeze on a slow network', topicId: 't-freeze', done: false, plannedDay: '2026-11-11', deadline: null, inbox: false, createdAt: '2026-11-10T14:00' },
        { id: 'k4', title: 'Unit tests for receipt expiry', topicId: 't-receipts', done: false, plannedDay: '2026-11-13', deadline: null, inbox: false, createdAt: '2026-11-12T11:30' },
        { id: 'k5', title: 'Answer expiry comments on #3094', topicId: 't-receipts', done: false, plannedDay: '2026-11-13', deadline: '2026-11-13T17:00', inbox: false, createdAt: '2026-11-13T08:40' },
        { id: 'k6', title: 'Share the build-time baseline with the team', topicId: 't-build', done: false, plannedDay: '2026-11-14', deadline: '2026-11-13T12:00', inbox: false, createdAt: '2026-11-11T16:30' },
        { id: 'k7', title: 'Connect the freeze toggle to the API', topicId: 't-freeze', done: true, completedAt: '2026-11-12T17:40', plannedDay: '2026-11-12', deadline: null, inbox: false, createdAt: '2026-11-10T09:00' },
        { id: 'k8', title: 'Draft fallback PIN copy', topicId: 't-bio', done: true, completedAt: '2026-11-10T15:10', plannedDay: null, deadline: null, inbox: false, createdAt: '2026-11-09T10:00' },
        { id: 'k9', title: 'List XML settings screens', topicId: 't-compose', done: false, plannedDay: null, deadline: null, inbox: false, createdAt: '2026-11-08T12:00' },
        { id: 'k10', title: 'Book a desk for Monday', topicId: null, done: false, plannedDay: '2026-11-13', deadline: null, inbox: false, createdAt: '2026-11-12T19:00' },
        { id: 'k11', title: 'Try configuration cache on the app module', topicId: 't-build', done: false, plannedDay: '2026-11-14', deadline: null, inbox: false, createdAt: '2026-11-12T09:10' },
        { id: 'k14', title: 'Fix crash when saved token expired', topicId: 't-crash', done: true, completedAt: '2026-11-06T13:00', plannedDay: null, deadline: null, inbox: false, createdAt: '2026-11-04T09:00' },
        { id: 'k12', title: 'Check if receipts need a size limit', topicId: null, done: false, plannedDay: null, deadline: null, inbox: true, createdAt: '2026-11-13T09:30' },
        { id: 'k13', title: 'Rename CardState to CardStatus?', topicId: null, done: false, plannedDay: null, deadline: null, inbox: true, createdAt: '2026-11-12T16:45' }
      ],
      notes: [
        { id: 'n1', title: 'Freeze flow decisions', topicIds: ['t-freeze'], inbox: false, updatedAt: '2026-11-13T11:40',
          body: '<h2>What we decided</h2><p>Freeze is a card state, not a screen flag. Card details reads it from the card repository, so the list and details cannot disagree.</p><ul><li>Freeze and unfreeze use one endpoint with a <b>target state</b>.</li><li>Show the result in place. No extra success screen.</li></ul><h2>Open questions</h2><ul class="checklist"><li data-checked="true">Does unfreeze need a second confirmation? No.</li><li data-checked="false">Which error codes mean <i>try again later</i>?</li></ul><p>Backend notes are in the KITE-288 comments.</p>' },
        { id: 'n2', title: 'Card state model', topicIds: ['t-freeze', 't-receipts'], inbox: false, updatedAt: '2026-11-12T17:10',
          body: '<h2>States</h2><ol><li>Active</li><li>Frozen</li><li>Blocked by the bank</li></ol><p>The receipts cache stores the card state at payment time, so a frozen card still shows its past receipts.</p><p><i>This one note is linked to two Topics. Edit it once, and both Topics show the change.</i></p>' },
        { id: 'n3', title: 'Build speed experiments', topicIds: ['t-build'], inbox: false, updatedAt: '2026-11-11T16:20',
          body: '<h2>Baseline</h2><p>Clean debug build: <b>4 min 12 s</b> on the demo laptop.</p><h2>Try next</h2><ul class="checklist"><li data-checked="false">Configuration cache on the app module</li><li data-checked="false">Turn off unused build variants</li></ul>' },
        { id: 'n4', title: '1:1 prep', topicIds: [], inbox: false, updatedAt: '2026-11-10T09:15',
          body: '<p>Things to raise:</p><ul><li>Time for the Compose migration next sprint</li><li>Review load this week</li></ul>' },
        { id: 'n5', title: 'Ideas from the payments sync', topicIds: [], inbox: true, updatedAt: '2026-11-13T09:50',
          body: '<p>Maybe show the last three receipts offline, even without a full cache.</p>' }
      ],
      reminders: [
        { id: 'r1', at: '2026-11-13T10:00', text: 'Mention the freeze blocker at stand-up', target: { kind: 'topic', id: 't-freeze' } },
        { id: 'r2', at: '2026-11-13T14:30', text: 'Ping backend about error codes', target: { kind: 'task', id: 'k2' } },
        { id: 'r3', at: '2026-11-13T16:30', text: 'Check the security reply on PIN copy', target: { kind: 'topic', id: 't-bio' } },
        { id: 'r4', at: '2026-11-14T09:30', text: 'Re-read the card state model', target: { kind: 'note', id: 'n2' } }
      ],
      links: {
        'jira:KITE-412': ['t-freeze'],
        'jira:KITE-415': ['t-freeze'],
        'jira:KITE-288': ['t-freeze', 't-receipts'],
        'jira:KITE-398': ['t-receipts'],
        'jira:KITE-377': ['t-bio'],
        'jira:KITE-420': ['t-build'],
        'jira:KITE-150': ['t-crash'],
        'pr:wallet-3101': ['t-freeze'],
        'pr:wallet-3094': ['t-receipts'],
        'pr:wallet-3112': ['t-build']
      },
      trash: [
        { id: 'tr1', kind: 'note', deletedAt: '2026-11-12T18:20', context: { topicIds: ['t-freeze'] },
          item: { id: 'n6', title: 'Old freeze sketch', topicIds: ['t-freeze'], inbox: false, updatedAt: '2026-11-09T10:00',
            body: '<p>First idea: a separate freeze screen. Replaced by the in-place toggle.</p>' } },
        { id: 'tr2', kind: 'task', deletedAt: '2026-11-11T10:05', context: { topicIds: ['t-freeze'] },
          item: { id: 'k20', title: 'Test freeze on the emulator', topicId: 't-freeze', done: false, plannedDay: null, deadline: null, inbox: false, createdAt: '2026-11-08T10:00' } },
        { id: 'tr3', kind: 'reminder', deletedAt: '2026-11-10T17:45', context: { topicIds: ['t-build'] },
          item: { id: 'r9', at: '2026-11-17T10:00', text: 'Read the build tool release notes', target: { kind: 'topic', id: 't-build' } } }
      ],
      connections: { jira: true, github: true }
    };
  }

  function emptyWorkspace() {
    return { topics: [], tasks: [], notes: [], reminders: [], links: {}, trash: [], connections: { jira: false, github: false } };
  }

  /* External records: read-only, device cache only. */
  T.people = {
    me: 'Demo user (you)',
    mira: 'Mira Okafor',
    teo: 'Teo Varga',
    lena: 'Lena Brandt',
    sam: 'Sam Ruiz'
  };

  T.jiraIssues = [
    { key: 'KITE-412', title: 'Card freeze: toggle in card details', type: 'Story', status: 'In Progress', priority: 'High',
      assignee: 'me', reporter: 'sam', sprint: 'Sprint 46', labels: ['cards', 'android'], updated: '2026-11-13T09:58', assigned: true, cached: true,
      description: [
        { t: 'p', x: 'Add a freeze toggle to card details. The card state must update in the list and in details without a manual refresh.' },
        { t: 'h', x: 'Acceptance' },
        { t: 'ul', items: ['The toggle shows the current state', 'A failure keeps the previous state and shows the reason', 'Works with TalkBack'] },
        { t: 'code', x: 'PUT /cards/{id}/state  { "target": "FROZEN" }' },
        { t: 'unsupported', x: 'Embedded design board' }
      ],
      comments: [
        { a: 'sam', at: '2026-11-12T15:20', x: 'The confirmation sheet design is in KITE-301.' },
        { a: 'mira', at: '2026-11-13T09:41', x: 'The endpoint returns 409 while a freeze is pending. Treat it as "try again later".' }
      ],
      olderComments: 3,
      related: [{ key: 'KITE-415', rel: 'Blocks' }, { key: 'KITE-301', rel: 'Relates to' }, { key: 'KITE-288', rel: 'Depends on' }] },
    { key: 'KITE-415', title: 'Card freeze: error states', type: 'Story', status: 'To Do', priority: 'Medium',
      assignee: 'me', reporter: 'lena', sprint: 'Sprint 46', labels: ['cards'], updated: '2026-11-12T14:05', assigned: true, cached: true,
      description: [{ t: 'p', x: 'Cover timeout, 409 pending and 403 blocked-by-bank. Each state needs copy and a retry rule.' }],
      comments: [{ a: 'lena', at: '2026-11-12T14:05', x: 'I will test these on the slow-network profile.' }], olderComments: 0,
      related: [{ key: 'KITE-412', rel: 'Is blocked by' }] },
    { key: 'KITE-398', title: 'Receipts: offline cache with expiry', type: 'Story', status: 'In Review', priority: 'Medium',
      assignee: 'me', reporter: 'sam', sprint: 'Sprint 46', labels: ['receipts', 'offline'], updated: '2026-11-13T08:30', assigned: true, cached: true,
      description: [{ t: 'p', x: 'Keep the last 30 days of receipts on the device. Expire entries by payment date, not download date.' }],
      comments: [{ a: 'teo', at: '2026-11-13T08:30', x: 'What happens after a time-zone change?' }], olderComments: 1,
      related: [{ key: 'KITE-288', rel: 'Relates to' }] },
    { key: 'KITE-377', title: 'Biometric sign-in: fallback PIN copy', type: 'Task', status: 'Blocked', priority: 'Low',
      assignee: 'me', reporter: 'lena', sprint: 'Sprint 46', labels: ['auth'], updated: '2026-11-10T15:12', assigned: true, cached: true,
      description: [{ t: 'p', x: 'Waiting for security review of the fallback wording.' }], comments: [], olderComments: 0, related: [] },
    { key: 'KITE-420', title: 'Gradle: try configuration cache', type: 'Task', status: 'To Do', priority: 'Low',
      assignee: 'me', reporter: 'teo', sprint: 'Backlog', labels: ['build'], updated: '2026-11-11T16:40', assigned: true, cached: true,
      description: [{ t: 'p', x: 'Measure clean and incremental debug builds with configuration cache on the app module.' }], comments: [], olderComments: 0, related: [] },
    { key: 'KITE-288', title: 'Card state API v2 contract', type: 'Story', status: 'In Progress', priority: 'High',
      assignee: 'mira', reporter: 'mira', sprint: 'Sprint 46', labels: ['api'], updated: '2026-11-13T09:40', assigned: false, cached: true,
      description: [{ t: 'p', x: 'One state endpoint for freeze, unfreeze and bank blocks. Error codes are listed below.' }, { t: 'ul', items: ['409: change pending, retry later', '403: blocked by the bank', '422: invalid target state'] }],
      comments: [{ a: 'mira', at: '2026-11-13T09:40', x: 'Contract is final unless QA finds gaps.' }], olderComments: 6, related: [{ key: 'KITE-412', rel: 'Is depended on by' }] },
    { key: 'KITE-301', title: 'Design: freeze confirmation sheet', type: 'Task', status: 'Done', priority: 'Medium',
      assignee: 'sam', reporter: 'sam', sprint: 'Sprint 45', labels: ['design'], updated: '2026-11-07T11:00', assigned: false, cached: false,
      description: [{ t: 'p', x: 'Bottom sheet with the card name, the result and one action.' }], comments: [], olderComments: 0, related: [{ key: 'KITE-412', rel: 'Relates to' }] },
    { key: 'KITE-150', title: 'Crash on cold start with an expired token', type: 'Bug', status: 'Done', priority: 'Highest',
      assignee: 'teo', reporter: 'lena', sprint: 'Sprint 44', labels: ['crash'], updated: '2026-11-06T13:10', assigned: false, cached: true,
      description: [{ t: 'p', x: 'The app crashed when the saved token had expired during a cold start.' }], comments: [], olderComments: 2, related: [] }
  ];

  T.pullRequests = [
    { id: 'wallet-3101', number: 3101, title: 'Freeze toggle in card details', repo: 'acme-demo/wallet-android', author: 'me', mine: true, reviewRequested: false,
      branch: 'feature/card-freeze-toggle', updated: '2026-11-13T10:12', ci: 'pass', ciText: '12 checks passed', review: 'changes', reviewText: 'Changes requested',
      body: 'Adds the freeze toggle and observes card state from the repository.', reviewers: [{ who: 'teo', state: 'Changes requested' }, { who: 'lena', state: 'Pending' }],
      comments: [{ a: 'teo', at: '2026-11-13T10:12', x: 'Can the toggle observe the repository flow directly instead of the cached list?' }], cached: true },
    { id: 'wallet-3094', number: 3094, title: 'Receipt cache with expiry', repo: 'acme-demo/wallet-android', author: 'me', mine: true, reviewRequested: false,
      branch: 'feature/receipt-cache', updated: '2026-11-13T08:31', ci: 'running', ciText: 'Checks running (4 of 9)', review: 'approved', reviewText: '1 of 2 approvals',
      body: 'Stores 30 days of receipts with payment-date expiry.', reviewers: [{ who: 'mira', state: 'Approved' }, { who: 'teo', state: 'Commented' }],
      comments: [{ a: 'teo', at: '2026-11-13T08:31', x: 'What happens after a time-zone change?' }], cached: true },
    { id: 'wallet-3112', number: 3112, title: 'Enable configuration cache (experiment)', repo: 'acme-demo/wallet-android', author: 'me', mine: true, reviewRequested: false,
      branch: 'chore/config-cache', updated: '2026-11-12T18:02', ci: 'fail', ciText: '1 check failed', review: 'none', reviewText: 'No reviews yet',
      body: 'Turns on configuration cache for the app module only.', reviewers: [], comments: [], cached: true },
    { id: 'wallet-3108', number: 3108, title: 'Card list skeleton loader', repo: 'acme-demo/wallet-android', author: 'teo', mine: false, reviewRequested: true,
      branch: 'feature/card-skeleton', updated: '2026-11-13T11:05', ci: 'fail', ciText: '2 checks failed', review: 'requested', reviewText: 'Your review requested',
      body: 'Shows a skeleton while the card list loads.', reviewers: [{ who: 'me', state: 'Requested' }], comments: [], cached: true },
    { id: 'core-214', number: 214, title: 'Card state client for the v2 API', repo: 'acme-demo/payments-core', author: 'mira', mine: false, reviewRequested: true,
      branch: 'feature/card-state-v2', updated: '2026-11-13T09:44', ci: 'pass', ciText: '6 checks passed', review: 'requested', reviewText: 'Your review requested',
      body: 'Adds the v2 state client and maps error codes.', reviewers: [{ who: 'me', state: 'Requested' }, { who: 'teo', state: 'Approved' }],
      comments: [{ a: 'mira', at: '2026-11-13T09:44', x: 'Error mapping follows KITE-288.' }], cached: true }
  ];

  T.otherDeviceVersion = function (note) {
    if (note.id === 'n1') {
      return { device: 'Work laptop', editedAt: '2026-11-13T12:39', title: 'Freeze flow decisions',
        body: '<h2>What we decided</h2><p>Freeze is a card state, not a screen flag.</p><ul><li>One endpoint with a <b>target state</b>.</li><li>Show the result in place.</li><li>Add haptic feedback when the state changes.</li></ul><h2>Open questions</h2><ul class="checklist"><li data-checked="true">Second confirmation for unfreeze? No.</li><li data-checked="true">409 means try again later (Mira confirmed).</li></ul>' };
    }
    return { device: 'Work laptop', editedAt: '2026-11-13T12:39', title: note.title,
      body: note.body + '<p>Added on the other device: check this again on Monday.</p>' };
  };

  T.makeSeed = function (kind) {
    const ws = kind === 'empty' ? emptyWorkspace() : exampleWorkspace();
    return Object.assign({
      version: T.SEED_VERSION,
      workspace: kind === 'empty' ? 'empty' : 'example',
      session: null,
      statuses: statuses.map((s) => Object.assign({}, s)),
      prefs: { topicsView: 'list', weekStart: 'mon', keepCompleted: true, captureType: 'task' },
      demo: {
        now: '2026-11-13T13:05', online: true, jira: 'fresh', github: 'fresh',
        noteSync: {}, noteDemo: {}, firedReminders: [], dismissedReminders: [], jiraDownloaded: []
      },
      focus: {
        workMin: 25, breakMin: 5, phase: 'idle', running: false, endsAt: null, remainingMs: 25 * 60000,
        target: '', history: kind === 'empty' ? [] : [
          { endedAt: '2026-11-13T11:20', minutes: 25, label: 'Card freeze flow' },
          { endedAt: '2026-11-12T16:05', minutes: 25, label: 'Faster debug builds' }
        ]
      },
      seq: 100
    }, ws);
  };
})();
