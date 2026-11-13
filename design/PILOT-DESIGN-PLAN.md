# Pilot design plan

Status: Design approved and pilot boundary confirmed on 2026-11-13.
Approved design: the [v3 prototype](claude-pilot-prototype-v3/README.md) for all pilot surfaces (D33). It supersedes the v0.5 baseline (D32).

## Visual direction review

Done. The owner chose v3 on 2026-11-13.
- v0.5 is superseded.
- v1 ([claude-pilot-prototype](claude-pilot-prototype/)) and v2 ([claude-pilot-prototype-v2](claude-pilot-prototype-v2/)) stay as references that were not chosen.
- The earlier [workspace-direction](workspace-direction/README.md) proposal is also not the reference.

## Pilot goal

Use Thread for one real workday. Capture personal work, organize it by Topic, resume a Note offline, and open a linked Jira issue or pull request for context.

| Design review | Pages and flows | Status |
| --- | --- | --- |
| Core workspace | Today, Topics List, Topics Board, Topic Details | approved in v3 |
| Notes | Notes index, editor, new note, offline save, sync error and conflict | approved in v3 |
| Capture and triage | Inbox, new Task/Note/Topic, Task details, planning date, deadline, reminders | approved in v3 |
| Work integrations | Jira and PR lists, item details, link to Topic, setup and connection failures | approved in v3 |
| Workspace controls | Cmd+K, onboarding/sign-in, Settings, editable Topic statuses, Trash/restore, Topic deletion | approved in v3 |
| Cross-screen review | Empty, loading, offline, permission and recovery states; keyboard and narrow layout | approved in v3 |

Pilot boundary, confirmed on 2026-11-13 (D34): basic read-only Jira and GitHub are in the pilot, since linking real work is central to it. Pomodoro (the Focus timer) and completed session history stay in v1 scope and are delivered after the pilot. Existing deferred capabilities remain deferred. The owner may revisit the pilot scope later; that is an idea, not a plan.

## After design review

Write ordered implementation phases with entry criteria, expected output, a Claude Code prompt and acceptance checks for each phase. Retain the M1 feasibility plan as technical input. Confirm the actual B1 location and evidence before relying on it. Start implementation in the shared folder after the design and phase plan are reviewed, and inspect the changed files and test results after each phase. The final phase includes a real workday pilot and a list of fixes before adding more features.
