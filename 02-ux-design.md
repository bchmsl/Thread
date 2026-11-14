# UX and design brief

## Status and ownership

Confirmed: airy, simple, Apple Notes-inspired initial direction.
Future: Comfortable / Compact density switch.
This is a design brief, not a pixel-level design.
The approved visual design reference is the v3 prototype in [design/claude-pilot-prototype-v3/](design/claude-pilot-prototype-v3/README.md), chosen by the owner on 2026-11-13 (D33).
This brief stays the behavior contract. The prototype shows the approved look, layout and states.
Claude should implement the approved design rather than invent a competing direction.

## Experience principles

- Open into useful local content, not a full-screen network spinner.
- Writing and completing personal Tasks must feel immediate.
- Quiet, content-first interface with whitespace and readable type.
- Progressive disclosure for secondary details.
- Preserve Topic context when inspecting an external item.
- No decorative analytics cards, oversized Pomodoro card, or gamification.
- Clearly distinguish saved locally from synchronized remotely.

## Navigation (D35)

One bottom status line on every page replaces the sidebar:

```
[workspace] [Today Inbox Topics Notes Jira PRs] [Task v | Capture a task | -> Inbox] [Search ⌘K] [Focus] [sync] [Trash] [Settings]
```

- Views: Today, Inbox, Topics, Notes, Jira, Pull Requests. Inbox shows its count.
- Capture: choose Task, Note or Topic, type a title, pick a destination. `C` focuses it from any page.
- Tools: global search (⌘K), Focus timer panel, sync status, Trash, Settings.
- The line shrinks by width: full labels, then icons, then a compact capture field.
  Under 900 px the current view opens a views sheet. The v3 README lists the exact breakpoints.
- Notes index, Settings and Trash are approved as part of v3.
- A dedicated global Tasks page is not required by the confirmed scope.
- Idea only, not planned: a Settings toggle to show navigation in the status line or in a sidebar.

## Screen contracts

### Today

Main area: today's Tasks, due/overdue work, Reminders.
Separate Review Requested section.
Groups (D37): Overdue, Today, Earlier planned in the main column; Reminders and Review requested in a side column.
Topic labels provide context.
A Task that qualifies for multiple sections should not create confusing duplicates.
Empty state should support quick capture, not just show an illustration.

### Topics List

Rows: title, semantic status, next step, personal Task progress.
Progress counts personal Tasks only; do not imply Jira completion.
List / Board toggle preserves selected mode.
Filtering and sorting controls should remain unobtrusive; exact options are open.

### Topics Board

One column per custom status, ordered by the workspace status order.
Small, readable cards. No giant nested previews.
Drag-and-drop with keyboard/menu equivalent.
Reflect the status change locally and persist it.
Preserve an intentional accessible horizontal-scroll experience on narrow displays.

### Topic Details

Header with title/status and short goal.
Main flow: continuation context, next step, personal Tasks, Notes.
Secondary region: Jira, PRs, Reminders.
Sections collapse; exact default expansion behavior to be designed.
Open Jira/PR details in one contextual side panel on wide screens.
Do not stack multiple competing sidebars; related links may collapse into the panel header.
On narrow screens, show details as a routed screen with a clear return path.

Suggestions (D49, ADR-017). Not in the v3 prototype; placement queued as Q77.
- A "Suggested" group under the linked rows of the Jira and Pull requests sections. Hidden when not connected or when there is nothing to suggest.
- Rows reuse the external row with a dashed icon frame and a muted title. The snippet line gives the best two reasons ("Mentioned in Next step", "Mentioned in pull request #12", "SYN-101 is blocked by this"). "Also in" tags show other Topics that link the item.
- Each row has a small **Link** text button and a **Don't suggest** icon button ("Don't suggest SYN-104 in this Topic"). Both show a toast with Undo.
- 3 rows, then "Show N more", up to 10. "Not suggested here (N)" opens a dialog with **Restore** per row (title from the device, else "Not on this device") and **Done**.
- Focus: after Link or Don't suggest, the next suggestion, else the section heading. In the dialog, the next Restore, else Done; closing returns to "Not suggested here".
- Narrow screens: title and reasons wrap; the two buttons stay on the right (checked at 390 px).
- Offline: the rows already on the device, no error.

### Note editor

Visual formatting: headings, emphasis, lists, checkboxes, links.
Quiet autosave indicator: Saving locally / Saved locally / Syncing / Synced / Needs attention.
Do not show Synced before remote acknowledgment.
Handle paste, text selection, undo/redo, IME and Georgian text, links, and mobile keyboard.
No request per keystroke; local durability and reasonable batching are required.
Preserve unsynchronized drafts on navigation or recover them on next launch.

### External details

