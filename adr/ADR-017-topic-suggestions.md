# ADR-017: Topic suggestions (Phases 1 and 2)

Status: Proposed (2026-11-14). The feature is the owner's request (D49): "when I open a topic, it would be good to catch keywords, tickets and even if I attach PR or Jira to a topic, it would be good to suggest to add another Jiras and PRs which are related to it. It would also be good to remove specific suggestion if it is not relatable and it won't suggest in that topic anymore." The owner approved the plan and its recommended defaults with "all approved. I will be AFK, continue" (relayed by the orchestrator session). The details below are the worker's choices and are queued for review (owner-review-queue.md, Q77 to Q85). Checked with unit tests, emulator rules tests and headless Chrome on the emulators, with SYNTHETIC data only. Update 2026-11-15: the rules were deployed to the real Firebase project before `a6ae332` was pushed (2026-11-14); Phase 2 changes no rules. The deployed ruleset matches `firebase/firestore.rules` at `cd0a581` (SHA-256 starts `765c3c9e48ac`).

Phase 2 (S4 and S5, 2026-11-14) was built under the same approval, relayed by the orchestrator: "all approved. I am afk. Continue without my approvals please. Everything is approved from me", on the condition that it degrades quietly without live checks. It adds two GET searches with no new scopes, no Firestore field and no rules change. Its Jira and GitHub API assumptions are marked **[inference, not checked live]** below; only SYNTHETIC providers ran. Its details are queued as Q100 to Q103.

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
| S4 Family (Phase 2) | A Jira search for the linked tickets' parents, their children and the parents' other children | Jira tickets |
| S5 PRs that mention (Phase 2) | A GitHub search for PRs whose title or body names a linked key | PRs |

- A Jira key is the `JiraIssueKey` pattern in uppercase, on word boundaries. A `https://<site>/browse/KEY` link counts only for the connected site; links to other Jira sites are ignored. A PR is `https://github.com/o/r/pull/n` or `o/r#n`. Keys and PRs are found once per text, in order.
- Phase 3 (only if asked): free-word search. Not built.

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

### Phase 2 searches (S4 and S5)

Both run while a Topic is open, after its first render, from the linked Jira tickets Jira already confirmed (a saved copy). Each answer is saved on the device per Topic and is fresh for 10 minutes; a fresh answer for the same keys sends nothing. A change of links waits 800 ms, so several quick changes send one search.

| | S4 Family (Jira) | S5 PRs that mention (GitHub) |
|---|---|---|
| Keys | Linked and confirmed, sorted, at most 10 | Linked and confirmed, sorted, at most 5 |
| Requests per refresh | Up to 3 GET `/rest/api/3/search/jql`, one page of 50, fields `summary,issuetype,status,updated,parent`: (1) `key in (linked)`; (2) `key in (parents)`, only when there are parents; (3) `parent in (linked + parents Jira showed) ORDER BY updated DESC`, only when (1) or (2) gave rows | One GET `/search/issues` per repository, at most 2: `is:pr repo:o/r in:title,body ("K1" OR "K2")`, `sort=updated&order=desc&per_page=20`, under 256 characters (keys are dropped from the end until it fits) |
| Repositories | n/a | The repositories of the Topic's linked PRs, most linked first; with none, those of the PRs already on the device (lists and opened PRs) |
| Budget | 3 refreshes a minute (9 searches) | 3 refreshes a minute (6 searches), next to the lists' 10, inside GitHub's 30 |
| Saved in | `thread-external-cache` meta, `family:<topicId>`, marked with the Jira site | `thread-external-cache` lists, `mentions:<topicId>`, without PR descriptions |
| Thread checks itself | Each ticket once (its first row); a parent Jira did not show is left out of (3) | A PR (`pull_request` present), in the searched repository (any letter case), whose title or body names a searched key on word boundaries |

- Sent: Jira keys and repository names only. Never the Topic's text.
- Refused searches (Jira 400, 403, 404; GitHub 422, 403, 404; an answer Thread cannot read) count as no rows and are saved, so they are not asked again for 10 minutes. A problem that may pass (401, a rate limit, no answer, 5xx) saves nothing; the saved answer stays.
- Nothing is asked offline, when not connected, after a 401, inside a rate limit or past the budget. Only a 401 (the app-wide "Reconnect") and a rate limit (holds the next request) are noted; the rest is quiet. No error banner.
- Rows from these searches count as confirmed: Jira or GitHub showed them.

API assumptions, **[inference, not checked live]**:
- Jira `search/jql` rows carry `fields.parent.key` when `parent` is asked for, for both epics and sub-tasks.
- JQL `parent in (...)` finds an epic's children and a ticket's sub-tasks on the owner's site.
- JQL `key in (...)` or `parent in (...)` with a key Jira does not know or does not show answers 400 for the whole search. Thread then keeps no rows (see Consequences).
- GitHub search accepts parentheses and `OR` inside `in:title,body` as written, with `is:pr` and `repo:` applied to every term.
- `/search/issues` items carry `body`, `state` and `pull_request`, and `repository_url` names the repository.
- GitHub answers 422 for a repository the token cannot search.
- Searches from suggestions and the lists together stay inside GitHub's 30 searches a minute.

