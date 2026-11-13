# Review checklist

## Product review

Bachana approved keeping the v0.5 design for Today, Topics List/Board, and Topic Details on 2026-11-12.

Pending decisions: Active topics sort order, earlier planned grouping, Today quick capture scheduling, section expansion defaults, completed Topic visibility, and Topic color selection.

Pending design work: rich-text editor, narrow-screen details, and empty, offline, error and recovery states.

## Prototype interactions

- Today → linked Topic → Jira/PR panel → close.
- Topics → List/Board → open any Topic.
- Checkbox completion updates the same in-memory task and Topic counts.
- Drag or select status moves a Topic between Board columns.
- Quick capture adds a task, respecting preview screen context.
- Focus button opens a placement preview; no timer is implemented.

## Verification

Static checks and any browser checks performed are recorded below. No production build, external API, offline save, sync or accessibility certification is implied.

## Known limitations

- Narrow-screen Board and external detail presentation are provisional fallback layouts, not the agreed production behavior.
- No editor, full search, create-Topic flow, persistence, full empty/error states, real timer or integrations.
- Fixture labels are not evidence of live sync or real cached records.
- UI proposal is not approved for pixel-level implementation yet.

### Checks performed 2026-11-12

JavaScriptCore with a mock DOM verified that Topic and Task labels share fixture colors and a Topic status change preserves its task-label color. Existing Today, PR, Jira, and List/Board checks passed. Browser layout remains unverified.
