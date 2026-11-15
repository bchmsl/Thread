# Pilot results (Phase 13)

Date: 2026-11-15. Status: closed with no fixes (D55).

## What the owner said

The owner tested the pilot on the live app (https://bchmsl.github.io/Thread/) and reported no problems.

Owner's words, relayed by the orchestrator session:

1. "I already tested it. What is next?"
2. Asked "What problems did you hit during the test? If none, say "none" and I'll start the Focus timer plan.", the owner answered: "none. Continue"

## What is known

- The owner used the live app and found no problem worth reporting.
- The owner approved moving on to the next feature, the Focus timer (D25, D34, D45).
- The live app is the GitHub Pages build of `master`. At the time of this record, `master` is at `cd0a581` (Phases 0 to 12 and D47 to D54).
- The Firestore rules on the real Firebase project were deployed by the orchestrator session before each push that changed them (`c1077a7`, `a6ae332`, `b8cfeb6`). The deployed ruleset matches `firebase/firestore.rules` at `cd0a581` (SHA-256 starts `765c3c9e48ac`).

## What is not known

- No pilot log was kept or shared.
- The day, the build and the device used for the test were not recorded.
- Which of the four pilot-goal actions (design/PILOT-DESIGN-PLAN.md, "Pilot goal") were done with real data was not recorded:
  1. Capture personal work.
  2. Organize it by Topic.
  3. Resume a Note offline.
  4. Open a linked Jira issue or pull request for context.
- No problem was reported, so nothing was reproduced. No local data, sync state or console log was checked for lost writing or a false "Synced".

So Phase 13's acceptance item "the four pilot goal actions done with real data" is not confirmed. It rests on the owner's report only.

## Fix list

| Priority | Items |
|---|---|
| Must fix before more features | None |
| Should fix | None |
| Later | None |

Reason: the owner reported no problems ("none. Continue"). The list is empty because nothing was reported, not because every path was checked.

## Still not verified

The owner's words do not cover these. They stay open from `pilot-readiness` (session notes, 2026-11-13) and the later ADRs.

| Area | What is not verified | Source |
|---|---|---|
| Browsers | Safari and Firefox. Only headless Chrome ran the browser checks. | ADR-016, ADR-017, ADR-018 |
| Devices | Real phones (iOS Safari, Android Chrome), including D48's zoom block on iOS. | pilot-readiness, D48 |
| Accessibility | Screen readers. Only focus order, names and ARIA attributes were checked. | pilot-readiness |
| Scale | Many records. No load test ran. | pilot-readiness |
| Time | A live midnight rollover, and time zones other than Asia/Tbilisi in the running app. | pilot-readiness |
| Accounts | Signing in with a different account between sign-out and sign-in (Q13). | pilot-readiness |
| Notes | Other devices' drafts of a Note deleted forever (Q37). | pilot-readiness |
| Sign-in | No console check for CSP errors during real Google sign-in was shared (Q58). | pilot-readiness |
| Jira and GitHub | Automated checks used SYNTHETIC fixtures only. Real answers, pagination, rate limits and 401/403 texts were checked only by the owner's own use, which was not recorded. | pilot-readiness, ADR-013, ADR-014 |
| Suggestions | D49 Phase 2 API assumptions are [inference, not checked live] (Q100 to Q103). How noisy suggestions are on real Topics is unknown. | ADR-017 |
| Connection sync | D47 between two real devices. Only emulated devices ran; two devices writing in the same second was not raced live. | ADR-016 |
| Reminder move | Whether D54's one-time move of Topic reminders onto Tasks ran on the real account, and what it moved, was not recorded. | ADR-018 part B |
| HTTP headers | `frame-ancestors`, `X-Content-Type-Options` and `Referrer-Policy` stay unset: GitHub Pages cannot set headers. A known limit, not a test gap. | ADR-015, README "Deploy" |

Not in the pilot build on purpose: the Focus timer (D34, D45), closed-app notifications (D27), Jira and GitHub writes (D26), AI features (D30). Outlook Calendar was not started; the owner said not now (session notes, 2026-11-13).

## Decisions from the pilot

- D55 (2026-11-15): the pilot is closed with no fixes, and the Focus timer comes next. See 06-roadmap-decisions.md.

## Next

The Focus timer (Pomodoro). Its phase plan waits for the owner's approval before any code.
