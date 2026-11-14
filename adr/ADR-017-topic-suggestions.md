# ADR-017: Topic suggestions (Phase 1)

Status: Proposed (2026-11-14). The feature is the owner's request (D49): "when I open a topic, it would be good to catch keywords, tickets and even if I attach PR or Jira to a topic, it would be good to suggest to add another Jiras and PRs which are related to it. It would also be good to remove specific suggestion if it is not relatable and it won't suggest in that topic anymore." The owner approved the plan and its recommended defaults with "all approved. I will be AFK, continue" (relayed by the orchestrator session). The details below are the worker's choices and are queued for review (owner-review-queue.md, Q77 to Q85). Checked with unit tests, emulator rules tests and headless Chrome on the emulators, with SYNTHETIC data only. The rules are not deployed yet.

## Context

- A Topic links Jira tickets and pull requests by id only (`externalRefs`, `topicExternalLinks`, Phase 10 and 11). Saved copies of tickets and PRs live on the device (`thread-external-cache`), never in Firestore (D08, Q6).
- Jira and GitHub v1 are read-only (ADR-006, ADR-007). AI and advanced automations are deferred (01-product-spec.md).
- The v3 design (D33) has no suggestions element. Notes in Topic Details already show "Also in".

## Decision

### Rules, not AI

Suggestions come from fixed rules in `shared/domain` (`suggest/TopicSuggestions.kt`, `external/Mentions.kt`). Nothing is linked without a click. Everything runs in the browser.

| Signal | Source | Suggests |
|---|---|---|
| S1 Written | Jira keys and PR links in the Topic name, goal, Additional information (Where I left off and Next step before D52, ADR-018), the titles of its Tasks (not in Trash), and the titles and previews of its Notes (not in Trash). Never full Note bodies (Q82). | Jira tickets and PRs |
| S2 Issue links | `related` (Jira `issuelinks`) of the linked tickets' saved copies | Jira tickets |
| S3 PR mentions | Jira keys in the title, body and head branch of the linked PRs' saved copies | Jira tickets |

- A Jira key is the `JiraIssueKey` pattern in uppercase, on word boundaries. A `https://<site>/browse/KEY` link counts only for the connected site; links to other Jira sites are ignored. A PR is `https://github.com/o/r/pull/n` or `o/r#n`. Keys and PRs are found once per text, in order.
- Phase 2 (not built): S4 parent, children and siblings; S5 PRs that mention a linked key (GitHub search); the per-Topic suggestion cache with a TTL. Phase 3 (only if asked): free-word search.

### Confirmation and calls

An item is suggested only after Jira or GitHub showed it on this device: a saved ticket, a list or search row, or an issue link of a linked ticket for Jira; a saved PR or a list row for GitHub. Text like `UTF-8` therefore never shows.

While a Topic is open, a few items are asked about in the background with the existing loaders (GET only). Their answers are saved on the device like an opened ticket or PR, so the next open sends nothing for them.

| Provider | Asked per Topic open | Cap |
|---|---|---|
| Jira | Linked tickets with no saved copy (up to 10), then unconfirmed keys: keys of projects already known on the device first, then keys of at most 3 new projects, 5 keys in all | 15 GETs |
| GitHub | Linked PRs with no saved copy, then unconfirmed written PRs | 5 GETs |

- Each item is asked at most once per open. Nothing is asked offline, when not connected, inside a rate limit, or after a 401. The rows already on the device stay.
- A key Jira answers with 404 or 403 is remembered as "not a ticket" for a day, in browser storage (`thread.jira.notTickets`, `.synthetic` in fixture mode), keys and times only (Q85). It is forgotten when the Jira connection changes. Suggestion checks never set the section's error banner for a 403.
- Sent to Jira and GitHub: ticket keys and PR ids only. The Topic's text is never sent.

### Ranking

| Reason | Points |
|---|---|
| Written in this Topic (S1) | 100 |
| A linked PR mentions it (S3) | 80 |
| Jira issue link to a linked ticket (S2) | 60 |

- Points of different kinds add up; the same kind counts once. A row shows its best two reasons.
- Done tickets get minus 20 (Q80). Ties: most recently updated first, then the order found.
- Left out: items linked to this Topic, items dismissed in this Topic, unconfirmed items. Items linked to another Topic stay, with "Also in <Topic>".
- 3 rows per section, then "Show N more", up to 10 (Q78).

### Dismiss: one optional field

`users/{uid}/topicExternalLinks/{topicId}_{refId}`: `{topicId, refId, linked, dismissed?, updatedAt, schemaVersion: 1}`.

An item is dismissed in a Topic only when `dismissed == true && !linked`. **Linked wins.** Documents without the field read as not dismissed.

