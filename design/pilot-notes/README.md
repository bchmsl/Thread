# Notes design draft

Status: Accepted as the current visual baseline. The remaining design details can be revised during pilot review. Reference: v0.5 Dark tokens and Comfortable density.

Desktop shows the workspace rail, a Notes index, and a focused editor. The editor has a title, visible Topic links, a compact formatting bar, and inline save status. Notes may be standalone or have several Topic links. The proposed narrow production flow uses a dedicated editor route with a return path to the index. The demo stacks on narrow widths.

## States

| State | Meaning |
| --- | --- |
| Saving locally | The current edit has not committed to device storage yet. |
| Saved locally · Offline | Local commit succeeded; remote sync is pending. |
| Syncing | A locally committed revision is waiting for server confirmation. |
| Synced | The server acknowledged the current revision. |
| Sync failed | Retain the local draft and explain the remaining sync failure. |
| Local save failed | The edit is still in memory. Provide a visible recovery action in the production design. |
| Conflict | Preserve both versions; open comparison without replacing either version. |

In the preview, the state selector is a design review control. Note selection, New, and Compare versions are interactive. Editor formatting and persistence are represented visually. Linking, conflict resolution actions, local storage retry/copy, search, Trash, and empty index still need their production designs. Note checklist items are rich text.

Open notes.html for the standalone preview. Reference notes.fragment.html for the inline version in the shared workspace.
