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
- Work meetings come from Outlook through Apple Calendar on the owner's Mac. The "Thread Calendar" Mac app uploads them to Firebase, read-only, so every device shows them (D78, [ADR-020](adr/ADR-020-calendar-meetings-sync.md)). They are the one external content Firebase holds.
- Airy, Apple Notes-inspired first UI; optional Compact density later.
- The pilot build is implemented and live at https://bchmsl.github.io/Thread/ (see Deploy). The pilot is closed with no fixes (D55, [pilot/PILOT-RESULTS.md](pilot/PILOT-RESULTS.md)). The approved visual reference stays the v3 prototype.

## How to use with Claude Code

Put this directory's contents in a project repository without overwriting existing project instructions. For a new repository, keep CLAUDE.md at the root and the sibling documents alongside it, or update all paths if moving them.

Start with Prompt 1 in 07-claude-prompts.md. It asks for a plan only.
Do not ask Claude to build the entire application in a single prompt.

After an approved decision changes, update its authoritative document and add a dated entry to the decision register. Keep pending questions and completed work explicit.

## Deploy

- The web app is deployed to GitHub Pages at https://bchmsl.github.io/Thread/ by [.github/workflows/pages.yml](.github/workflows/pages.yml), on every push to `master` or by hand from the Actions tab.
- The workflow writes `firebase/web-config.properties` from four repository secrets: `THREAD_FIREBASE_API_KEY`, `THREAD_FIREBASE_AUTH_DOMAIN`, `THREAD_FIREBASE_PROJECT_ID` and `THREAD_FIREBASE_APP_ID`. If one is missing, the job stops, so a build for the local emulators is never deployed.
- The Firebase web config is public in the bundle by design. It names the project and is not a secret. Sign-in and the Firestore rules protect the data.
- Jira and GitHub tokens are stored in the browser, per origin. On https://bchmsl.github.io they must be entered again in Settings. The tokens saved on 127.0.0.1 are not copied.
- Every GitHub Pages site of the account shares the https://bchmsl.github.io origin and its browser storage, tokens included. Host no other untrusted pages under this account.
- One-time setup: Pages source "GitHub Actions" in the repository settings, and `bchmsl.github.io` in the Firebase Auth authorized domains.
- GitHub Pages cannot set HTTP headers, so `frame-ancestors`, `X-Content-Type-Options` and `Referrer-Policy` stay unset (adr/ADR-015-pilot-hardening.md).
- Paths are relative, so the same build runs at / on 127.0.0.1 and at /Thread/ on Pages.

## Calendar from your Mac (D78)

Meetings reach Thread through "Thread Calendar", a menu bar app in [macCompanion/](macCompanion/README.md). Its README has every step; in short:

1. The Firestore rules for meetings must be live first (`firebase/firestore.rules`).
2. In the Google Cloud console of the Thread project, create an OAuth client ID of type **Desktop app**. Safelist its client ID in Firebase console › Authentication › Sign-in method › Google.
3. Copy `macCompanion/oauth-desktop.example.properties` to `macCompanion/oauth-desktop.properties` and fill in `clientId` and `clientSecret`. The file is git-ignored; never commit it or paste its values anywhere. The build also reads `apiKey` and `projectId` from `firebase/web-config.properties`.
4. Build and install with `macCompanion/scripts/build-app.sh --install`. It needs an Apple Development certificate; the build writes the values into the app bundle only, without printing them.
5. Open the app, allow full calendar access, pick the work calendar, sign in with the Google account used for Thread, and turn on Start at login.

Thread web needs no setup: Today shows the Meetings card once the Mac app has uploaded, and Settings › "Calendar from your Mac" shows its state. Syncing starts, pauses and stops in the Mac app.

## What comes next

The [v3 prototype](design/claude-pilot-prototype-v3/README.md) is the approved design reference for all pilot surfaces (D33). It replaces the v0.5 baseline. The pilot boundary is confirmed (D34): read-only Jira and GitHub are in the pilot, and the Focus timer comes after it.

Done: the M1 feasibility slice, the pilot build (Phases 0 to 12) and the owner's changes D47 to D54. The pilot (Phase 13) is closed with no fixes (D55). Phase 14 adds the Focus timer (D56, [ADR-019](adr/ADR-019-focus-timer.md)); its Firestore rules must be live before its build. Work meetings from the owner's Mac (D78, [ADR-020](adr/ADR-020-calendar-meetings-sync.md)) are built and live. The owner's live check passed; a decline, a reply change, start at login and a night running are still to show in the Mac app's diagnostics.

Next steps:
1. Use Prompt 3 with the v3 prototype for UI work, one named set of screens at a time.
