# Inbox and Quick Capture design

Status: Proposed. Uses v0.5 Dark and Comfortable.

Inbox is the queue for untriaged captures. Selecting an item opens its details beside the queue. On narrow screens the preview stacks the queue and details. A production detail route and its back behavior remain open.

- Quick Capture exposes Task, Note, and Topic. Enter a title to create. Task and Note accept an optional Topic. With no Topic they land in Inbox. Topic creation goes to Topics. An entry point inside a Topic preselects it for Task and Note.
- Move to Topic links the existing object and triages the Inbox entry. Keep standalone triages the Inbox entry and preserves the object. Both actions remove the entry from the queue. Empty Inbox has an explicit state and Quick Capture stays available.
- Task details expose three independent optional fields: planned day, deadline, and reminder. Planned day is a calendar day; deadline and reminder are timestamps. Capturing a Task requires none of them. The design does not select default dates.
- Initial Inbox triage picks one Topic. Notes can add multiple Topic links from their own editor flow. Tasks have at most one Topic.

The preview uses temporary sample data. It demonstrates capture, Task scheduling, and triage. Topic creation and linked capture show a success message in place. Production destinations, Inbox undo, and persistence/error states are outside this preview. Open inbox.html to run it independently.
