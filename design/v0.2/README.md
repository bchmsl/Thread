# Thread design handoff v0.2

Status: PROPOSED visual design, awaiting Bachana's review. Date: 2026-11-12.
Thread is a working title, not a confirmed product-name decision.

## Read order for Claude Code

1. Root CLAUDE.md and documents 01–06 remain the product baseline.
2. Read this file and screen-spec.md.
3. Inspect thread-wireframe.html in a browser. Read its CSS and interaction code.
4. Read tokens.json for the proposed values and their intended roles.
5. Read review-checklist.md for known omissions and verification status.

The owner requested portable, inspectable design deliverables and written behavior contracts so implementation does not rely on conversation memory or screenshots alone.

## Artifacts

- thread-wireframe.fragment.html: editable inline preview source.
- thread-wireframe.html: standalone preview; generated from the fragment. No dependencies or API calls.
- tokens.json: proposed Comfortable/dark design values.
- screen-spec.md: screen and interaction contract.
- review-checklist.md: approval and verification checklist.

The HTML is a design reference, NOT a production technology decision. Implement later in Kotlin/JS + Compose HTML. Do not copy this prototype's in-memory data model or synchronization behavior into production.

## Decision authority

Confirmed: existing product requirements in root documents; GPT designs, Claude implements. v1 uses Dark mode only. Additional themes are deferred.
Proposed: all new visual values, sidebar placement, English preview, Today continuation shortcut, task grouping, section defaults, completed-topic visibility.
Open: final name/language, date rules and technical choices.
Deferred: same scope as root roadmap; nothing here brings future features into v1.

Approval to explore a wireframe is NOT approval of every visual or behavioral default. On review, record approved decisions in the relevant root spec and decision register before a production implementation prompt is issued.

## Reproduce and edit

Open thread-wireframe.html in a browser. Make changes to the fragment first and regenerate the standalone wrapper. All data is synthetic and hardcoded in the fragment. There is no real authentication, API, local storage, editor or sync.

Task/status edits exist only for the current preview session. Reload resets fixtures. Optional host state remembers navigation only. A static preview can be served or opened directly without external network dependencies.

## Implementation gate

No implementation is authorized by this handoff. After review, scope the implementation prompt to approved screens and states. Complete the M1 storage/editor/auth feasibility work before claiming functional offline behavior.

## Portable bundle

The ZIP contains all nine baseline documents, the standalone HTML and written design contracts. The inline fragment stays in the working project; its complete markup/CSS/JS is embedded in the standalone HTML, which Claude can read and edit without access to ChatGPT.
