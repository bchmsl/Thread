# ADR-007: GitHub access and cache policy

Status: Proposed (2026-11-13). Written in Phase 3 (M1-C). Checked on synthetic fixtures, then live at gate P3-G2: G1 to G3 passed. Evidence: `adr/M1-RESULTS.md`, "M1-C" and "M1-C live".

Phase 4 review (2026-11-13, owner away): checked for consistency with the other ADRs. Still Proposed; the owner review is queued. Phase 11 builds on it, with `review-requested:@me` as the review query.

Update 2026-11-16 (D57): the description is shown as formatted text, parsed from its GitHub Markdown. See "Description" below. The rest is unchanged.

Update 2026-11-16 (D67): pull request rows refresh when they show. See "Cache policy" below.

## Context

- GitHub is read-only in v1. The v3 design shows two lists, "Mine" and "Review requested", and one pull request with its reviews and CI state.
- There is no app server. The owner uses a fine-grained personal access token (O6).
- GitHub documents CORS support for its REST API. Its CORS page lists these readable headers: `ETag`, `Link`, `x-ratelimit-limit`, `-remaining`, `-reset`, `X-OAuth-Scopes`, `X-Accepted-OAuth-Scopes` and `X-Poll-Interval`.
- Live (P3-G2): the page could also read `x-ratelimit-used` and `x-ratelimit-resource`. It could not read `X-Accepted-GitHub-Permissions` or `X-GitHub-SSO` (not exposed, or not sent). `retry-after` did not come up, because no limit was hit.
- Fine-grained permissions are documented for pull requests and commit statuses. No fine-grained permission for check runs is documented.
- Live (P3-G2): search, pull request, reviews, combined status and check runs all answered 200 with the owner's token. Why check runs worked is not determined: the log cannot show the token kind, its permissions or the repository's visibility (M1-RESULTS, "Why check runs answered 200").

## Decision

### Requests

| Item | Choice |
|---|---|
| Base URL | `https://api.github.com` |
| Auth | `Authorization: Bearer <token>`. The token leaves `SecretText` only for this header. |
| Headers | `Accept: application/vnd.github+json` and `Authorization` only |
| API version | No `X-GitHub-Api-Version` header. GitHub then uses `2022-11-28`, which is supported until 2028-03-10. The newest version is `2026-03-10`. Adding the header later is a one-line change, at the cost of one more preflight header. |
| Methods | `GET` only. No GitHub write exists in the code. |
| Browser options | As for Jira (ADR-006): `cors`, no cookies, `no-store`, no referrer, 20 s timeout |
| Permissions asked of the owner | Repository access to the repos that matter. Metadata (read, required), Pull requests (read), Commit statuses (read), and Checks (read) if GitHub offers it. |

| Screen | Requests per refresh |
|---|---|
| Mine | `GET /search/issues?q=is:pr is:open author:@me archived:false&sort=updated&order=desc&per_page=50` |
| Review requested | Same with `review-requested:@me` |
| One pull request | `GET /repos/{o}/{r}/pulls/{n}`, `…/pulls/{n}/reviews?per_page=100`, `…/commits/{sha}/status?per_page=100`, `…/commits/{sha}/check-runs?per_page=100` |
| The viewer (D62) | `GET /user`, with a list refresh, only while the token's own login is not saved yet. It counts against the core limit. |

- The list rows link to `owner/repo#number`. Owner and repo names must match `A-Z a-z 0-9 _ . -`, and the head SHA must be a full SHA, because both go into request paths.
- **Assignees and the viewer (D62):** list rows keep each assignee's `login` and numeric `id` from the search answer. A login must match `A-Z a-z 0-9 _ . - [ ]` (apps end in `[bot]`), or that person is left out. The avatar address is built from the id, `https://avatars.githubusercontent.com/u/{id}?s=40&v=4`; Thread never uses `avatar_url` or any other address from an answer, so no other image host can appear (ADR-015). The 40 px size stays sharp at 20 px on a 2x screen. The viewer's login from `GET /user` passes the same check and is saved with the connection (ADR-008), not in the cache. A failed or odd answer leaves it unknown: the list still loads, rows show the login instead of "You", and the next refresh asks again. A token changed while GitHub answered keeps that answer out of the store.
- When the token cannot read statuses or check runs (403 or 404), the pull request still loads. "CI sources" then says which part was not readable, and the CI state comes from the readable part only (G3).

### Indicators (pure domain rules, `PullRequestStates`)

