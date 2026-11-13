# ADR-014: GitHub in the workspace

Status: Proposed (2026-11-13). Written in Phase 11 while the owner was away. Built on ADR-007 (GitHub access and cache), ADR-008 (token storage), ADR-012 (Trash and search) and ADR-013 (Jira in the workspace, whose Firestore shapes this reuses). Checked with the domain tests (`ExternalLinksTest`, `SearchTest`, `ExternalLoaderTest`), the data tests (`GitHubRepositoriesTest`), the existing emulator tests for `externalRefs` and `topicExternalLinks`, and the Phase 11 browser checks on SYNTHETIC fixtures only. Nothing here was checked against real GitHub. The owner review is queued (owner-review-queue.md, Q50 onwards).

## Context

- D08, D09: GitHub is read-only in v1. Firestore stores app-owned content only.
- Q6 (provisional): titles and other provider payload never go to Firestore.
- ADR-007: REST with the owner's token, GET only. Search for the two lists; four requests for one pull request (pull, reviews, combined status, check runs). Search and core have separate rate limits.
- ADR-008: the token is stored in IndexedDB on this browser profile only. It is never synced.
- v3 prototype (D33): `prs.html`, `item.html?kind=pr`, the Topic pull request section and side panel, Today "Review requested", Settings Connections, ⌘K "Pull requests (downloaded)".

## Decision

### What Firestore holds

- The same two collections as Jira (ADR-013), no new rules: `externalRefs/{github~owner~repo~number}` with `{provider: "github", instance: "owner/repo", key: "number", url, updatedAt, schemaVersion: 1}`, and `topicExternalLinks/{topicId}_{refId}`. The ref id replaces `/` in the repository with `~`.
- One pull request is stored once, however many Topics link it. Unlink writes `linked: false`; nothing is deleted.
- No title, author, branch, description, review or CI state reaches Firestore. A Topic made from a pull request carries the title as its own name, like a Topic made from a ticket.

### What stays on the device

- Each opened pull request is saved in IndexedDB `pullRequests` (by `owner/repo#number`) with its reviews and CI summary. No diff or file content is read.
- The "Mine" and "Review requested" rows are saved in `prLists`.
- Disconnect hides lists and copies until a token is saved again. "Delete saved copies" removes Jira and GitHub copies from this device; the connections stay.

### Reading

- Lists: one search request each, 50 rows, newest first. The page says when GitHub has more ("Showing 50 of N").
- Thread keeps to 10 search requests a minute (GitHub allows 30 for the whole token), so other tools using the same token keep their share. Past that, the list shows its saved copy and says GitHub is limiting requests until the minute is over.
- Rows show CI and review state only for pull requests opened on this device, because one list request cannot carry them (ADR-007). Other rows say "Open to load CI and reviews".
- A pull request refreshes when opened and then every 5 minutes while the page is visible and online, like Jira.
- Offline or rate limited: the saved copy is shown with its age; no request is made. 401 keeps copies readable and asks to reconnect. 403 says GitHub refused; 403 or 404 on statuses or check runs only marks that CI part unreadable ("CI sources"). 404 on the pull request forgets the saved copy. Search and core each have their own rate-limit gate.

### Screens

- Pull requests page: "Mine" and "Review requested" tabs with counts. At 1000px and wider the pull request shows next to the list (the first one when none is picked), narrower one at a time with a back link.
- `#/item/pr/owner/repo/number`: the pull request as a page, with a back link to the Topic it came from.
- Detail: state pill (Open, or Draft), repository, head branch, title and number, CI and review indicators, Topic chips, Link to Topic (several Topics), Create Topic from pull request, Open in GitHub (hidden for SYNTHETIC data), description as plain text, reviewers (people and teams), and fields (repository, author, branch, updated, CI sources). No comments.
- Topic details: the Pull requests section lists linked pull requests. At 1000px and wider one opens in the side panel; narrower, as its own page.
- Today: the "Review requested" column lists the saved list, then GitHub's answer.
- ⌘K adds "Pull requests (downloaded)" for pull requests saved on this device (title, number or repository).
- Settings > Connections: a token form with one field (a fine-grained, read-only personal access token). The token field is never filled back in. Disconnect asks first.
- Trash names a returning pull request link as `owner/repo#number`, with its title when saved here.

### Synthetic mode

- The emulator test hook switches a tab to SYNTHETIC providers. GitHub then says "GitHub (synthetic)", uses fixture answers and an in-memory connection, and `threadTest.setGitHubScenario(name)` turns every answer into one failure kind. Synthetic values never go to real GitHub.

## Consequences

- Topic links survive token expiry, disconnect and Trash. Another device shows `#number` and "Not on this device yet" until it opens the pull request or loads a list.
- Opening Today or the Pull requests page costs one or two search requests. Moving between them quickly can reach Thread's 10-a-minute share; the saved copies then show with the limit message.
- Live behaviour (real search answers, real rate limits, fine-grained token permissions for statuses and checks) is not verified. Phase 13 checks it with the owner's own token.

## Alternatives considered

- CI and review state in every list row: needs GraphQL or four core requests per row. Kept for later (ADR-007).
- Comments in the detail, as in v3: one more request per pull request and more content on the device. Left out of v1 for now (queued).
- A GitHub sign-in (OAuth or a GitHub App) instead of a token form: needs a registered app and a callback host. Kept for later (Q3).
