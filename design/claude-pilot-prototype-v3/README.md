# Thread pilot prototype, version 3

A clickable design prototype for the Thread pilot. Plain HTML, CSS and JS.
All data is synthetic and saved only in this browser.

Status: the owner chose v3 on 2026-11-13. It is the approved design reference for all pilot surfaces
(D33 in `06-roadmap-decisions.md`). It replaces the v0.5 baseline.

## Run

- Open `index.html` in a browser. No build, install or network is needed.
- Or serve the folder and open `http://127.0.0.1:8773/`:

```bash
python3 -m http.server 8773 --bind 127.0.0.1
```

- Every page shares one demo state in `localStorage` (key `thread-v3-demo`).
- Reset it from `index.html` (Reset demo data) or Settings (Reset example data, Start empty).
- The demo clock is fixed at Fri 13 Nov 2026 13:05. Today has a control to advance it to the next reminder.

## Concept

Thread v3 works like a code editor. Every view shares one bottom status line:

```
[hex Demo] [Today Inbox Topics Notes Jira PRs] [Task v | Capture a task | -> Inbox] [Search ⌘K] [Focus] [sync] [Trash] [Settings]
```

- Capture lives in the line, so it is one keystroke (`C`) from any page.
- Topics are hex stickers with a two-letter monogram in their pastel color.
- Status categories use a pattern swatch plus a color, so meaning never depends on color alone:
  dashed outline = Planned (gray), solid = Active (blue), stripes = Paused (orange), check = Done (green).
- Futura for display, system UI for text. One magenta pill per surface marks the main action.
- Dark only. The Notes editor sits on a faint dot grid, like a notebook page.

## Screens

| # | Screen | Entry |
|---|---|---|
| 1 | Sign-in / first run | `index.html` (sign out to see sign-in, choose Empty workspace for first-run states) |
| 2 | Today | `today.html` |
| 3 | Topics List | `topics.html?view=list` |
| 4 | Topics Board | `topics.html?view=board` |
| 5 | Topic Details, Move to Trash | `topic.html?id=t-freeze` |
| 6 | Notes list, editor, save/sync, conflict compare | `notes.html`, `notes.html?id=n1`, `notes.html?id=n1&demo=conflict` |
| 7 | Inbox | `inbox.html` |
| 8 | Quick Capture | `today.html?capture=1`, or press `C` on any page |
| 9 | Task details and Reminders | `today.html?task=k2` |
| 10 | Jira list and detail | `jira.html`, `jira.html?tab=search&q=freeze`, `item.html?kind=jira&id=KITE-412&from=topic:t-freeze` |
| 11 | Pull Requests list and detail | `prs.html`, `prs.html?tab=review` |
| 12 | Global search | `today.html?search=1`, or ⌘K / Ctrl+K anywhere |
| 13 | Settings and status editor | `settings.html`, `settings.html#statuses` |
| 14 | Trash, including deleted Topics | `trash.html` |
| 15 | Focus timer (post-pilot concept, 25/5) | `today.html?focus=1`, or Focus in the status line |

`index.html` has all 21 links.

## Files

```
index.html today.html inbox.html topics.html topic.html notes.html
jira.html prs.html item.html settings.html trash.html
assets/tokens.css   design tokens (color, type, space, radius)
assets/app.css      components and layouts
assets/icons.js     inline SVG icon set
assets/data.js      synthetic example data
assets/store.js     shared state, actions, persistence
assets/shell.js     status line, dialogs, search, task sheet, focus, toasts
assets/external.js  Jira and PR list/detail shared by jira, prs and item pages
assets/pages/*.js   one script per page
```

## Responsive behavior

| Width | Status line |
|---|---|
| 1500 px and up | Full labels |
| 1200 to 1499 | Current view keeps its label, other views are icons |
| 1100 to 1199 | Views and tools are icons |
| 900 to 1099 | Icons plus a compact capture field |
| Under 900 | Current view button opens a views sheet, Inbox count is a corner badge |
| 599 and below | Search results and the status legend stack |
| 479 and below | The views-button chevron is hidden |

## Owner decisions (2026-11-13)

The owner reviewed the v3 proposals on 2026-11-13. The decision IDs are in `06-roadmap-decisions.md`.

| Decision | What it means |
|---|---|
| D33 | v3 is the approved design reference for all pilot surfaces. D32 (v0.5) is superseded. |
| D34 | Pilot scope: read-only Jira and GitHub are in the pilot. The Focus timer comes after the pilot and stays in v1 (D25). |
| D35 | Bottom status line replaces the sidebar. Navigation, capture, search, focus and sync share one line on every page. |
| D36 | Hex-sticker monogram is the Topic identity mark, in the Topic's pastel color. |
| D37 | Today groups: Overdue, Today, Earlier planned, plus a side column for Reminders and Review requested. |
| D38 | A task added from Today without a Topic is planned for today and also waits in Inbox until it is filed. |
| D39 | New note outside a Topic lands in Inbox, untriaged. A note made inside a Topic is linked to that Topic. |
| D40 | ⌘K is global search, also inside the editor. Adding a link in a note uses the toolbar button. |
| D41 | Note conflicts: the version you do not keep goes to Trash. "Keep both" saves it as a second note with the same Topics. |
| D42 | Topics can be deleted in the pilot. See "Topic deletion" below. |
| D43 | Move to Trash is instant with Undo, no confirm dialog. Delete forever still asks. |
| D44 | Week start setting removed from Settings. No pilot screen uses a week view. |
| D45 | Focus timer opens as a small panel from the status line, with no dashboard block. Post-pilot. |
| D46 | "Open in Jira" and "Open in GitHub" open the original page in a new tab. The demo shows a toast instead. |

