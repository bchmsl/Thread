# ADR-003: Note editor and RichText v1

Status: Accepted (2026-11-13). Written in Phase 1 (M1-A spike). The owner answered the open questions ("1. Default 2. Default 3. Default") and accepted this record ("Accept all"), both relayed by the orchestrator session.

## Context

Notes are rich text, not Markdown source and preview. The stored body must not depend on the web editor, so a later SwiftUI app can read and write it. Saves are local-first, and the save status must never claim more than storage confirmed (`02-ux-design.md`, "Quiet autosave indicator").

Phase 1 proves the editor and local drafts on one route. Not in this phase: Firebase, sync, Topics, real screens.

## Decision

### RichText v1 model (`shared/domain`, package `thread.domain.note`)

- Blocks: paragraph, heading levels 1 to 3, bullet list, ordered list (with `start`), checklist.
- List and checklist items start with a paragraph and may hold more blocks after it, such as a nested list.
- Inline content: text with bold, italic and link marks, and hard break.
- `LinkHref` accepts only `https://`, `http://` and `mailto:` addresses (prefix case does not matter), with something after the prefix and no spaces or control characters. Everything else, such as `javascript:` or `data:`, is refused.
- The constructors enforce the rules: a document has at least one block (an empty note is one empty paragraph), text is never empty, lists have at least one item.
- Checklist items are text, not Tasks (`01-product-spec.md`).

### JSON form (`RichTextJson`)

```json
{"schemaVersion":1,"content":[
  {"type":"heading","level":2,"content":[{"type":"text","text":"Plan"}]},
  {"type":"paragraph","content":[{"type":"text","text":"Read ","marks":[{"type":"bold"}]},{"type":"hardBreak"}]},
  {"type":"orderedList","start":1,"content":[{"type":"listItem","content":[{"type":"paragraph","content":[]}]}]},
  {"type":"taskList","content":[{"type":"taskItem","checked":false,"content":[...]}]}
]}
```

- Node and mark names match Tiptap's, so the web adapter maps them one to one. The format is still owned by `RichTextJson`, not by Tiptap: Tiptap attributes that v1 does not define are never stored.
- Marks are written in a fixed order: bold, italic, link.
- `decode` is strict. An unknown schema version, node type, mark or key, a wrong value type, or a refused link fails the whole document with `RichTextFormatException`. Nothing is dropped silently, so content from a later schema is never lost by being read as v1.
- A later version gets a new `schemaVersion` and an explicit migration. Not built yet.

### Editor (`webApp`, package `thread.web.platform.editor`)

