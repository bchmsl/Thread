# ADR-013: Jira in the workspace

Status: Proposed (2026-11-13). Written in Phase 10 while the owner was away. Built on ADR-005 (rules), ADR-006 (Jira access path), ADR-008 (token storage), ADR-009 (one document per entity) and ADR-012 (Trash and search). Checked with the domain tests (`ExternalLinksTest`, `SearchTest`, `TrashTest`), the data tests (`JiraListRepositoryTest`, `JiraIssueRepositoryTest`), the emulator tests `FirestoreExternalLinksEmulatorTest` and `FirestoreExternalLinksRulesEmulatorTest`, and the Phase 10 browser checks on SYNTHETIC fixtures only. Nothing here was checked against real Jira. The owner review is queued (owner-review-queue.md, Q41 onwards).

## Context

- D08, D09: Jira is read-only in v1. Descriptions and comments stay on the device (API plus device-local cache). Firestore stores app-owned content only.
- Q6 (provisional): titles and other Jira payload never go to Firestore.
- ADR-006: Jira Cloud REST v3 with the owner's API token, read through the enhanced JQL search and the issue API.
- ADR-008: the token is stored in IndexedDB on this browser profile only. It is never synced.
- v3 prototype (D33): `jira.html`, `item.html`, the Topic Jira section and side panel, Settings Connections, ⌘K "Jira (downloaded)".

## Decision

### What Firestore holds

- `externalRefs/{provider~instance~key}`: `{provider, instance, key, url, updatedAt, schemaVersion: 1}`. For Jira, `instance` is the Cloud ID and `key` the issue key. One ticket is stored once, however many Topics link it.
- `topicExternalLinks/{topicId}_{refId}`: `{topicId, refId, linked, updatedAt, schemaVersion: 1}`. Unlink writes `linked: false`. Nothing is ever deleted, so an offline unlink and an offline link merge field by field.
- The rules allow exactly these fields and types. No title, status, description or comment reaches Firestore.
- A Topic in Trash hides its links (ADR-012). Restore brings them back. Delete forever of a Topic leaves the refs and links as they are; they are only ids.

### What stays on the device

- Each opened ticket is saved in IndexedDB `jiraIssues` with its fields, description and comments, tagged with the site host. Since D74 and D75 that copy also holds the attachment list (name, size, type, date; no file or thumbnail links), sub-tasks, fix versions and the due date, and its text keeps no mention account ids (ADR-006). No file is downloaded or saved.
- The "Assigned to me" rows are saved in `jiraMeta` under `list:assigned`, next to the Sprint field id. Search results are kept only in memory for the page load.
- Since D103 the Filters tab saves the starred filters in `jiraMeta` under `filters:starred` (id, name and JQL only) and each filter's rows under `filter:<id>`, scoped to the site like the other records. The filter this device showed last is kept in browser storage (`thread.jira.filter`, `.synthetic` with the fixtures). Filter names, JQL and rows never reach Firestore (owner answer Q4 of #33: kept on this device only).
- Disconnect hides lists and copies until the same site connects again. "Delete saved copies" removes them from this device; the connection stays.

### Reading

- Assigned to me: `assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC`, up to 2 pages of 50. The list says when it is not complete.
- Filters (D103): the starred filters from `GET /filter/favourite`, then the chosen filter's own JQL through the enhanced search, up to 2 pages of 50. A filter whose JQL is not saved yet is asked for with `GET /filter/{id}`. A 400 means Jira could not run that filter and stays on the Filters tab; it is not an access problem. The list loads on open (not again within a minute) and on Refresh; the filter's rows also every 5 minutes while the page is visible, and they update the shared rows (D67).
- Search: a typed key looks up that ticket (`key = "ABC-1"`); a 400 answer for a key means "no result". Other text becomes `text ~ "words"`, and a single word is also tried as a label. Lucene special characters and quotes are dropped, so typed text never reaches JQL unescaped. One page, never saved.
- Copies refresh when opened and then every 5 minutes while the page is visible and online.
- Rows refresh when they show (D67): a Topic's linked tickets and confirmed suggestions go in one search, `key in (...)` with the row fields only, up to 50 keys and 6 searches a minute, and a ticket asked less than a minute ago is skipped. The rows are saved in `jiraMeta` under `row:<KEY>`. A key Jira refuses (400) is found by splitting the search, shows "Not found or no access", and keeps its saved row.
- Offline or rate limited: the saved copy is shown with its age; no request is made. 401 keeps copies readable and asks to reconnect. 403 and 404 say so; 404 forgets the saved copy. One rate-limit gate covers tickets, lists and search.

### Screens

- Jira page: "Assigned to me", "Filters" (D103) and "Search" tabs, with the ticket next to the list at 1000px and wider, else on its own. The Filters tab's address is `#/jira?tab=filters&filter=ID`; without `filter=` it shows the filter this device showed last, else the first starred one.
- From outside the Jira tab a ticket opens as a card on top of the screen (`?jira=KEY`, D70), with "Open full page" to the Jira tab. The old `#/item/jira/KEY` page is gone; its links open the ticket in the Jira tab.
- Topic details: the Jira section lists linked tickets. A ticket opens as a card on top of the Topic (D70; before D70, a side panel at 1000px and wider).
- A ticket's parts (D68, D69): Description, Attachments and Sub-tasks (D75, shown only when the ticket has them), Comments and Related issues on the left; Details (Due and Fix versions since D75) and Topics on the right. An attachment's name opens the file on the Jira site in a new tab; a sub-task opens as the next card, like a related ticket.
- A ticket can be linked to several Topics ("Link to Topic"), and a Topic can be created from a ticket ("Create Topic from ticket"). The Topic name is the ticket title cut to the Topic name limit, and its "where I left off" says "Created from KEY.".
- ⌘K adds "Jira (downloaded)" for tickets saved on this device (key or title). When nothing is found, it links to Jira search.
- Settings > Connections: a token form (site, Cloud ID, e-mail, API token). The token field is never filled back in. GitHub shows its status only; its form is Phase 11.

### Synthetic mode

- The emulator test hook (`threadTest.useSyntheticProviders(true)`) swaps in SYNTHETIC providers for the session. The page and Settings say "Jira (synthetic)". Synthetic values never go to real Atlassian.

## Consequences

- Topic links survive token expiry, disconnect and Trash. Only ids are synced, so another device shows the key and the "Not on this device yet" badge until it opens the ticket.
- A ticket from another Jira site shows its key only.
- Live behaviour (real JQL answers, real rate limits, the real Cloud ID lookup) is not verified. Phase 13 checks it with the owner's own token.

## Alternatives considered

- Store titles in Firestore for cross-device lists: rejected by Q6 (provisional) and D09.
- Delete link documents on unlink: rejected, it would let an older offline link and unlink race without a clear winner.
- An OAuth 3LO sign-in instead of a token form: kept for later (ADR-006). It needs an Atlassian app registration and a callback host.
