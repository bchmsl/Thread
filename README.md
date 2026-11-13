# Personal Workspace: project handoff

Version: 0.1 | Decision snapshot: 2026-11-13 | Owner: Bachana
Working title only: Personal Workspace. Final product name is not selected.

## Purpose

A private personal workspace for an Android engineer. Organize meaningful work around Topics, connecting Jira tickets, GitHub PRs, personal Tasks, rich-text Notes, Reminders, and eventually AI sessions.

These documents preserve decisions from the planning conversation. They are not evidence that software or integrations have been built. The approved design is a clickable prototype, not production UI.

## Responsibilities

- Bachana: product owner and final decisions.
- ChatGPT: product specification, architecture, and implementation prompts.
- Visual design reference: the owner-approved v3 prototype in [design/claude-pilot-prototype-v3/](design/claude-pilot-prototype-v3/README.md), approved on 2026-11-13 (D33).
- Claude Code: implementation and verification against these documents and the approved v3 design.
- Do not let an implementation agent silently substitute its own product or visual direction.

## Read in this order

1. [Product specification](01-product-spec.md)
2. [UX and design brief](02-ux-design.md)
3. [Architecture](03-architecture.md)
4. [Data model and sync](04-data-and-sync.md)
5. [Integrations](05-integrations.md)
6. [Roadmap and decision register](06-roadmap-decisions.md)
7. [Claude instructions](CLAUDE.md)
8. [Prompt pack](07-claude-prompts.md)

## Decision vocabulary

- **Confirmed:** explicitly agreed in the planning conversation.
- **Proposed:** a concrete architectural or implementation recommendation, not yet verified or approved as a library-level choice.
- **Open:** needs a decision or technical spike.
- **Deferred:** deliberately outside the first release.

Confirmed product behavior takes precedence over proposed technical details. When documents disagree, resolve the discrepancy explicitly rather than silently choosing one.

## Current baseline

- Web first: Kotlin/JS + Compose HTML with real HTML/DOM.
- Later iPhone app: SwiftUI; shared Kotlin domain/data where practical.
- Clean Architecture: data/domain/presentation.
- Firebase for app-owned cloud data and synchronization.
- Local-first experience: immediate local reads and durable local writes; background sync.
- Jira Cloud at your-site.atlassian.net; detailed read-only integration in v1.
- Jira content fetched from its API and cached on each device, not mirrored into Firestore.
- Notes may be stored in Firebase. User confirmed local Jira caching and work Notes are permitted.
- Airy, Apple Notes-inspired first UI; optional Compact density later.
- No production implementation has been delivered yet. The approved visual reference is the v3 prototype.

## How to use with Claude Code

Put this directory's contents in a project repository without overwriting existing project instructions. For a new repository, keep CLAUDE.md at the root and the sibling documents alongside it, or update all paths if moving them.

Start with Prompt 1 in 07-claude-prompts.md. It asks for a plan only.
Do not ask Claude to build the entire application in a single prompt.

After an approved decision changes, update its authoritative document and add a dated entry to the decision register. Keep pending questions and completed work explicit.

## What comes next

The [v3 prototype](design/claude-pilot-prototype-v3/README.md) is the approved design reference for all pilot surfaces (D33). It replaces the v0.5 baseline. The pilot boundary is confirmed (D34): read-only Jira and GitHub are in the pilot, and the Focus timer comes after it.

Next steps:
1. Write the ordered implementation phases described in [design/PILOT-DESIGN-PLAN.md](design/PILOT-DESIGN-PLAN.md).
2. Start with Prompt 1 in 07-claude-prompts.md to plan the M1 technical feasibility slice.
3. Use Prompt 3 with the v3 prototype for UI implementation, one named set of screens at a time.
