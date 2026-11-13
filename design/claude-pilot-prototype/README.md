# Thread pilot prototype (Claude)

A clickable design prototype of the 15 Thread pilot surfaces. It is plain HTML, CSS and JavaScript. It has no dependencies, no build step and makes no network calls.

All data is synthetic and labeled "Demo": invented people, ticket keys (NOVA-…), repositories (orbit/…) and PR numbers. Sign-in asks for no password or token.

This is a design artifact, not the production app. The production stack stays Kotlin/JS + Compose HTML.

## Run it

Open `index.html` in a browser. It lists every surface, its simulated states and the six journeys.

If your browser blocks local files, serve the folder:

```bash
cd design/claude-pilot-prototype
python3 -m http.server 8765 --bind 127.0.0.1
```

Then open http://127.0.0.1:8765/index.html.

State is kept in `localStorage` under `thread-claude-pilot:v1`. If storage is blocked, the app keeps state in memory for the session. To start over, use **Reset demo data** on the index page, or Settings > Demo data. The demo clock is fixed at Thursday 12 November 2026, 14:10.

## Files

| File | Purpose |
|---|---|
| `index.html` | Prototype map: surfaces, state links, journeys, keyboard |
| `app.html` | The app shell. Hash routes such as `#/today`, `#/topic/t-qr`, `#/notes/n1` |
| `assets/thread.css` | Design tokens and all styles |
| `assets/icons.js` | Local SVG icon sprite |
| `assets/data.js` | Synthetic seed data and Jira/GitHub fixtures |
| `assets/core.js` | State, persistence, router, rendering, dialogs, toasts, shortcuts |
| `assets/components.js` | Shared pieces: rows, chips, sidebar, details panels |
| `assets/views.js` | One function per surface |
| `assets/interactions.js` | Event handlers, editor, search, timers, simulated states |

## Design direction

- **Dark shell, pastel Topics.** Graphite surfaces and a chalk primary button. Each Topic has one pastel color (lavender, mint, peach, sky, rose, butter). It shows as a thin "thread" rail on every Task, Note, ticket and card that belongs to it.
- **One primary action per surface.** For example: Add a Task on Today, Move to Topic in Inbox, Link to Topic on a ticket.
- **Notes feel like paper.** The editor uses a warm paper surface and a local serif stack, so writing looks different from lists.
- **Status by category.** Custom status names map to four stable categories: Planned, Active, Paused, Done. Glyph and color follow the category.
- **Local fonts only.** `system-ui` for the interface and `ui-serif`/Charter/Georgia for Notes. No web fonts.

## Verified in a browser

Chrome, served from 127.0.0.1. Desktop at 1440×900 and narrow at 390×844. The tool could not resize the Chrome window, so both widths ran in a fixed-size iframe.

| Journey | Result |
|---|---|
| Capture → triage → Topic → complete Task | Pass. `C` opens capture, Enter saves to Inbox, triage moves it to QR payments, the Topic lists it, ticking it completes it with an Undo toast. |
| Open a Note → format → offline/conflict | Pass. Heading and checklist tools work. Save goes Saving → Synced. Offline shows "Saved on this device" and keeps the edit after reload. Back online it syncs. The conflict comparison resolves, and the version you do not keep goes to Trash. |
| Jira search → detail → link → Topic | Pass. Search "receipt" finds NOVA-205. The detail loads, the picker links two Topics, and the Topic lists the ticket. |
| Change status → Board/Today | Pass. Moving a Topic to Active adds it to Today's Active Topics and the Active lane. Moving it to Paused removes it. Renaming and deleting a status (with a replacement) updates the Board. |
| Delete/restore a note | Pass. Move to Trash shows an Undo toast. Restore from Trash brings it back with an Open action. |
| Search → navigate | Pass. Cmd/Ctrl+K opens grouped results, arrows move, Enter opens the item, and Escape closes the palette (real key press). |

Also checked:

- A sweep of 41 route, state, dialog and popover links ran with no console errors or uncaught exceptions.
- No horizontal overflow at 390px on any of the 17 main routes.
- Network log shows only `127.0.0.1` files and the inline `data:` favicon.
- No `fetch`, XHR, WebSocket or module imports in the code.
- The focus timer counts down, and Pause and Stop appear.
- Each page sets its own tab title.

## Implemented but not verified in a browser

- Opening directly from `file://`. The browser tool could not open file URLs. The code uses classic scripts and relative paths only, so it should work. Use the local server if it does not.
- Board drag and drop. It uses HTML5 drag and drop with a mouse. Only the status menu on each card was tested, and touch devices use that menu.
- Reminder alarms after Demo controls > Clock > Advance 1 hour.
- Inbox arrow-key navigation and bulk actions beyond Select all.
- Screen reader output. Labels, roles and live regions are in the markup, but no screen reader was run.

## Visual only or simulated

- Sign-in, connect and disconnect are demo actions. No account, OAuth or token is involved.
- Jira and GitHub are fixtures. Refresh, loading, offline cache, access expired and rate limited are simulated with timers and the Demo controls panel.
- Note save and sync are simulated: device save after a short delay, then cloud sync. Failures come from Demo controls.
- Links inside Notes do not open. Cmd/Ctrl-click shows a "Demo only" toast.
- The focus timer is a post-pilot concept. It is a small popover from the sidebar, not a dashboard block.

## Decisions made in this prototype (need owner review)

These are proposals, not confirmed product decisions:

1. Today has an inline "Add a Task for today" field. Capture (`C`) is still the path for things to sort later.
2. A Task with a deadline today and no planned day appears under "Planned for today".
3. A sixth pastel, butter, was added to the Topic palette.
4. Each status category must keep at least one status, so the last one in a category cannot be deleted.
5. Disconnecting Jira or GitHub clears cached ticket text on the device but keeps the links on Topics.
6. When a note conflict is resolved, the version you do not keep moves to Trash instead of being discarded. "Keep both" makes a copy.
7. Trashing a Topic moves its Tasks and its single-Topic Notes to Trash. Notes shared with other Topics are only unlinked. Jira tickets and PRs are never deleted, only unlinked.
8. Opening a review-requested PR selects the Review requested tab.

## Tools used

- **UI UX Pro Max:** ran its design-system query and applied its priority rules (keyboard focus, touch targets, contrast, reduced motion).
- **21st:** searched the catalog and used one component (a formatting toolbar) as the accessibility reference for the Notes toolbar's roving focus. It assumes React, so it served as a reference only. The 21st CLI was not used.
- **frontend-design:** used for the design plan and critique: one memorable element (the Topic thread), restrained color, no template chrome.

No external code, fonts or icons were copied in. Icons are hand-made local SVGs.
