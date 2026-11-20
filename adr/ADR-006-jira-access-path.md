# ADR-006: Jira access path

Status: Proposed (2026-11-13). Written in Phase 3 (M1-C). Checked on synthetic fixtures, then live at gate P3-G2: J1 passed. Evidence: `adr/M1-RESULTS.md`, "M1-C" and "M1-C live".

Phase 4 review (2026-11-13, owner away): checked for consistency with the other ADRs. Still Proposed; the owner review is queued. Phase 10 builds on it: direct token, typed Cloud ID, no proxy.

Update 2026-11-17 (D74, D75): Jira text maps onto the document kinds of D57 plus tables, panels, expands and status lozenges ("Rich text" below). Saved copies no longer keep mention account ids. The ticket request asks four more fields for attachments, sub-tasks, fix versions and the due date. No new request, host or CSP entry.

## Context

- Thread reads one Jira Cloud site (D07). Jira is read-only in v1.
- Descriptions and comments stay "API plus a device-local cache". They never go to Firestore.
- There is no app server, and no Blaze billing, so the browser has to call Jira itself.
- The owner uses a scoped, read-only API token (O5).
- Atlassian documents that a scoped token works only through the `api.atlassian.com` gateway, with basic auth. On the site URL it gets 401.
- Whether the gateway allows a browser call with that token (CORS) is not documented. Community reports said the preflight fails.
- **J1 result (live, P3-G2):** it works. From `http://127.0.0.1:8780` in Chrome 157, `/field`, `/issue/{key}` and `/issue/{key}/comment` answered 200 with Basic auth, and 404 answers were readable too. The kind of token the owner made was not reported; the instructions asked for a scoped, read-only token.

## Decision

### Requests

| Item | Choice |
|---|---|
| Base URL | `https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3` |
| Auth | `Authorization: Basic base64(email:token)`. The token leaves `SecretText` only for this header. |
| Headers | `Accept` and `Authorization` only, so the preflight stays small |
| Methods | `GET` only. No Jira write exists in the code. |
| Browser options | `mode: cors`, `credentials: omit`, `cache: no-store` (saved copies live only in IndexedDB), `referrerPolicy: no-referrer`, 20 s timeout |
| Scopes asked of the owner | Classic `read:jira-work` and `read:jira-user`, with a short expiry. Since D103 part B also the granular `read:board-scope.admin:jira-software` and `read:project:jira`, for a board's own columns only; without them the board shows one column per status |

One refresh of a ticket sends up to three requests:

1. `GET /field`, at most once a day per cloud id. It finds the Sprint field by its schema type, because the field id differs per site. A failed `/field` only hides the sprint, unless it shows that no request can work now (no answer, 401 or rate limit).
2. `GET /issue/{key}?fields=summary,issuetype,status,priority,assignee,reporter,labels,updated,description,issuelinks,parent,attachment,subtasks,fixVersions,duedate` plus the Sprint field. The last four came with D75 in the same request.
3. `GET /issue/{key}/comment?orderBy=-created&maxResults=100`. The newest 100 comments are shown with "Showing the newest N of M" when there are more.

The Filters tab (D103) adds two GETs under the same base, headers and options, both covered by `read:jira-work`, so no new scope: `GET /filter/favourite` (the starred filters) and, only when a filter's JQL is not saved yet, `GET /filter/{id}`. The filter's tickets come from the enhanced JQL search with the filter's JQL as Jira stored it. A 400 on that search means the JQL cannot run; it is shown on the Filters tab and does not change the connection state.

The Board tab (D103 part B) adds one GET on the same gateway, headers and options, under the Jira Software REST base `https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0`: `GET /board/{boardId}/configuration`, a board's columns, limits and estimate field, at most once a day per board. It needs the two granular scopes above. Its 401 or 403 is kept apart from the token's state: the board shows one column per status with a note, and Thread's other Jira views are not touched. Only a rate limit on it stops the board. The board's tickets and the person's active sprints come from the enhanced JQL search (`read:jira-work`). `GET /field` also finds the site's story points field, in the same daily request. No new host or CSP entry.

### Cloud id

- The person types the site and the cloud id into the spike form.
- "Look up" tries `https://{site}/_edge/tenant_info` without a token. This endpoint is from an Atlassian support article, not the REST reference. The browser may block it (CORS). If it does, the form explains how to open that URL by hand.
- Live (P3-G2): "Look up" got no answer from the owner's site, which is how a CORS block shows. Typing the Cloud ID by hand worked.

### Answers and problems

| Answer | Shown as | Effect |
|---|---|---|
| 200 | Fresh copy, "Updated HH:MM" | Saved on this device |
| 401 | "Jira access expired", "Update the token" | Saved copy stays readable |
| 403 | "Jira refused access" | Saved copy stays |
| 404 | "Ticket not found" | The saved copy is deleted. Jira also answers 404 when the token may not see the ticket, so lost access removes the copy too. |
| 429 | "Jira is limiting requests. Next refresh in about N minutes." | No request until `Retry-After` (1 s to 1 h), else `X-RateLimit-Reset`, else 60 s |
| No answer while online | "Jira did not answer. The network failed, or the browser blocked the request." | This is how a CORS block shows |
| No answer while offline, or offline before the request | "Offline" with the saved copy, or "Not saved on this device" | No request is sent while offline |
| Anything else | "Refresh failed (http-N)" | Saved copy stays |

