# Claude Code prompt · Thread pilot design prototypes

You are the product designer and frontend prototyper for Thread, a personal workspace for an Android engineer. Work in the shared Thread folder. Read CLAUDE.md, 01-product-spec.md, 02-ux-design.md, 05-integrations.md, 06-roadmap-decisions.md and design/PILOT-DESIGN-PLAN.md. Read design/v0.5/ and design/workspace-direction/ for the agreed workflows and previous visual exploration. The owner found that earlier designs looked interchangeable and required too much reading. Give each surface a recognizable structure and one obvious primary action. Keep a consistent dark shell and pastel Topic identity.

## Required design tools

Use **21st (21st.dev)** for component and interaction references, **frontend-design** (the owner referred to it as frontent-design) for visual direction and implementation quality, and **UI UX Pro Max** for design-system, usability, and accessibility review. Discover their actual installed names and usage from your environment. Report which you used, and continue with the available tools if any are missing. Use 21st components as references when they assume React or external packages. Produce plain HTML, CSS and JavaScript.

## Deliverables

Create design/claude-pilot-prototype/ with index.html and direct links for each major view. Make it runnable by opening the HTML in a browser. Use shared CSS and JS, meaningful icons, realistic dummy data, responsive layouts, visible focus and keyboard-operable navigation. Label demo data and state simulation. Avoid network calls, sign-in credentials, dependencies and production application changes. Do not modify the other prototype. Use SVG icons or a local icon set with text or accessible names. Make the main controls work. Avoid filler copy, repeated cards and controls that look clickable without a demo action.

## Screen and flow requirements

| Screen | Layout and behavior |
| --- | --- |
| Sign-in / first run | Focused entry with demo sign-in, example workspace and skip/enter path. Never request a real password or token. |
| Today | Date and personal checklist lead. Show overdue, planned today and earlier planned tasks once each. Task completion updates the same Task everywhere. Reminders have time-first styling. Requested PR reviews stay separate. Active Topics have equal visual weight and show next steps. They are driven by the semantic Active category. |
| Topics List | Show title, status, next step and personal task progress. Open a Topic. Toggle List/Board and retain the last view. |
| Topics Board | Lanes follow editable status order. Distinguish Topic pastel identity from status. Move a card via drag and an accessible menu/select. Show horizontal scrolling at narrow widths. |
| Topic Details | Show name, goal, status and continuation context. Use collapsible Tasks, Notes, Jira, PRs and Reminders. Link to existing items without duplication. Contextual creation preselects Topic for Task/Note. Open external details in a desktop side panel or a narrow routed view. |
| Notes list and editor | Distinct document surface with an index, title, visual rich-text formatting, optional multiple Topic links and standalone notes. Simulate Saving locally, Saved locally/Offline, Syncing, Synced, local failure and remote failure. Show conflict comparison with both versions preserved and explicit resolution. |
| Inbox | Select and triage Task/Note captures. Link the existing item to a Topic or keep it standalone. Both clear the Inbox entry without deleting the item. Include an empty state. |
| Quick Capture | Global create Task, Note, or Topic. Minimal title. Optional Topic for Task/Note; outside a Topic default to Inbox, inside default to that Topic. No mandatory date. Topic creation goes to Topics. |
| Task details and Reminders | Complete/reopen checkbox. Task has zero or one Topic. Separate optional planned day, deadline and Reminder. Show their effects in Today. Show Reminder creation for Task, Note or Topic and in-app visibility. |
| Jira list and detail | Assigned list and sample accessible-ticket search. Use keys, status, fields, description, comments and related issues. Link to existing Topic or create one; allow multiple Topic links. Simulate cached-first refresh, offline/no cache, expired access and rate limits. Read-only. |
| Pull Requests list and detail | My PRs and Review Requested views. Green Open lifecycle pill, title and trailing number, repository/author and separate CI/review indicators. Read-only details, comments and Topic linking. Simulate the same access and cache states. |
| Global search | Cmd/Ctrl+K, grouped local results (Topics, Tasks, Notes, downloaded Jira/PRs) and navigation to each. Include empty/no results and Escape close. |
| Settings | Demo connection/disconnection for Jira/GitHub, workspace preferences and editable ordered Topic statuses. Delete an in-use status only after choosing a replacement. Category drives behavior. Dark theme only for pilot. |
| Trash | Show deleted app-owned objects, restore with context, include empty state. External records stay read-only. |
| Focus timer (post-pilot concept) | Compact sidebar/popover, 25/5 defaults, start/pause/stop, optional Task/Topic, small history. Label as post-pilot concept. |

## Verification

Use a single in-memory state or localStorage for demo data so links and actions reflect across views. Keep draft states visibly simulated. Check desktop and narrow layouts and run these journeys: capture → triage → Topic → complete Task; open a Note → format → inspect offline/conflict; Jira/PR search → detail → link → Topic; modify status → see Board/Today update; delete/restore a sample note; search → navigate. Include working entry links, a concise README with run steps, and verified versus visual-only behavior. Stop after design files and verification.
