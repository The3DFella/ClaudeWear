# PC Chats (read-only) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** List the user's existing Claude Code chats on the watch, with recent messages, live progress and a finish buzz. Read-only.

**Architecture:**
- **Bridge:** a pure transcript parser, a cached chat index that reads only the head and tail of each file, and a 2 s poller that tails grown files into bus events under `pc:<sessionId>`.
- **Watch:** new state slices, plus a Home section and a Chat screen.

**Tech Stack:** as the main plan (bridge: Node/TS/vitest; watch: Kotlin/Compose/JUnit).

**Spec:** `docs/design/specs/2026-11-04-pc-chats-readonly-design.md` (extends `2026-11-04-wearclaude-design.md`)

## Global Constraints
- Read-only: no route may write to `~/.claude`. Never read credentials files (`~/.claude/.credentials.json` or similar). Only `~/.claude/projects/**/*.jsonl` top-level session files are read, and `subagents/` folders are skipped.
- Parse defensively: unknown or invalid lines are ignored, never thrown.
- The watch never receives `cwd`; it receives `projectName` (the basename) only.
- Limits:
  - prompt and reply text: 500 chars
  - title: 60 chars
  - items per chat: 60
  - chats listed: 100, from the last 30 days
  - head read: 64 KB; tail read: 512 KB
  - poll interval: 2 s
  - working window: 20 s
  - idle timeout: 60 s
- Agent id namespace for PC chats on the bus: `pc:<sessionId>`.
- Event types added: `chat_item {kind:'prompt'|'reply'|'step', text, ts}` and `chat_status {status:'working'|'idle', title, projectName}`. Mirror them exactly in watch `Models.kt`.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- A live bridge is running on ports 8787-8789. Tests must use port 0 and temp dirs.

## Review Focus
1. Huge transcripts (30+ MB): `list()` must stay fast. Only the head and tail are read, and results are cached by (mtime, size).
2. A partial trailing line during live writes is buffered, not parsed or dropped.
3. A transcript that is rewritten or truncated (size shrinks) resets that file's offset, with no crash and no duplicate flood.
4. The bridge's own `claude -p` sessions are excluded, as are temp-dir cwds.
5. Startup must not fire "finished" alerts for chats that were already idle.

---

### Task 16: Transcript parser (bridge, pure)
**Files:** Create `bridge/src/transcript.ts` and `bridge/test/transcript.test.ts`, plus synthetic fixtures in `bridge/test/fixtures/pc-chat-*.jsonl`. The fixtures are hand-written to match the observed shapes in the spec; they contain no real content.

**Produces:**
- `type ChatItem = { kind: 'prompt' | 'reply' | 'step'; text: string; ts: number }`
- `parseTranscriptLines(lines: string[]): { items: ChatItem[]; title?: string; firstPrompt?: string; cwd?: string; turnEnded: boolean; lastTs?: number }`
  - `turnEnded` reflects the last assistant line seen in this batch.
- `chatTitle(customTitle?: string, firstPrompt?: string): string`

**Tests:**
- custom-title wins over first prompt; a 60-char title from the first prompt; the fallback is "Untitled chat".
- `tool_result` user blocks, `isMeta` and `isSidechain` lines are not prompts.
- Wrapper tags are stripped: `<system-reminder>…</system-reminder>`, `<pasted_content …>…</pasted_content>` replaced by `[pasted]`, and `<command-…>` tags.
- Thinking blocks are skipped.
- `tool_use` produces `describeTool` steps.
- `end_turn` sets `turnEnded`.
- Invalid JSON is ignored.
- 500-char truncation.

### Task 17: Chat index + routes (bridge)
**Files:** Create `bridge/src/chatIndex.ts`. Modify `bridge/src/config.ts` (`showPcChats`, `pcChatsMaxAgeDays`), `bridge/src/server.ts` (routes) and `bridge/src/cli.ts` (wiring). Tests in `bridge/test/chatIndex.test.ts` and `server.test.ts`.