- Tiptap 3.33.1 through Kotlin externals, one file per npm module: `@tiptap/core`, `@tiptap/pm` (the ProseMirror peer), `@tiptap/starter-kit`, `@tiptap/extension-list`, `@tiptap/extension-link`. The packages ship UMD builds, so each file has `@file:JsModule` and `@file:JsNonModule`.
- Licences: these five bring in 42 npm packages in total. All 42 declare MIT in their own `package.json` (checked in `node_modules`, not from a website).
- StarterKit with headings 1 to 3. Blockquote, code, code block, horizontal rule, strike and underline are off. Link is replaced by a Link that keeps only `href`, and OrderedList by one that keeps only `start`. TaskList and TaskItem (nested) are added.
- `enableContentCheck` is on. If a stored body does not fit the schema, the editor refuses it instead of dropping parts. `NoteEditor` then turns editing off and the page shows "This note cannot be edited here" with Copy text, so a damaged body is never saved over a good one.
- `RichTextEditorJson` maps editor JSON both ways. Reading from the editor also normalizes: a link with a refused address loses the link but keeps its text, a non-integer list start becomes 1, and next-door text runs with the same marks merge.
- The editor's trailing empty paragraph is not stored (owner answer 3). Tiptap's TrailingNode adds an empty paragraph whenever the last block is not a paragraph, so the cursor can reach the end. `RichTextEditorJson` leaves out exactly that case: a last empty paragraph that follows a heading, list or checklist. The editor adds it again when the note opens, so the note looks the same and opening it is not an edit. An empty paragraph after a paragraph is the user's and is stored.
- `injectCSS` is off. The ProseMirror base rules are copied into the spike stylesheet instead.
- ⌘Enter or Ctrl+Enter in a checklist item ticks it, as in v3. The checkbox label is "Checklist item: …" (Tiptap's default says "Task item", which would confuse checklist items with Tasks).
- The editor is created in a Compose `ref` and destroyed in its `onDispose`.

### Paste

The editor schema is the paste filter. Only schema nodes and marks survive. Seen in Chrome with a synthetic web page:

| Pasted | Result |
|---|---|
| h1 | Heading 1 |
| h4 | Paragraph |
| `<b>`, `<em>` | Bold, italic |
| `<u>`, `<s>`, `<code>`, blockquote, `<pre>` | Plain text kept, formatting dropped |
| `<hr>`, `<img>`, `<script>` | Dropped. The script never ran and the image never loaded. |
| Table | Cell text kept as paragraphs. Cells in one row merge into one run ("HeadCell"). |
| `<ol type="a" start="4">` | Ordered list starting at 4. The numbering type is dropped. |
| `style`, `class`, `title`, `target`, `rel` | Dropped. Links keep only `href`. |
| Link with `https`, `http` or `mailto` | Kept as a link (owner answer 1) |
| `javascript:` link | Text kept, link dropped |
| Relative link | Chrome writes absolute URLs to the clipboard on copy, so it arrives as `http(s)://…` and is kept |

Autolink and link-on-paste are off. StarterKit's typing shortcuts stay on (`# ` heading, `- ` bullet, `1. ` ordered list, `[ ] ` checklist; owner answer 2): they only create schema content.

### Links

- The toolbar link button opens an inline form, as v3 does. It adds a link to the selection, or edits the link at the cursor (the form is prefilled).
- With nothing selected and the cursor outside a link, the address itself is inserted as linked text.
- The form refuses any address that `LinkHref` refuses, with "Use a full address that starts with https://, http:// or mailto:".
- `openOnClick` is off. Rendered links get `target="_blank" rel="noopener noreferrer nofollow"` (Tiptap's default).

### ⌘K (D40)

- One `keydown` listener on `window` in the capture phase, so it runs before the editor or any focused field.
- It matches ⌘K or Ctrl+K by character, or by the physical K key when the layout types a non-Latin letter (Georgian). It ignores IME composition and Shift or Alt.
- It calls `preventDefault` and `stopPropagation`, so the editor never sees the key. The spike shows a toast in place of search.

### Local drafts

- Domain: `LocalDraft(noteId, title, body)` and the `LocalDraftStore` interface (`read`, `write`). `write` returns only after storage reports a durable commit, and it must start the write before its first suspension, so a flush inside `pagehide` reaches storage.
- `DraftAutosaver` (domain, common code):
  - Writes after 300 ms without edits, at least once a second while typing goes on, and at once on `visibilitychange` to hidden, `pagehide` and leaving the route.
  - Each write stores the whole draft. Status comes from revision counters: Saved only when the newest edit is stored; Needs attention after a failed write, until a newer write succeeds.
- Data: `IndexedDbDraftStore` (`shared/data`, jsMain).
  - Database `thread-local` version 1, object store `noteDrafts`, key path `noteId`. Record: `{noteId, recordVersion: 1, title, body}`, where `body` is the RichText JSON string.
  - Writes use a `readwrite` transaction with `durability: "strict"`, call `commit()` where the browser has it, and finish only on the `complete` event. An abort or error is a failure.
  - The database is opened again after `versionchange` or `close` (another tab upgraded it, or the browser cleared storage).
  - Test hook: `failWritesForTesting` makes a write abort its own transaction after `put`, so the real failure path runs.
- Status line: "Saving locally" (busy), "Saved locally" (quiet), "Needs attention / Not saved on this device" (bad) with a banner that offers Retry save and Copy text. While a save is failing, the browser asks before the tab is closed or reloaded (`beforeunload`, added only then, because it can keep the page out of the back/forward cache).

### Spike route

- `#/m1-spike`, linked from the home page. One synthetic note, labelled as synthetic in its title and text.
- `css/tokens.css` is copied from `design/claude-pilot-prototype-v3/assets/tokens.css` unchanged (SHA-256 match). `css/m1-spike.css` copies the v3 rules this page needs. Rules that are not from v3 are marked "Spike:".
- Small additions to v3, all on this page only: Save link and Remove link in the link form, the quiet tone for "Saved locally", heading 1 and 3 styles, and the test hook bar ("Make local saves fail", styled like the v3 demo bar).
- The test hook is wired in `Main.kt` and is on the page in every build. It goes away with the spike.

## Verification (2026-11-13, real output)

Unit and build checks:

| Check | Result |
|---|---|
| `./gradlew build ktlintCheck` | Passed (102 tasks). Includes the production bundle (`thread.js`, 1.25 MiB minified, up from 514 KiB in Phase 0, mostly Tiptap and ProseMirror). |
| `:shared:domain` tests (`jsNodeTest`, and the same common tests on `iosSimulatorArm64Test`) | 34 passed on each: RichTextJson 16 (round trip of every node and mark, stable and documented encoding, refusal of other schema versions, unknown nodes, marks and keys, duplicate marks, editor JSON, heading levels outside 1 to 3, refused links and broken structure, errors name the JSON path), LinkHref 3, DraftAutosaver 15 (debounce, max wait, flush starts the write at once, Saved only after the store confirms, Needs attention while storage fails, retry) |
| `:shared:data:jsBrowserTest` (Chrome headless, real IndexedDB) | 7 passed: missing note reads as null, a write is read back also by a new connection, a write replaces the previous draft, the record has the documented shape, a failed write (test hook) throws and keeps the previous draft, a damaged record is reported instead of dropped, a newer record version is refused |
| `:webApp:jsBrowserTest` (Chrome headless, real Tiptap) | 15 passed: RichTextEditorJson 8, NoteEditor 6 (every v1 construct survives the editor unchanged, the trailing paragraph is not stored and opening is not an edit, web-page paste keeps only schema content, ⌘Enter ticks, link insert, destroy removes the editor), search shortcut 1 (⌘K, Ctrl+K, Georgian layout, Dvorak layout; no match without the modifier, with Shift, during composition). The 3 trailing-paragraph tests were added after the owner's answers; `./gradlew build ktlintCheck` passed again afterwards. |
| Licences | 42 npm packages, all MIT |
| `tokens.css` | SHA-256 matches the v3 file |
| Secrets | No key-format matches in the new code |

Browser checks: a scripted run in headless Chrome 157 over the DevTools protocol, against the development bundle served from `127.0.0.1`. 13 of 13 passed, with an empty console.

| Check | What happened |
|---|---|
| First open | Status went Saving locally → Saved locally. The synthetic note was in IndexedDB. |
| Formats | Bold, italic, heading, bullet, ordered and checklist made with the toolbar and keyboard. Stored with `schemaVersion: 1`. |
| Undo and redo | ⌘Z removed the typed text, ⌘⇧Z brought it back |
| Georgian typing | "გამარჯობა, სამყარო" in the body and Georgian letters in the title, both stored |
| IME composition | A composition showed while open and committed "日本" with no leftover text. Storage matched. |
| Paste from a web page | As in the paste table above. Only allowed links stored. |
| Links | `javascript:`, `example.com` and `ftp:` refused. Add, edit to `mailto:`, remove (text kept), each with a toast. |
| ⌘K and Ctrl+K | Reached the global handler from the editor, the title and the link field. The note did not change. |
| No editor leak | 5 open and leave cycles. While open: 1 editor, 1 view. After leaving: 0 editors, 0 views, 0 ProseMirror nodes, and the window and document listeners were back to the home page set. |
| E1: edit offline, reload | Network set offline in DevTools. "Saved locally" while offline. A real reload while offline shows Chrome's offline page (there is no offline app shell yet). Reloading once online kept the edited text. |
| E2: close right after typing | Tab closed 0 ms and 50 ms after the last key, with the status still "Saving locally". Reopened: text kept both times. |
| E4: IndexedDB fails (test hook) | Status became Needs attention and never showed Saved locally while failing. Banner shown, nothing stored, close warning on. After switching the hook off, Retry save stored the text, the status became Saved locally and the close warning went away. |
| Visual review | Spike and v3 notes page side by side at 1440 px and 390 px, also with the link error and Needs attention states. Checklist spacing was fixed to match v3. |

## Not verified and known limits

- Safari and Firefox: not run. Only Chrome 157, headless.
- IME: simulated with DevTools composition events, not a real macOS input method. Georgian used key events with Georgian characters, not a real Georgian keyboard layout.
- Paste used a synthetic web page copied inside Chrome, not a page from another app.
- No recording; the evidence is the script log and screenshots (kept outside the repository).
- A crash or OS kill less than about one second after an edit can lose that edit. Closing the tab normally does not (E2).
- No offline app shell: an offline reload cannot open the app until a service worker exists.
- `navigator.storage.persist()` is not requested, so the browser may evict local drafts under storage pressure.
- Two tabs editing the same note: last write wins, with no warning.

## Owner answers (2026-11-13)

The owner accepted the proposed default for each question ("1. Default 2. Default 3. Default", relayed by the orchestrator session).

| Question | Answer |
|---|---|
| 1. Pasted links: keep allowed links, or keep only their text? | Keep them as links. Only `http`, `https` and `mailto`, with `href` only. No change. |
| 2. Typing shortcuts (`# `, `- `, `1. `, `[ ] `): keep or turn off? | Keep them on. No change. |
| 3. Store the editor's trailing empty paragraph, or trim it? | Trim it in `RichTextEditorJson`, with tests. Done (see "Editor"). |

## Later changes

- Phase 2 (M1-B) changed the local draft store. The record is now version 2 with `baseRevisionId`, and version 1 records are still read. Each signed-in account has its own database, `thread-drafts-<uid>`; `thread-local` is now the signed-out database. See ADR-002 and ADR-004 (both Proposed).

## Proposed document updates

- `03-architecture.md`: name RichText v1 and `RichTextJson` as the stored Note body format, and `LocalDraftStore` as the domain boundary for device-local drafts.
- `04-data-and-sync.md`: record the `thread-local` IndexedDB layout and that sync must read drafts through `LocalDraftStore`, not IndexedDB directly.
- Neither is changed until the owner accepts this record.
