# PC Chats (read-only) — Design Addendum

Date: 2026-11-04
Status: Approved in chat
Extends: `2026-11-04-wearclaude-design.md`

## Goal

Show the user's existing Claude Code chats from this PC (desktop app and CLI) on the watch:
- the list, grouped by project like the desktop sidebar
- recent messages in each chat
- live progress while a chat is working on the PC
- a buzz when a PC chat finishes a turn

**Read-only.** Replying to PC chats is out of scope.
- **Why:** a spike showed turns appended via `claude -p --resume` land on a separate branch that the desktop app never displays.
- **What stays:** watch-started agents keep working as today.

## Source of truth

Claude Code transcripts live at `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl`. This location is documented, but the line format is not, so the bridge must parse defensively and never crash on unknown lines.
- **Skip:** the `<sessionId>/subagents/**` folders.
- **Skip:** sessions whose cwd is under the OS temp dir.
- **Skip:** sessions the bridge itself started. Their `sessionId` is already in `agents.json` and they show as watch agents.

Format facts we rely on (all observed on Claude Code 2.1.316):
- `{"type":"custom-title","customTitle":…}`: the latest one is the sidebar title.
- `{"type":"user","message":{"content":string|blocks}}`:
  - A real prompt is a string or a `text` block, with `isMeta` not true and `isSidechain` not true.
  - `tool_result` blocks are not prompts.
- `{"type":"assistant","message":{"id","content":[thinking|text|tool_use…],"stop_reason"}}`: one line per content block, grouped by `message.id`. `stop_reason:"end_turn"` marks the end of a turn.
- `cwd` appears on most lines. `timestamp` is ISO-8601.

## Bridge

### `transcript.ts` (pure)
Parses lines into `ChatItem`s:
```ts
type ChatItem = {
  kind: 'prompt' | 'reply' | 'step';
  text: string;
  ts: number;
  uuid?: string;
};
```
- `prompt`: user text. Strip `<system-reminder>…</system-reminder>` and similar wrapper tags, collapse whitespace, max 500 chars.
- `reply`: assistant text block, max 500 chars.
- `step`: `describeTool(name, input)` for each `tool_use`.
- Thinking blocks are skipped.
- `turnEnded = true` when an assistant line has `stop_reason === 'end_turn'`.

Also extracts:
- `title`: the last `customTitle`, else the first prompt (max 60 chars), else `"Untitled chat"`.
- `cwd`: the first `cwd` seen.

### `chatIndex.ts`
`ChatIndex(rootDir = ~/.claude/projects, opts)` exposes:
- `list(): ChatInfo[]`
  ```ts
  type ChatInfo = {
    id: string;
    title: string;
    projectName: string;          // basename of cwd
    lastActivity: number;
    status: 'working' | 'idle';
  };
  ```
  - Only chats from the last 30 days, at most 100, newest first.
  - `status` is `working` when the file changed within 60 s (the watcher's idle timeout) and the turn has not ended: the last parsed assistant line was not `end_turn`, or a user prompt follows it. Otherwise it is `idle`.
- `get(id): { chat: ChatInfo, items: ChatItem[] }`, with the last 60 items.

Reading rules:
- Read the head (64 KB) for `cwd` and the first prompt.
- Read the tail (512 KB) for the title, recent items and status.
- Never read whole large files.
- Cache by `(mtime, size)`.

The watch never receives `cwd`, only `projectName`. Message text may contain paths; this is accepted.

### Live tailing (`chatWatcher.ts`)
- Poll `rootDir` every 2 s. This is a cheap stat of the `*.jsonl` files changed in the last 30 days; `fs.watch` is unreliable on Windows across network and OneDrive folders.
- For each file that grew, parse only the appended bytes and emit bus events under `agentId = "pc:" + sessionId`:
  - `chat_item {kind, text, ts}` for each new item.
  - `chat_status {status: 'working'|'idle', ended, title, projectName}` when the status changes:
    - `working` on first growth after idle (`ended: false`).
    - `idle` with `ended: true` on `end_turn`.
    - `idle` with `ended: false` after no growth for 60 s, or for 10 minutes when the last assistant `stop_reason` was `tool_use` (a tool is running or a permission prompt is waiting).
  - The watch buzzes "finished" only for working → idle with `ended: true`.
- The scan's size is reused; a file is opened only when its size changed.
- Partial last lines are buffered until a newline arrives.

### API (auth required, like all routes)
- `GET /chats` → `ChatInfo[]`
- `GET /chats/:id` → `{ chat: ChatInfo, items: ChatItem[] }`, or 404.

### Config
- `showPcChats` (default `true`; `false` disables index, watcher and routes)
- `pcChatsMaxAgeDays` (default 30)

## Watch

- **Home:**
  - The 🎤 Dictate button first.
  - Watch agents next, as today.
  - Then a "On your PC" section: one header per `projectName`, with chats under it (status dot: working = primary, idle = outline), newest projects first.
- **Chat screen** (route `chat/{id}`, read-only):
  - Header: title and project.
  - Items, auto-scrolled to the newest:
    - `prompt` shown as "You: …"
    - `reply` as plain text
    - `step` as "↳ …"
  - Footer note: "Read-only — reply from your PC or the Claude phone app".
- **Live updates:**
  - The reducer handles `chat_item` (append, capped at 60 per chat, dedup by seq) and `chat_status` (upsert ChatInfo).
  - `reload()` also loads `GET /chats`.
  - Opening a chat loads `GET /chats/:id`.
- **Alerts:**
  - A PC chat going `working → idle` while the app is in the background → notification "<title> finished" (agent_updates channel). Tapping it opens `chat/{id}`.
  - No alert at startup; this uses the same seeding rule as other alerts.

## Testing
- Bridge:
  - The parser is tested against synthetic fixtures modelled on real lines (no real user content).
  - The index is tested on a temp dir: head/tail reading, the 30-day and temp-dir filters, and the bridge-agent exclusion.
  - The watcher is tested by appending lines to a temp file, which must produce events.
  - Route tests.
- Watch: reducer/repository tests for chat events, plus Home grouping logic and an alert-on-idle test.
- On-device: the PC chats list shows the real sidebar titles, and a desktop chat's progress streams live.