- **Reviewers:** one entry per person. The person's newest approval, change request or dismissal counts; a later comment does not undo it. A person whose review is requested again shows "Review requested". Requested teams are listed by name. A pending review is the viewer's own unsent draft and does not count.
- **Review indicator:** any change request, else any approval, else any open request, else "No reviews yet".
- **CI:** any failure wins, then anything still running, then success. No status and no check run gives "No checks". Check runs count as failing when they end `failure`, `timed_out`, `cancelled`, `action_required` or `startup_failure`; other ends, such as `neutral` or `skipped`, are neutral.
- **Description (D57):** GitHub Markdown, parsed in shared Kotlin (`GitHubMarkdown`, `shared/data` commonMain) into the read-only `ExternalDocument` blocks and drawn as DOM elements by `ExternalDocumentView`. Thread does not ask GitHub for rendered HTML, and no HTML from GitHub is inserted. Owner's words: "Also pull requests should be rendered as formatted text as it is on Github pull request". Replaces the earlier rule "plain text, split into paragraphs on blank lines".
  - Covers CommonMark plus GitHub's tables, task lists (read-only checkboxes), strikethrough, bare `https://` and `www.` links, `#123` (an issue or pull request in the same repository) and `@name` (the GitHub account). A team (`@org/team`) and an email address stay text.
  - A newline inside a paragraph is a line break, as GitHub shows pull request descriptions.
  - Images become a link "Image: <alt>" that opens the image, because the CSP loads images only from Thread itself (and, since D62, GitHub's avatar host, which description images do not use).
  - Raw HTML stays visible text, except three things GitHub never shows as text: comments are hidden, `<br>` is a line break and `<img>` becomes an image link. `<details>`, `<summary>`, `<kbd>` and other tags show as typed.
  - Every link passes the Notes link rule (`https`, `http`, `mailto`, no spaces or control characters) or stays plain text. Links open in a new tab with `rel="noopener noreferrer"` (D46).
  - Caps against hostile text: 65,536 characters (GitHub's own limit), 2,000 blocks, tables of 100 rows by 20 columns, 16 levels of nesting. The screen says what it did not show ("The rest of this description", "The rest of this table", "Deeply nested content"). A wide table or code block scrolls inside its own box, and quotes and lists stop indenting after four levels, so the page never scrolls sideways, even at 390 px.
  - Headings stay one step under the screen's own "Description" title, so a `## Summary` reads as part of the description.
  - The device cache keeps the Markdown source, and parsing runs on each read, so a saved pull request shows the same way offline. The Jira key search (D49) still reads the source.
  - Footnotes, emoji shortcodes (`:tada:`), math and diagrams are not converted; they show as typed.

### Problems and rate limits

| Answer | Shown as |
|---|---|
| 401 | "GitHub access expired", "Update the token" |
| 403 or 429 with `x-ratelimit-remaining: 0` | "GitHub is limiting requests". No request until `x-ratelimit-reset`. |
| 403 or 429 with `retry-after` | Same, for that many seconds. Browsers may not expose this header. |
| 429, or 403 with "rate limit" in the body, without those headers | Same, for 60 s (secondary limit) |
| Other 403 | "GitHub refused access" |
| 404 | "Pull request not found"; the saved copy is deleted |
| No answer, offline, other statuses | As for Jira (ADR-006) |

- Search has its own limit (30 requests a minute) and the other calls share the core limit, so each has its own `RateLimitGate`. The live answers confirmed the split: `x-ratelimit-resource` was `search` or `core`.
- Lists and details use the same `ExternalLoader` as Jira: saved copy first, then refresh.

### Cache policy

- IndexedDB `thread-external-cache`, stores `prLists` (`mine`, `review-requested`) and `pullRequests` (by `owner/repo#number`). Fixture mode uses `thread-external-cache-synthetic`.
- A record is `{recordVersion: 1, savedAtMillis, scope: "github", data}`. `data` keeps only what Thread shows.
- A list row in `data` keeps `number`, `title`, `user.login`, `draft`, `updated_at`, `repository_url` and, since D62, `assignees: [{login, id}]` (always an array; GitHub's `null` is saved as none). A list saved before D62 has no `assignees` key: its rows show no assignees until the next refresh. The record version stays 1, because reading the old shape is still correct.
- A copy is refreshed when it is opened, every 5 minutes while it stays open (ADR-014), or with Refresh. The two lists also refresh in the background (D51).
- **Refresh on show (D67):** a pull request that shows as a row with its CI and reviews refreshes when the row shows, with the four requests of "One pull request": a Topic's linked pull requests, and on the Pull requests page and Today's rail the rows of a pull request opened before. A pull request asked less than a minute ago is not asked again, one on its way is not asked twice, and rows ask one after another, at most 8 pull requests (32 core requests) a minute. The D51 guards apply: nothing goes out offline, hidden, with fixtures, after a 401 or inside a rate limit. A 404 or 403 shows "Not found or no access" on the row; the Topic link stays.
- There is no ETag revalidation yet.
- "Delete saved copies" clears all four cache stores. Disconnect hides the copies until GitHub is connected again (v3).
- Nothing goes to Firestore. The worker (ADR-008) never sees these requests.

## Consequences

- One pull request costs four core requests per refresh. That is fine for a person opening pull requests by hand. Since D67, rows refresh CI states with the same four requests, capped at 8 pull requests a minute and, in the lists, only for pull requests opened before. A full list of CI states would still need GraphQL or ETags.
- The four requests run one after another: 3.2 s in the live run. Reviews, status and check runs could run side by side after the pull request call. Not changed in this phase.
- The core limit belongs to the person, not to the token. In the live run, 732 of the 5,000 hourly requests were already used before Thread's first core request, by other use of the same account. Thread shows "GitHub is limiting requests" when that budget runs out.
- An organization that owns the repositories may need to approve the token. Until then the token sees public data only. Private repos then answer 404, which reads as "not found". P3-G2 records it if it happens.
- Search results are capped at 50 per list. "Showing 50 of N" says when there are more.

## Not verified

- Live 401, 403, 404 and rate-limit answers. Checked on fixtures only.
- Why check runs were readable (see Context), and so whether a fine-grained token without extra permissions can read them.
- Whether the lists held the right pull requests and the detail looked right. The log has no content, and the owner has not reported on it.
- A token waiting for organization approval, and paging (`Link`).
- D62, checked on SYNTHETIC fixtures only: `GET /user` with a fine-grained token (GitHub's docs did not confirm it for every token kind), real assignee answers, and real avatar images. If `GET /user` fails, rows show the login instead of "You"; nothing else breaks.