### Ranking

| Reason | Points |
|---|---|
| Written in this Topic (S1) | 100 |
| A linked PR mentions it (S3) | 80 |
| Jira issue link to a linked ticket (S2) | 60 |
| A PR that names a linked ticket (S5) | 80 |
| Parent or child of a linked ticket (S4) | 60 |
| Same parent as a linked ticket (S4), at most 5 siblings, in Jira's order | 30 |

- Points of different kinds add up; the same kind counts once. A row shows its best two reasons.
- Done tickets and closed or merged PRs found by S5 get minus 20 (Q80, Q101). Ties: most recently updated first, then the order found.
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
- Closed and merged PRs are ranked lower only when S5 found them; a PR suggested only by S1 has no state yet.
- When Jira refuses one linked key in S4 search (1), for example a key it no longer shows, the whole search is refused and the Topic gets no family rows for 10 minutes **[inference, not checked live]**.
- S4 search (3) keeps the 50 most recently updated children; a large epic's older children are not suggested. S5 keeps 20 PRs per repository.
- The `family:` and `mentions:` entries of a deleted Topic stay on the device until Jira or GitHub is disconnected, like the lists.
- S5 searches only the Topic's linked repositories (or the known ones): a PR in another repository is not found.
- A written PR that GitHub answers with 404 is asked again on each open (at most 5 per open). There is no "not a PR" memory.
- Since D70 a ticket or PR opens as a card on top of the Topic, so the sections and their suggestions stay under it. (Before, the side panel replaced the sections and hid them.)

## Evidence

- Domain unit tests: `MentionsTest` (6), `TopicSuggestionsTest` (6), `ExternalLinksTest` (dismiss, restore, Undo, linked wins, Unlink and the picker dismiss, other Topics untouched, Trash), `RefusedChangesTest` (the dismissal wording).
- Data tests: `WorkspaceWriteJsonTest` (the field in the write payload), `FirestoreExternalLinksRulesEmulatorTest.aLinkMayCarryADismissedFlagAndNothingElse` (the old shape, Dismiss, Link over a dismissed document and Restore accepted; a non-bool or null `dismissed`, an extra field, a wrong id, another account and delete refused), `FirestoreExternalLinksEmulatorTest.aDismissalSyncsFollowsTheTopicIntoTrashAndBackAndLinkWins`.
- webApp test: `SuggestionReasonTextTest`.
- Phase 2 unit tests: `RelatedSuggestionsTest` (8: family relations, the sibling cap, parents to ask, search keys, repositories, mentions still linked, points, freshness), `MentionsTest.mentionedKeysAreTheGivenKeysTheTextNamesOnWordBoundaries`, `JiraFamilyRepositoryTest` (7: the three JQL searches and fields, de-duplication, nothing sent without keys, refused 400/403/404 saved as no rows, a refused parent left out of the child search, 401/429/5xx and a late 503 save nothing, the budget, another site or no connection), `GitHubPullRequestMentionsTest` (6: the query, its length and repository checks, items that are not PRs, in another repository or not naming the key, a refused repository, problems that may pass, two repositories and the budget), four new `SuggestionReasonTextTest` asserts.
- Phase 2 headless Chrome on the emulator build, a scratch profile and SYNTHETIC providers, 32 checks: parent, siblings and child with their reasons and order, PRs #12 and #14 with "Mentions", closed below open, #15 (`SYN-1010`) left out, the saved answers per Topic (site-marked, no PR descriptions), Link and Undo, Don't suggest, a reload reuses the fresh answers (same save time), a refused GitHub search saved as no rows with no banner, a linked parent turns siblings into children, offline, 390 px, nothing from the searches in Firestore, no console errors, no request to Atlassian or GitHub hosts.
- Headless Chrome on the emulator build, a scratch profile and SYNTHETIC providers, 44 checks: reasons and their order, Show more, Link and Undo, keyboard focus after Link and Don't suggest, the dismissed document's exact keys, Undo, a reload, a second browser context, the dialog and Restore, Unlink dismisses and Undo, Trash and Restore of the Topic, offline, 390 px, no console errors, no request to Atlassian or GitHub hosts.

## Not verified

- The real Firebase project: the rules are deployed (see Status), but the checks above ran on the emulators only.
- Live Jira and GitHub: only the SYNTHETIC providers ran. The noise level on real Topics is unknown.
- Every Phase 2 API assumption listed under "Phase 2 searches" [inference, not checked live], and how Jira and GitHub rate-limit these searches in practice.
- Safari and Firefox. Only headless Chrome ran the browser checks.