Title, key/number, status, assignee/author, metadata, rich description, comments.
Show last refreshed time and whether the content is cached.
Loading more comments should not block already available content.
Include Open in Jira/GitHub. It opens the original page in a new tab (D46).
Jira status shows as a small lozenge colored by status category, like Jira (D50): To Do gray, In Progress blue, Done green. The text is Jira's status name as written, not uppercased. Each color pair meets WCAG AA on the dark theme. Used on Jira list and search rows, the ticket header, a Topic's Jira section, ⌘K results and suggestion rows.
The Jira "Assigned to me" tab has a "Current sprint only" checkbox above the list (D50). Off by default, remembered on this device, hidden when the site has no sprints. The label is the 40 px tap target. With it on and nothing in the sprint, the empty state says "Nothing assigned to you in the current sprint".
Unsupported rich content should degrade to an understandable placeholder/link, not disappear silently.

### Quick capture

Minimal initial fields. Capture lives in the status line (D35).
From Topic: default association.
Elsewhere: Inbox default with optional Topic.
Do not require scheduling, priority, or a Topic just to capture an idea.

### Pomodoro

Focus button in the status line; show remaining time while active.
It opens a small panel for configuration and actions, with no dashboard block (D45).
Delivered after the pilot; still in v1 (D34, D25).
Task/Topic context visible within the popover.
Use persisted timestamps, not only a decrementing UI counter, to survive background throttling.

## Required interface states

For key screens design populated, empty, loading-first-use, cached-refreshing, offline, recoverable error, and permission/reauthentication states.
Do not blank useful cached data while refreshing.
Toast messages alone are insufficient for a persistent sync problem.

## Design system preparation

Proposed token groups:
- Semantic colors: surface, text, border, status categories, success/warning/error.
- Typography and spacing.
- Radius and elevation.
- Row/control dimensions.
- Density tokens.

Comfortable is the only initial density.
Compact will change spacing and row dimensions, not business features.
Dark mode is the only theme in v1. Additional themes and a theme switch are deferred. Keep color roles semantic so other themes can be added later.
Palette, typeface and breakpoints follow the v3 prototype (D33). App language remains open.
The production icon library is open; v3 uses its own inline SVG set.
Do not turn tentative visual defaults into hard product requirements.

### v3 visual system (approved 2026-11-13)

Source of truth: `design/claude-pilot-prototype-v3/assets/tokens.css`, in three layers (primitive, semantic, component).

| Area | v3 choice |
| --- | --- |
| Shell | Ink-navy surfaces (app `#0e1322`, surface `#131a2b`, raised `#192135`), fog text (`#e9edf7`, `#b2bbd2`, `#8792ae`). |
| Action | One magenta action color (`#f2559e`). One magenta pill per surface marks the main action. |
| Status categories | Gray `#a7b0c6`, blue `#82b6ff`, orange `#f5a65b`, green `#7bd49f`, each with a pattern swatch: dashed outline = Planned, solid = Active, stripes = Paused, check = Done. Meaning never depends on color alone. |
| Topic identity | Hex sticker with a two-letter monogram in a pastel (lavender, mint, sky, peach, butter) (D36). Pastels are identity only, never status. Idea only, not planned: an optional icon instead of the monogram. |
| Type | Futura for display, system UI for text. Scale 12, 13, 14, 15, 17, 20, 24, 30, 38 px. Futura ships with Apple platforms; other systems use the listed fallbacks (Century Gothic, URW Gothic, Trebuchet MS). |
| Space and shape | 4 px spacing base. Radius 10 px for controls and rows, 14 px for surfaces, pills for primary actions. Controls 40 px, primary 44 px, rows 48 px minimum, status line 56 px. |
| Notes | The editor is a notebook page on a faint dot grid. |
| Today | Overdue, Today, Earlier planned, plus a side column for Reminders and Review requested (D37). |
| Motion | Short transitions (120 and 220 ms) that drop to zero with reduced motion. |

## Accessibility and interaction quality

Use semantic HTML, keyboard access, visible focus, sufficient contrast, and meaningful labels.
Status meaning must not depend on color alone.
Support reduced motion.
Cmd+K must coexist with editor shortcuts. ⌘K opens global search in the editor too; adding a link uses the toolbar button (D40).
No page zoom (D48). The page blocks every zoom it can: the viewport meta (`maximum-scale=1, user-scalable=no`, which also stops iOS zooming in on a focused field), `touch-action: pan-x pan-y` (no pinch or double-tap zoom, scrolling stays), Ctrl or ⌘ with + = - _ 0, Ctrl or ⌘ with the wheel or a trackpad pinch, and Safari's pinch gestures. Page code cannot block the browser's own menu zoom, the operating system's accessibility zoom, or a setting that forces zoom on (iOS Safari's "ignore zoom restrictions"). This is a deliberate trade-off against text resizing (WCAG 1.4.4), chosen by the owner.
Retain sensible browser navigation and deep links.

## Next visual deliverables

1. Today desktop.
2. Topics List and Board.
3. Topic Details with external detail panel.
4. Note editor states.
5. Narrow-screen adaptations and core empty/offline states.

All five are covered by the v3 prototype, approved on 2026-11-13 (D33).
New screens or states beyond it should follow its visual system.