`ExternalLoader` (domain) does this for Jira and GitHub alike: saved copy first, then refresh, then the answer. A `RateLimitGate` per provider limit keeps requests back until the limit ends. While Jira is not connected, saved copies stay hidden (v3).

### Rich text (P3-G1 decision 3, revised by D74)

- ADF becomes `ExternalDocument`, the read-only document model pull request descriptions also use (D57). It no longer reuses the Notes' RichText v1 blocks; the Note schema does not change.
- Paragraphs, headings 1 to 6 (each keeps its level), bullet and ordered lists (an ordered list keeps its start), quotes, rules and code blocks (with a language name that passes a short safe pattern) map to the D57 kinds. A list item holds any block, so a code block or a table in a list stays itself.
- Task lists keep their checked state; a block task item keeps all its blocks, and a nested task list goes under the item before it. Decisions are list items marked as decisions.
- Jira-only kinds: a table whose cells hold blocks, with header cells, column and row spans and Jira's row numbers; a panel with its tone (info, note, success or tip, warning, error, else none); an expand with its title (nested expands too). Layout columns show their content one after another.
- Bold, italic, strikethrough, inline code and links are kept. Links pass `LinkHref.parse` (`https`, `http`, `mailto`); any other link becomes plain text. Underline, text and background colors and sub/superscript keep their text and lose the style. Table cell colors are not kept.
- A status lozenge keeps its text and one of Jira's six colors (neutral, purple, blue, red, yellow, green); an empty one is left out. Mentions, emoji, dates and inline cards become text, or a link when the card URL passes the check.
- Pictures and files in the text, app content (extensions), sync blocks and unknown nodes become the v3 placeholder, named by the file's alt text when there is one, for example `Image “screen.png” is not supported here`. Inline pictures need a live check first (D74).
- Limits: tables are cut at 100 rows and 20 columns with a "The rest of this table" placeholder; spans are clamped to the table; deeper than 20 levels shows "Deeply nested content"; an expand title is cut at 120 characters.
- Rendering uses Compose DOM nodes only. No HTML string from Jira is ever inserted. Text such as `<b>` stays text.

### Saved copies

- IndexedDB database `thread-external-cache`, stores `jiraIssues` (by issue key) and `jiraMeta` (Sprint and story points field ids, lists, rows and, since D103, `filters:starred`, `filter:<id>`, `sprints:mine`, `sprint:<id>` and `boardConfig:<boardId>`). A starred filter keeps only its id (digits), name and JQL: no owner, account ids, share permissions or links.
- A record is `{recordVersion: 1, savedAtMillis, scope: site host, data}`. `data` is the answer cut down to what Thread shows: no account ids, e-mails or avatars. A copy saved for another site is not shown.
- Since D74 the description and comment bodies are saved without mention account ids (`AdfMapper.forStorage` removes `attrs.id` from mention nodes); the mention's text stays. Copies saved before D74 keep those ids until the ticket refreshes or "Delete saved copies" removes them.
- Since D75 an attachment keeps only its id (digits only), file name, size, type and date: not its author or its file and thumbnail links. Sub-tasks keep key, title, status and type; fix versions their names; the due date as Jira gives it.
- Fresh answers and saved copies go through the same reader, so a better mapper also improves old copies.
- Fixture mode uses its own database, `thread-external-cache-synthetic`, so synthetic data never mixes with real copies.
- Nothing goes to Firestore. The worker (ADR-008) never sees these requests, because they are cross-origin.

## Consequences

- J1 passed, so no proxy or server is needed for read-only Jira, and gate P3-G3 is skipped. If Atlassian changes the gateway's CORS behavior later, a proxy would change only the base URL in `JiraClient`.
- In practice the Cloud ID has to be typed by hand, because "Look up" got no answer in the browser. Phase 4 Settings (O9) should plan for that.
- Jira's `x-ratelimit-limit` and `-remaining` are readable on normal answers and differ by endpoint (350 or 400 in the live run). Thread does not use them; it reacts only to 429.
- Only the newest 100 comments. Paging is not built.
- The token sits in IndexedDB, readable by any script on the page (ADR-008).
- No JQL lists, no Topic links and no background refresh in this phase.

## Not verified

- Live 401, 403 and 429 answers, and whether `Retry-After` and `X-RateLimit-Reset` are readable on a 429. Checked on fixtures only.
- Which token kind and scopes the owner used. The scope set in this ADR is the documented one; the live run does not prove it is the smallest that works.
- Whether real ADF from the owner's site rendered correctly. The live ticket loaded, but its look was not reported. The fixture covers the documented nodes plus task lists, cards and an unknown node.
