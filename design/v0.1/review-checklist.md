# Review checklist

## Product review: pending

- Does Today prioritize the right work without feeling like a dashboard?
- Approve or revise Earlier planned and its placement.
- Approve or revise Today quick capture automatically planning a task today.
- Are Topic continuation context and next step prominent enough?
- Are List/Board cards readable at the intended density?
- Is the external detail panel wide enough for real ticket content?
- Choose section expansion defaults and completed Topic visibility later.

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

Passed: standalone HTML generation, tokens JSON parsing, static HTML parsing, unique static IDs, required render targets, no network-fetch code. Browser verification could not run because the available Playwright import failed. Interactive behavior and visual layout are not browser-verified; review before approval. No production tests were run.
