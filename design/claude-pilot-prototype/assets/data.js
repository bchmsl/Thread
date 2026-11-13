/* Synthetic demo data. Every person, ticket key (NOVA-*), repository (orbit/*)
   and PR number below is invented. Nothing represents a real Jira or GitHub
   record. The demo clock is fixed at Thursday 12 November 2026, 14:10. */
(function () {
  'use strict';

  const DEMO_NOW = '2026-11-12T14:10';

  const statuses = [
    { id: 'st-planned', name: 'Planned', category: 'planned' },
    { id: 'st-active', name: 'Active', category: 'active' },
    { id: 'st-review', name: 'In review', category: 'active' },
    { id: 'st-paused', name: 'Paused', category: 'paused' },
    { id: 'st-done', name: 'Done', category: 'done' }
  ];

  const people = {
    me: { name: 'Demo Engineer', handle: 'demo-dev', initials: 'DE' },
    mara: { name: 'Mara Lind', handle: 'mlind', initials: 'ML' },
    theo: { name: 'Theo Park', handle: 'tpark', initials: 'TP' },
    ines: { name: 'Ines Okafor', handle: 'iokafor', initials: 'IO' },
    ravi: { name: 'Ravi Tal', handle: 'rtal', initials: 'RT' },
    juno: { name: 'Juno Bell', handle: 'jbell', initials: 'JB' }
  };

  function example() {
    return {
      topics: [
        { id: 't-qr', name: 'QR payments', color: 'lavender', statusId: 'st-active',
          goal: 'Pay any merchant QR code, with retries that never charge twice.',
          leftOff: 'Timeout path returns to the scanner but loses the entered amount.',
          nextStep: 'Test timeout and retry on a throttled network.', createdAt: '2026-10-28T10:00' },
        { id: 't-bill', name: 'Bill payment errors', color: 'mint', statusId: 'st-review',
          goal: 'Every failed bill payment tells the user what to do next.',
          leftOff: 'Copy for the five most common error codes is in review.',
          nextStep: 'Reply to the copy review comments on PR #239.', createdAt: '2026-11-02T09:30' },
        { id: 't-cold', name: 'App cold start', color: 'butter', statusId: 'st-active',
          goal: 'Bring cold start under 1.2 s on the low-end test device.',
          leftOff: 'Trace shows analytics init blocking the main thread for 310 ms.',
          nextStep: 'Profile cold start again with lazy analytics init.', createdAt: '2026-11-06T15:20' },
        { id: 't-rec', name: 'Recurring payments API', color: 'peach', statusId: 'st-paused',
          goal: 'Schedule repeating transfers through the new payments contract.',
          leftOff: 'Waiting for contract v2. Retry rules are still undefined.',
          nextStep: 'Follow up on the API contract at 16:30.', createdAt: '2026-10-20T11:00' },
        { id: 't-map', name: 'Payment mapper tests', color: 'sky', statusId: 'st-planned',
          goal: 'Cover the payment result mapper before the enum migration.',
          leftOff: '', nextStep: 'List rounding edge cases for currency amounts.', createdAt: '2026-11-09T17:00' },
        { id: 't-rcpt', name: 'Payment receipt', color: 'rose', statusId: 'st-done',
          goal: 'Share a receipt after any successful payment.',
          leftOff: 'Shipped in the last release.', nextStep: '', createdAt: '2026-09-30T10:00' }
      ],
      tasks: [
        { id: 'k1', title: 'Confirm backend error codes for QR failures', topicId: 't-qr', done: false, planned: '2026-11-11', deadline: '2026-11-11T18:00', inbox: false, createdAt: '2026-11-09T10:00' },
        { id: 'k13', title: 'Summarize the cold start trace findings', topicId: 't-cold', done: false, planned: null, deadline: '2026-11-12T12:00', inbox: false, createdAt: '2026-11-10T09:00' },
        { id: 'k2', title: 'Test timeout and retry on a throttled network', topicId: 't-qr', done: false, planned: '2026-11-12', deadline: null, inbox: false, createdAt: '2026-11-10T11:00' },
        { id: 'k5', title: 'Review error copy with design', topicId: 't-bill', done: false, planned: '2026-11-12', deadline: null, inbox: false, createdAt: '2026-11-08T12:00' },
        { id: 'k8', title: 'Profile cold start on the low-end device', topicId: 't-cold', done: false, planned: '2026-11-12', deadline: null, inbox: false, createdAt: '2026-11-11T16:00' },
        { id: 'k9', title: 'Book a desk for Friday', topicId: null, done: false, planned: '2026-11-12', deadline: null, inbox: false, createdAt: '2026-11-11T18:00' },
        { id: 'k6', title: 'Update mapper for the new error enum', topicId: 't-bill', done: false, planned: '2026-11-10', deadline: null, inbox: false, createdAt: '2026-11-07T10:00' },
        { id: 'k3', title: 'Add camera permission fallback', topicId: 't-qr', done: false, planned: '2026-11-14', deadline: null, inbox: false, createdAt: '2026-11-10T11:30' },
        { id: 'k4', title: 'Connect scanner to the payment sheet', topicId: 't-qr', done: true, completedAt: '2026-11-10T16:40', planned: '2026-11-10', deadline: null, inbox: false, createdAt: '2026-11-03T09:00' },
        { id: 'k7', title: 'Draft retry policy questions', topicId: 't-rec', done: false, planned: null, deadline: null, inbox: false, createdAt: '2026-11-04T14:00' },
        { id: 'k11', title: 'Renew the test device certificate', topicId: null, done: false, planned: null, deadline: '2026-11-15T12:00', inbox: false, createdAt: '2026-11-05T09:00' },
        { id: 'k12', title: 'List rounding edge cases', topicId: 't-map', done: false, planned: null, deadline: null, inbox: false, createdAt: '2026-11-09T17:05' },
        { id: 'k14', title: 'Ship receipt share sheet', topicId: 't-rcpt', done: true, completedAt: '2026-10-29T15:00', planned: null, deadline: null, inbox: false, createdAt: '2026-10-01T09:00' },
        { id: 'k10', title: 'Check the QR timeout copy', topicId: null, done: false, planned: null, deadline: null, inbox: true, createdAt: '2026-11-12T09:12' }
      ],
      notes: [
        { id: 'n1', title: 'QR payment decisions', topicIds: ['t-qr'], inbox: false, updatedAt: '2026-11-12T13:40',
          html: '<h2>Retry behavior</h2><p>Keep the scanned merchant and the entered amount after a timeout. The retry must reuse the <b>same idempotency key</b>, so the backend can reject a duplicate charge.</p><ul class="checklist"><li data-checked="true">Agree on timeout length (20 s)</li><li data-checked="false">Confirm the error code for an expired QR</li></ul><h2>Open questions</h2><ul><li>Does a declined card count as a retryable failure?</li><li>Who owns the copy for partial outages?</li></ul><p>Spec draft: <a href="https://example.com/qr-retry-spec" rel="noopener noreferrer">example.com/qr-retry-spec</a></p>' },
        { id: 'n3', title: 'Payments sync, 12 November', topicIds: ['t-qr', 't-bill'], inbox: false, updatedAt: '2026-11-12T11:05',
          html: '<p>One note linked to two Topics. Editing it from either Topic changes the same text.</p><h2>QR payments</h2><ul><li>Backend owns the idempotency key format.</li><li>Timeout stays at 20 s for the pilot.</li></ul><h2>Bill payment errors</h2><ul><li>Design reviews copy on Friday.</li><li><i>Keep error codes visible in support mode.</i></li></ul>' },
        { id: 'n5', title: 'Cold start baseline', topicIds: ['t-cold'], inbox: false, updatedAt: '2026-11-11T17:30',
          html: '<p>Low-end test device, release build, 10 runs.</p><ol><li>Median cold start: 1.64 s</li><li>Analytics init: 310 ms on the main thread</li><li>Remote config fetch: 120 ms, not blocking</li></ol>' },
        { id: 'n2', title: 'PR review checklist', topicIds: [], inbox: false, updatedAt: '2026-11-11T09:00',
          html: '<p>A standalone note. It belongs to no Topic and is not in the Inbox.</p><ul class="checklist"><li data-checked="false">Follow the changed flow end to end</li><li data-checked="false">Check loading, empty and error states</li><li data-checked="false">Look for main-thread work</li></ul>' },
        { id: 'n4', title: 'Ideas from the payments sync', topicIds: [], inbox: true, updatedAt: '2026-11-12T11:20',
          html: '<p>Show the merchant name before the amount field. Maybe cache the last three merchants.</p>' }
      ],
      reminders: [
        { id: 'r2', target: { type: 'task', id: 'k2' }, at: '2026-11-12T11:00', text: 'Start the timeout tests', dismissed: false },
        { id: 'r1', target: { type: 'topic', id: 't-rec' }, at: '2026-11-12T16:30', text: 'Follow up on the API contract', dismissed: false },
        { id: 'r3', target: { type: 'note', id: 'n3' }, at: '2026-11-12T18:00', text: 'Send the sync summary', dismissed: false },
        { id: 'r4', target: { type: 'task', id: 'k11' }, at: '2026-11-14T09:00', text: 'Renew certificate before it expires', dismissed: false }
      ],
      links: {
        jira: { 'NOVA-142': ['t-qr'], 'NOVA-143': ['t-qr'], 'NOVA-151': ['t-qr', 't-bill'], 'NOVA-160': ['t-bill'], 'NOVA-171': ['t-rec'] },
        prs: { 'pr-248': ['t-qr'], 'pr-252': ['t-qr'], 'pr-239': ['t-bill'], 'pr-261': ['t-cold'] }
      },
      trash: [
        { id: 'tr1', type: 'note', deletedAt: '2026-11-11T17:40',
          item: { id: 'n9', title: 'Old scanner spike', topicIds: ['t-qr'], inbox: false, updatedAt: '2026-11-03T10:00',
            html: '<p>Spike notes for the first scanner library. Replaced by the platform scanner.</p>' } },
        { id: 'tr2', type: 'task', deletedAt: '2026-11-10T12:15',
          item: { id: 'k20', title: 'Try a second QR scanning library', topicId: 't-qr', done: false, planned: null, deadline: null, inbox: false, createdAt: '2026-11-01T10:00' } }
      ],
      cachedJira: ['NOVA-142', 'NOVA-143', 'NOVA-151', 'NOVA-160', 'NOVA-171', 'NOVA-188'],
      cachedPrs: ['pr-248', 'pr-252', 'pr-239', 'pr-255', 'pr-261']
    };
  }

  function empty() {
    return { topics: [], tasks: [], notes: [], reminders: [], links: { jira: {}, prs: {} }, trash: [], cachedJira: [], cachedPrs: [] };
  }

  /* Remote catalog: what the simulated Jira and GitHub would return. */
  const jira = {
    'NOVA-142': { key: 'NOVA-142', summary: 'Build QR scanner screen', status: 'In Progress', cat: 'progress', type: 'Story', priority: 'High',
      assignee: 'me', reporter: 'mara', sprint: 'Payments 24', labels: ['qr', 'android'], updated: '2026-11-12T10:20', assigned: true,
      description: [
        { t: 'p', text: 'As a customer I can scan a merchant QR code and land on a prefilled payment sheet.' },
        { t: 'h', text: 'Acceptance criteria' },
        { t: 'ul', items: ['Camera opens in under 500 ms', 'Invalid codes show `QR_INVALID` copy', 'Torch toggle is reachable with TalkBack'] },
        { t: 'unsupported', text: 'Embedded flow diagram (diagram macro). Open the ticket in Jira to view it.' }
      ],
      comments: [
        { by: 'mara', at: '2026-11-11T15:02', text: 'Design is final. Torch icon moved to the bottom bar.' },
        { by: 'theo', at: '2026-11-12T09:40', text: 'Backend returns 422 for an expired code, not 400.' }
      ],
      related: [{ key: 'NOVA-143', rel: 'blocks' }, { key: 'NOVA-210', rel: 'relates to' }] },
    'NOVA-143': { key: 'NOVA-143', summary: 'Call QR payment endpoint', status: 'To Do', cat: 'todo', type: 'Task', priority: 'High',
      assignee: 'me', reporter: 'mara', sprint: 'Payments 24', labels: ['qr', 'api'], updated: '2026-11-10T12:00', assigned: true,
      description: [
        { t: 'p', text: 'Send the decoded QR payload to the payment endpoint with an idempotency key.' },
        { t: 'code', text: 'POST /v1/qr-payments\nIdempotency-Key: <uuid>' }
      ],
      comments: [{ by: 'ines', at: '2026-11-10T13:15', text: 'Key format is a UUID v4. Reuse it on retry.' }],
      related: [{ key: 'NOVA-142', rel: 'is blocked by' }, { key: 'NOVA-151', rel: 'relates to' }] },
    'NOVA-151': { key: 'NOVA-151', summary: 'Map QR and bill payment error codes', status: 'Code Review', cat: 'progress', type: 'Task', priority: 'Medium',
      assignee: 'me', reporter: 'juno', sprint: 'Payments 24', labels: ['errors'], updated: '2026-11-12T08:55', assigned: true,
      description: [
        { t: 'p', text: 'One mapper for payment error codes, shared by QR and bill payments.' },
        { t: 'ol', items: ['Collect codes from both endpoints', 'Map to user-facing categories', 'Log unknown codes'] }
      ],
      comments: [{ by: 'juno', at: '2026-11-11T10:30', text: 'Linked to both Topics on purpose. It unblocks both.' }],
      related: [{ key: 'NOVA-160', rel: 'relates to' }] },
    'NOVA-160': { key: 'NOVA-160', summary: 'Bill payment error messages', status: 'In Progress', cat: 'progress', type: 'Story', priority: 'Medium',
      assignee: 'me', reporter: 'juno', sprint: 'Payments 24', labels: ['copy', 'bills'], updated: '2026-11-11T16:45', assigned: true,
      description: [{ t: 'p', text: 'Replace generic failures with actionable messages for the five most common codes.' }],
      comments: [], related: [{ key: 'NOVA-151', rel: 'relates to' }] },
    'NOVA-171': { key: 'NOVA-171', summary: 'Recurring payments contract v2', status: 'Blocked', cat: 'todo', type: 'Task', priority: 'Low',
      assignee: 'me', reporter: 'ravi', sprint: 'Backlog', labels: ['api'], updated: '2026-11-06T09:10', assigned: true,
      description: [{ t: 'p', text: 'Waiting for the backend contract. Retry and skip rules are open.' }],
      comments: [{ by: 'ravi', at: '2026-11-06T09:10', text: 'Contract draft expected this week.' }], related: [] },
    'NOVA-188': { key: 'NOVA-188', summary: 'Crash on cold start with low memory', status: 'To Do', cat: 'todo', type: 'Bug', priority: 'High',
      assignee: 'me', reporter: 'ines', sprint: 'Payments 24', labels: ['crash', 'startup'], updated: '2026-11-12T07:30', assigned: true,
      description: [{ t: 'p', text: 'Low-memory devices crash during analytics init. Seen on 0.4% of cold starts.' }],
      comments: [], related: [] },
    'NOVA-99': { key: 'NOVA-99', summary: 'Upgrade the payments SDK', status: 'Done', cat: 'done', type: 'Task', priority: 'Medium',
      assignee: 'theo', reporter: 'mara', sprint: 'Payments 22', labels: ['sdk'], updated: '2026-10-21T11:00', assigned: false,
      description: [{ t: 'p', text: 'SDK 5 removes the legacy card form.' }], comments: [], related: [] },
    'NOVA-205': { key: 'NOVA-205', summary: 'Share receipt as PDF', status: 'Done', cat: 'done', type: 'Story', priority: 'Low',
      assignee: 'ines', reporter: 'mara', sprint: 'Payments 23', labels: ['receipt'], updated: '2026-10-29T15:00', assigned: false,
      description: [{ t: 'p', text: 'Receipts can be shared as a PDF from the success screen.' }], comments: [], related: [] },
    'NOVA-210': { key: 'NOVA-210', summary: 'QR merchant name is truncated', status: 'To Do', cat: 'todo', type: 'Bug', priority: 'Medium',
      assignee: 'theo', reporter: 'juno', sprint: 'Payments 24', labels: ['qr', 'ui'], updated: '2026-11-12T12:05', assigned: false,
      description: [{ t: 'p', text: 'Merchant names longer than 24 characters are cut without an ellipsis on the payment sheet.' },
        { t: 'ul', items: ['Seen on small screens', 'Font scale 1.3 makes it worse'] }],
      comments: [{ by: 'theo', at: '2026-11-12T12:05', text: 'Probably a missing maxLines on the title.' }],
      related: [{ key: 'NOVA-142', rel: 'relates to' }] }
  };

  const prs = {
    'pr-248': { id: 'pr-248', number: 248, repo: 'orbit/android-wallet', title: 'Handle QR payment timeout', author: 'me', draft: false,
      ci: 'pass', review: 'changes', mine: true, requested: false, branch: 'feature/qr-timeout', base: 'main', updated: '2026-11-12T12:40', files: 9,
      description: [{ t: 'p', text: 'Keeps scanned context after a timeout and retries with the same idempotency key.' }],
      checks: [{ name: 'Unit tests', state: 'pass' }, { name: 'Lint', state: 'pass' }, { name: 'Instrumented tests', state: 'pass' }],
      reviewers: [{ who: 'theo', state: 'changes' }, { who: 'mara', state: 'pending' }],
      comments: [{ by: 'theo', at: '2026-11-12T11:30', text: 'Please keep the amount in SavedStateHandle, not in the ViewModel field.' }] },
    'pr-252': { id: 'pr-252', number: 252, repo: 'orbit/android-wallet', title: 'Camera permission fallback for the scanner', author: 'me', draft: true,
      ci: 'run', review: 'pending', mine: true, requested: false, branch: 'feature/scanner-fallback', base: 'main', updated: '2026-11-12T13:55', files: 4,
      description: [{ t: 'p', text: 'Draft. Manual code entry when camera permission is denied.' }],
      checks: [{ name: 'Unit tests', state: 'run' }, { name: 'Lint', state: 'pass' }], reviewers: [], comments: [] },
    'pr-239': { id: 'pr-239', number: 239, repo: 'orbit/android-wallet', title: 'Bill payment error copy', author: 'me', draft: false,
      ci: 'fail', review: 'approved', mine: true, requested: false, branch: 'feature/bill-error-copy', base: 'main', updated: '2026-11-11T17:20', files: 6,
      description: [{ t: 'p', text: 'New copy for five error codes. Strings only, no logic changes.' }],
      checks: [{ name: 'Unit tests', state: 'pass' }, { name: 'Lint', state: 'fail' }, { name: 'Instrumented tests', state: 'pass' }],
      reviewers: [{ who: 'juno', state: 'approved' }],
      comments: [{ by: 'juno', at: '2026-11-11T16:00', text: 'Copy approved. Lint fails on an unused string.' }] },
    'pr-255': { id: 'pr-255', number: 255, repo: 'orbit/payments-core', title: 'Migrate payment mapper to sealed results', author: 'theo', draft: false,
      ci: 'pass', review: 'requested', mine: false, requested: true, branch: 'refactor/sealed-results', base: 'main', updated: '2026-11-12T10:05', files: 14,
      description: [{ t: 'p', text: 'Replaces nullable error fields with a sealed result type.' }],
      checks: [{ name: 'Unit tests', state: 'pass' }, { name: 'Lint', state: 'pass' }],
      reviewers: [{ who: 'me', state: 'requested' }, { who: 'ines', state: 'approved' }],
      comments: [{ by: 'ines', at: '2026-11-12T10:40', text: 'Looks good. Mapper tests could cover rounding.' }] },
    'pr-261': { id: 'pr-261', number: 261, repo: 'orbit/android-wallet', title: 'Lazy init for analytics on cold start', author: 'ines', draft: false,
      ci: 'run', review: 'requested', mine: false, requested: true, branch: 'perf/lazy-analytics', base: 'main', updated: '2026-11-12T13:20', files: 3,
      description: [{ t: 'p', text: 'Moves analytics setup off the main thread until first frame.' }],
      checks: [{ name: 'Unit tests', state: 'pass' }, { name: 'Startup benchmark', state: 'run' }],
      reviewers: [{ who: 'me', state: 'requested' }], comments: [] }
  };

  window.ThreadData = { DEMO_NOW, statuses, people, example, empty, jira, prs };
})();