| Action | Link document write | Undo |
|---|---|---|
| Dismiss | `externalRefs` (ids and URL only, as Link does) and `linked: false, dismissed: true` | `dismissed: false` |
| Restore | `linked: false, dismissed: false` | `dismissed: true` |
| Link | `linked: true`. `dismissed` is not written, so it stays as stored (Q84). | as before |
| Unlink, and removing an item in the Link picker | `linked: false, dismissed: true` (Q79) | `linked: true`, `dismissed` as before |

- Writes are merges through the existing outbox and sync path (ADR-009), so dismissals sync, survive reloads and reach other devices.
- Trash hides the links of a Topic in Trash (`visibleWorkspace`), so dismissals leave with the Topic and come back on Restore (D42). Delete forever leaves the documents as hidden leftovers, the same as links today.
- `schemaVersion` stays 1. Older builds read named fields only, so they ignore `dismissed`.

### Firestore rules (`validExternalLink`)

```
-    return link.keys().hasOnly(['topicId', 'refId', 'linked', 'updatedAt', 'schemaVersion'])
+    return link.keys().hasOnly(['topicId', 'refId', 'linked', 'dismissed', 'updatedAt', 'schemaVersion'])
...
-        && link.linked is bool;
+        && link.linked is bool
+        && (!('dismissed' in link) || link.dismissed is bool);
```

- `hasAll` is unchanged, so old documents stay valid.
- `linked` and `dismissed` may both be true. An older build can link an item that is dismissed, and "linked wins" handles it. A stricter rule would refuse that older build's write.

### UI

- A "Suggested" group under the linked rows of a Topic's Jira and Pull requests sections (Q77). Rows reuse `xrow` with an `xrow--suggested` modifier: dashed icon frame, muted title, the reasons on the snippet line, "Also in" tags.
- **Link**: a small text button, the existing link flow, toast "Linked" with Undo. **Don't suggest**: an icon button labelled "Don't suggest KEY in this Topic", toast "KEY won't be suggested in this Topic." with Undo.
- "Not suggested here (N)" opens a dialog. Each row shows the key and the title from the device, or "Not on this device", with **Restore** (toast with Undo). "Done" closes it.
- Focus: after Link or Don't suggest, the next suggestion's link, else the section heading. In the dialog, the next Restore button, else Done. Closing the dialog returns to "Not suggested here", else the heading.
- Not connected, or nothing to suggest: no group. Offline: the rows on the device, no error. No setting to turn suggestions off (Q83).

## Consequences

- **The rules must be deployed before this build ships.** Until then the real project refuses Unlink, Don't suggest and Restore, because each writes `dismissed`. The refused changes stay in the sync panel with Retry, Copy and Discard (Phase 12), so nothing is lost, but Unlink does not sync. Link writes are unaffected.
- After Unlink, an item is no longer suggested in that Topic. It shows under "Not suggested here" and can be restored.
- Saved copies grow a little: suggestion checks save the tickets and PRs they ask about, like opening them.
- Closed and merged PRs are not ranked lower: the saved PR summary has no state yet.
- A written PR that GitHub answers with 404 is asked again on each open (at most 5 per open). There is no "not a PR" memory.
- Suggestions do not show while a ticket or PR is open in the side panel, because the panel replaces the sections.

## Evidence

- Domain unit tests: `MentionsTest` (6), `TopicSuggestionsTest` (6), `ExternalLinksTest` (dismiss, restore, Undo, linked wins, Unlink and the picker dismiss, other Topics untouched, Trash), `RefusedChangesTest` (the dismissal wording).
- Data tests: `WorkspaceWriteJsonTest` (the field in the write payload), `FirestoreExternalLinksRulesEmulatorTest.aLinkMayCarryADismissedFlagAndNothingElse` (the old shape, Dismiss, Link over a dismissed document and Restore accepted; a non-bool or null `dismissed`, an extra field, a wrong id, another account and delete refused), `FirestoreExternalLinksEmulatorTest.aDismissalSyncsFollowsTheTopicIntoTrashAndBackAndLinkWins`.
- webApp test: `SuggestionReasonTextTest`.
- Headless Chrome on the emulator build, a scratch profile and SYNTHETIC providers, 44 checks: reasons and their order, Show more, Link and Undo, keyboard focus after Link and Don't suggest, the dismissed document's exact keys, Undo, a reload, a second browser context, the dialog and Restore, Unlink dismisses and Undo, Trash and Restore of the Topic, offline, 390 px, no console errors, no request to Atlassian or GitHub hosts.

## Not verified

- The real Firebase project: the rules are not deployed.
- Live Jira and GitHub: only the SYNTHETIC providers ran. The noise level on real Topics is unknown.
- Safari and Firefox. Only headless Chrome ran the browser checks.
