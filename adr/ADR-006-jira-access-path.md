# ADR-006: Jira access path

Status: Proposed (2026-11-13). Written in Phase 3 (M1-C). Checked on synthetic fixtures, then live at gate P3-G2: J1 passed. Evidence: `adr/M1-RESULTS.md`, "M1-C" and "M1-C live".

Phase 4 review (2026-11-13, owner away): checked for consistency with the other ADRs. Still Proposed; the owner review is queued. Phase 10 builds on it: direct token, typed Cloud ID, no proxy.

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
| Scopes asked of the owner | Classic `read:jira-work` and `read:jira-user`, with a short expiry |

One refresh of a ticket sends up to three requests:

1. `GET /field`, at most once a day per cloud id. It finds the Sprint field by its schema type, because the field id differs per site. A failed `/field` only hides the sprint, unless it shows that no request can work now (no answer, 401 or rate limit).
2. `GET /issue/{key}?fields=summary,issuetype,status,priority,assignee,reporter,labels,updated,description` plus the Sprint field.
3. `GET /issue/{key}/comment?orderBy=-created&maxResults=100`. The newest 100 comments are shown with "Showing the newest N of M" when there are more.

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

### Rich text (P3-G1 decision 3)

- ADF becomes `ExternalDocument`: RichText v1 blocks (ADR-003) plus `Code` and `Unsupported(kind)`. The Note schema does not change.
- Paragraphs, headings (levels 4 to 6 become 3), bullet, ordered and task lists map to RichText v1. List items hold RichText blocks only, so a code block inside a list item becomes a paragraph with line breaks.
- Bold, italic and links are kept. Links pass `LinkHref.parse` (`https`, `http`, `mailto`); any other link becomes plain text. Other marks keep their text and lose the style.
- Mentions, emoji, dates, status lozenges and inline cards become text, or a link when the card URL passes the check.
- Quotes, panels, layouts and expands show their content. Code blocks stay code.
- Tables, media, extensions and unknown nodes become the v3 placeholder, for example "Table is not shown here."
- Rendering uses Compose DOM nodes only. No HTML string from Jira is ever inserted. Text such as `<b>` stays text.

### Saved copies

- IndexedDB database `thread-external-cache`, stores `jiraIssues` (by issue key) and `jiraMeta` (Sprint field id).
- A record is `{recordVersion: 1, savedAtMillis, scope: site host, data}`. `data` is the answer cut down to what Thread shows: no account ids, e-mails or avatars. A copy saved for another site is not shown.
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
