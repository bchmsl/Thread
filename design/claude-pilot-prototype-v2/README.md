# Thread pilot prototype, version 2

A clickable design prototype of the Thread pilot. Plain HTML, CSS and JavaScript. No build step, no dependencies, no network calls.

All data is synthetic demo data stored in one browser `localStorage` key (`thread-v2-prototype-state`). Jira, GitHub, sync, offline and conflict behavior are simulated and labeled as such.

## Run it

Option 1: open `index.html` directly in a browser.

Option 2 (the setup that was tested): serve the folder locally.

```bash
cd "design/claude-pilot-prototype-v2" && python3 -m http.server 8772 --bind 127.0.0.1
```

Then open `http://127.0.0.1:8772/index.html`.

`index.html` is the sign-in and first-run page. It also has a **Prototype map** with a direct link to every view, including deep links that open overlays:

| View | Link |
|---|---|
| Sign-in and first run | `index.html` |
| Today | `today.html` |
| Inbox | `inbox.html` |
| Quick capture | `today.html?open=capture` (or press `N` anywhere) |
| Topics list / board | `topics.html?view=list`, `topics.html?view=board` |
| Topic details | `topic.html?id=t-qr` |
| Notes editor / conflict | `notes.html?id=n-qr`, `notes.html?id=n-push` |
| Task details | `task.html?id=k-codes` |
| Reminders | `today.html?open=reminders` |
| Jira list / detail | `jira.html`, `detail.html?kind=jira&key=LARK-418` |
| Pull requests list / detail | `prs.html`, `detail.html?kind=pr&id=pr-1291` |
| Search | `today.html?open=search` (or `Cmd/Ctrl+K`, or `/`) |
| Settings | `settings.html` |
| Trash | `trash.html` |
| Focus timer (post-pilot concept) | `today.html?open=timer` |

To reset the demo, use **Settings > Demo account and data > Reset to the example workspace**, or clear site data for the page.

## Design concept: folder tabs

Thread is a set of topic folders. Each Topic is a pastel folder tab. Active topics sit as a row of tabs under the top bar, so the current work is always one click away.

- **Shell**: a deep plum dark shell (dark only). Top navigation with Today, Inbox, Topics, Notes, Jira and Pull requests. On narrow screens it becomes a bottom bar plus a More sheet.
- **Topic identity**: six pastels (lavender, mint, sky, peach, rose, sand) used as folder tabs and chips. Topic Details is one folder: the tab, then the body with collapsible sections.
- **Status**: a square "media" tile per category. Planned shows lines, Active shows play, Paused shows pause, Done shows a check. Category colors are gray, blue, orange and green.
- **Primary action**: one sunflower-yellow keycap button per surface, showing its keyboard shortcut. It is a different hue from the orange Paused status.
- **Today**: a large date numeral with a few counts, then Due, Planned today and Active topics, with an hour ruler and a "now" line driven by the demo clock.
- **Inbox**: a triage desk. Captures on the left, filing into topic tabs on the right.
- **Notes**: three panes. Index, a dark writing sheet with rich text (headings, bold, italic, lists, checklists, links), and a margin with a two-node device-to-cloud sync indicator plus the simulation controls.
- **Type**: system fonts only. Avenir Next (or Segoe UI) for the interface, DIN Condensed (or Bahnschrift) for numbers.

Key tokens live at the top of `assets/thread.css`.

## Files

```
index.html ... trash.html   one page per view, all share the same assets
assets/thread.css           tokens and all styles
assets/icons.js             inline SVG icon set
assets/store.js             state, seed data, selectors, sanitizer, time helpers
assets/shell.js             top bar, tab strip, navigation, dialogs, popovers, search, capture, reminders, timer
assets/views/*.js           one script per view
```

Scripts are classic scripts under `window.Thread`, so no module loader or server is required.

## Synthetic data

Every person, issue, repository and pull request is invented: Jira keys `LARK-*`, repositories `sample-labs/wallet-android` and `sample-labs/payments-sdk`, PR numbers such as #1291, and people such as Oskar Fenn, Mira Lund and Theo Basil. The demo sign-in never asks for a password or token.

## Proposals for owner review

These are design proposals, not confirmed decisions.

1. **Top navigation instead of a sidebar.** `02-ux-design.md` proposes a sidebar. This version uses a top bar plus the Active-topic tab strip to keep width for content.
2. **Today groups.** "Due today" is shown as its own group above "Planned today".
3. **Reminders** can be dismissed or snoozed from the bell popover.
4. **Deleting a Topic** moves it to Trash and hides its links. Restoring it brings the links back.
5. **Note conflicts.** "Keep both" makes a copy note. "Keep only one version" moves the other version to Trash, so nothing is lost.
6. **Statuses.** Every status category keeps at least one status. Deleting an in-use status requires choosing a replacement first.
7. **Paused topics and Today.** Tasks planned for today still show on Today even when their Topic is Paused. Only the Active topics row follows the status category. The spec does not say this either way.

## Verified vs visual only

Verified in Chrome on macOS, served from `127.0.0.1:8772`, at 1440x900 and 390x844, with no JavaScript errors recorded:

| Area | Result |
|---|---|
| Journey 1: capture, triage, file into Topic, complete Task | Passed |
| Journey 2: open Note, format, offline, local and remote failure, conflict (keep both, keep one) | Passed |
| Journey 3: search Jira/PR, open detail, link to Topic, see it on the Topic, unlink keeps the item | Passed |
| Journey 4: change a status category, Board and Today update; delete in-use status needs a replacement | Passed |
| Journey 5: delete a Note, restore it from Trash | Passed |
| Journey 6: `Cmd+K` search, grouped results, arrow keys and Enter, Escape closes | Passed |
| Note links: add on a selection, add at the caret, edit, remove, invalid address error, keyboard path, saved `href` survives reload | Passed |
| Jira simulation: cached-first refresh, offline, no cache, expired access, rate limit | Passed |
| Focus timer start and stop, reminder dismiss, task drawer, narrow More sheet, Board drag between lanes | Passed |
| All 19 Prototype map links load | Passed |
| No horizontal page overflow at 390 and 1440 wide, one `h1` per page | Passed |
| Static scan for `fetch`, XHR, WebSocket, `sendBeacon`, `@import` and remote URLs: none. The only matches are one inline `data:` SVG in CSS and the `https://` hint text in the link field | Passed |
| `node --check` on every script | Passed |

Built but not driven in the browser: opening via `file://`, Safari and Firefox, screen readers, touch input, reminder snooze, task date edits in the drawer, the PR simulation states (they share code with Jira), Topics List interactions, Topic section collapse, the keyboard shortcuts dialog, Empty trash, Start empty, Reset to the example workspace, Settings connect and disconnect, sign out, and reduced motion.

Notes on the evidence: `file://` should work because every script is a classic script and nothing is fetched, but that is an inference, not a test. Note links are stored with their `href`, and only `http` and `https` addresses are kept.