### Topic deletion (D42)

- Topic Details has **Move to Trash**. It is instant, with an Undo toast.
- The Topic's tasks and its reminders, including reminders on those tasks, go to Trash with it.
- Notes are never deleted with a Topic. Only the link is removed.
  A note linked only to this Topic becomes standalone. It does not go to Inbox.
- Jira and PR links are removed. The tickets and pull requests are not changed.
- Trash shows the Topic with what went with it and the links that come back.
- Restore brings back the Topic, its tasks and reminders, and its links to notes, Jira and PRs, in their old order.
- Delete forever asks first. Notes, tickets and pull requests are not affected.
- The Topics List and Board have no per-row or per-card menu, so the action is only on Topic Details.
  Adding a menu there would be a new pattern.
- Trash retention is still open.

### Future ideas (not planned)

These are ideas the owner wants to keep. They are not part of the pilot or v1.

- A Settings toggle to put navigation in the bottom status line or in a sidebar.
- An optional icon instead of the monogram, if the user chooses one.
- The pilot scope may be revisited later.

## Verification

### Ran in a real browser (Chrome, local server on 127.0.0.1:8773)

Width checks used a same-origin iframe at exact sizes, because the browser window was in full screen.

| Check | Width | Result |
|---|---|---|
| J1 capture, triage from Inbox, file to a Topic, complete the task | 390 and full desktop | Pass |
| J2 open a note, format text, inspect offline and conflict states | 1440 | Pass |
| J3 Jira and PR search, open detail, link to a Topic, see it on the Topic | 1440 | Pass |
| J4 change a status, delete one with a replacement, Undo, see Board and Today update | 1440 | Pass |
| Board card Move to menu, then Undo | 1440 | Pass |
| J5 move a sample note to Trash and restore it | 390 | Pass |
| J6 search, then go to the result | 1440 | Pass |
| Narrow views sheet opens and navigates | 390 | Pass |
| All 21 index links load, shared script runs, page renders, no horizontal overflow | 390, 768, 1024, 1280, 1440 | Pass (105 loads) |
| Keyboard: ⌘K, type, arrow, Enter opens the result; Esc closes; skip link to the status line; visible focus ring | 1440 | Pass |
| `node --check` on every edited script | n/a | Pass |
| Static scan: no fetch, XHR, modules, CDN or web fonts | n/a | Pass |
| D39: New note from Notes lands in Inbox with no Topic, Inbox count goes up | 1440 and 390 | Pass |
| D39: New note from Topic Details is linked to that Topic and not in Inbox | 1440 | Pass |
| D42: Move to Trash on a Topic takes its 4 tasks and 2 reminders, unlinks 3 notes and 4 Jira/PR links, focus moves to the deleted-state heading | 1440 | Pass |
| D42: Undo and Restore Topic each bring the state back exactly, including link order | 1440 and 390 | Pass |
| D42: Trash shows the Topic, what went with it and the links that come back; its old items say "Its Topic is in Trash" | 1440 and 390 | Pass |
| D42: Delete forever asks first; Cancel keeps it; confirming removes a synthetic test Topic and its task, notes unchanged | 1440 | Pass |
| After the D39 and D42 changes: 13 pages load with no horizontal overflow and no script errors | 1440 and 390 | Pass (26 loads) |

### Not verified

- Opening the files directly with `file://`. All scripts are classic scripts and load by relative path, so it should work, but it was only tested through the local server.
- Safari and Firefox. Only Chrome was used.
- Screen reader output. No screen reader was used.
- Real touch input. Narrow widths were tested with mouse and keyboard.
- Board drag and drop. It uses HTML5 drag events, which the test tool's synthetic mouse does not fire. The Move to menu on each card was tested instead.
- Live Jira, GitHub and sync. Everything uses synthetic fixtures. Offline, conflict and connection states are switched with labeled Demo controls.

## Synthetic data

All people, Jira keys (`KITE-*`), repositories (`acme-demo/*`) and PR numbers are invented.
No real names, tickets, repositories or credentials are used. The demo sign-in never asks for a password or token.

## Design tools

| Tool | Used for |
|---|---|
| frontend-design skill | Visual direction, type and layout critique |
| ui-ux-pro-max and design-system skills | Token structure, accessibility and responsive checks |
| 21st MCP search and get_inspiration | Reference patterns, queried with generic UI terms only |
| Chrome browser | Screenshots, journeys and sweeps at desktop and narrow widths |

Not used: 21st-ui-explore, 21st-ai and Figma.

## Doc updates

The owner's decisions are recorded in `06-roadmap-decisions.md` (D33 to D46), `01-product-spec.md`,
`02-ux-design.md`, `04-data-and-sync.md`, `README.md`, `CLAUDE.md`, `07-claude-prompts.md` and `design/PILOT-DESIGN-PLAN.md`.