**Produces:**
- `type ChatInfo = { id: string; title: string; projectName: string; lastActivity: number; status: 'working' | 'idle' }`
- `class ChatIndex`
  - `constructor(o: { rootDir: string; excludeSessionIds: () => Set<string>; maxAgeDays: number; now?: () => number; tmpDir?: string })`
  - `list(): ChatInfo[]`
  - `get(id: string): { chat: ChatInfo; items: ChatItem[] } | undefined`
  - `file(id): string | undefined`
- Routes:
  - `GET /chats` returns 404 `{error:'not_found'}` when `showPcChats` is false.
  - `GET /chats/:id`: `id` must match `^[0-9a-f-]{36}$`, otherwise 400.

**Tests (temp root):**
- A file with a 1 MB middle of filler still yields the title from the tail and the first prompt from the head.
- Old files and temp-dir cwds are excluded.
- Ids from `excludeSessionIds` are excluded.
- `subagents/` is ignored.
- Results are cached: a second `list()` with no change doesn't re-read (spy on the read function).
- Status is `working` when mtime is under 20 s and the last line is not end_turn.
- `get` returns the last 60 items.
- Routes return the data; no `cwd` field anywhere in the JSON (assert the string "cwd" is absent).

### Task 18: Live tailing → bus events (bridge)
**Files:** Create `bridge/src/chatWatcher.ts`. Modify `cli.ts` to start and stop it, and `types.ts` to add the event variants. Test in `bridge/test/chatWatcher.test.ts` with injected clock and interval.

**Produces:** `class ChatWatcher { constructor(o: { index: ChatIndex; bus: EventBus; intervalMs?: number; now?: () => number }); start(): void; stop(): void; tick(): void }`
- The first tick records offsets only, so there is no backlog flood.
- Later ticks parse the appended bytes, emit `chat_item`s, and emit `chat_status working` on the first growth after idle and `idle` on end_turn or after 60 s without growth.

**Tests:**
- Appending 2 lines emits 2 items and a working status.
- Appending an end_turn line emits idle.
- A partial line is held until its newline arrives.
- A file that shrinks resets its offset without emitting.
- Nothing is emitted on the first tick.

### Task 19: Watch data layer for chats
**Files:** Modify `watch/.../data/Models.kt` (ChatInfo, ChatItem, events), `BridgeApi.kt` (`chats()`, `chat(id)`), `AgentsReducer.kt` (state `chats: Map<String, ChatInfo>`, `chatItems: Map<String, List<ChatItem>>`) and `AgentRepository.kt` (reload also fetches chats; `loadChat(id)`). Tests in the existing test files.

**Tests:**
- `chat_status` upserts and `chat_item` appends, capped at 60, with seq dedup via the existing `lastSeq` guard.
- Reload populates chats.
- `loadChat` replaces the items.
- An unknown future event is still ignored.

### Task 20: Watch UI + alert
**Files:** Modify `ui/HomeScreen.kt` and `HomeLogic.kt` (pure `pcChatGroups(state): List<ProjectGroup>`, with groups ordered by newest chat), create `ui/ChatScreen.kt` and `ChatLogic.kt` (pure `chatLines(items)`), and modify `AppNav.kt` (route `chat/{id}`), `service/AlertLogic.kt` (`ChatFinished` alert on a working→idle transition, never at startup) and `Notifier.kt` (text "<title> finished", tap → `chat/<enc id>`).

**Tests:**
- Grouping and order.
- `chatLines` prefixes ("You: ", "↳ ").
- The chat alert fires on transition but not on the first loaded state.
- `routeFromExtras` accepts `chat/`.

### Task 21: Docs + on-watch check
- Add a README section "PC chats (read-only)", documenting the `showPcChats` toggle and noting that replies to PC chats aren't supported and why.
- Manual check with the user: the list shows real sidebar titles, and a desktop chat's progress streams live with a buzz on finish.
