# Thread · clickable pilot prototype

Open **index.html** in a desktop or mobile browser. All data is fictional and the prototype runs without a server. The other HTML files are direct entry points. Within the prototype use the sidebar and linked items to move between screens while retaining the same demo session. LocalStorage may be isolated per file URL depending on the browser. Reset the demo in Settings.

## Try these flows

1. New → Task → Create → Inbox → choose a Topic → Finish triage → open that Topic → mark the task complete.
2. Topics → Board → drag or use the status select → see Active topics on Today. Toggle List for the compact view.
3. Notes → edit text and format a selection → link two Topics → select an offline or conflict state → compare and choose a version.
4. Jira or Pull requests → item details → Link Topic → check the linked item in Topic Details. Review the cached and disconnected states.
5. Cmd/Ctrl+K → search QR → open a result. In Settings edit or reorder statuses. Delete and restore a note using Trash.
6. Use the small Focus control to try the post-pilot timer concept.

The preview includes Today, Inbox, Quick Capture, Task scheduling and reminders, Topics List and Board, Topic Details, Notes, Jira, Pull Requests, Search, Settings, Trash, and demo sign-in. It uses plain HTML/CSS/JS with Unicode icons and labels. The design has distinct layouts for agenda, board, notebook, triage, integrations and settings.

Local data and interactions are demo-only. Provider states, sync, conflict, sign-in and storage failure are simulated. Open in Jira/GitHub shows an explanation for dummy records. Notes use browser editing commands and a small sample conflict model. Production editor behavior, durable offline sync, real provider auth and timer background behavior still require implementation. The timer illustrates fixed 25/5 durations and sample completion history.

Claude's independent design instructions are in CLAUDE-DESIGN-PROMPT.md. This prototype is a review proposal and does not change the production Kotlin architecture. The three named design tools are requested in the Claude prompt; this version was authored directly as a plain HTML prototype.
