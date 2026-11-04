# WearClaude Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Node bridge that runs the user's own signed-in `claude` CLI on their PC, plus a Wear OS app that dictates prompts, streams agent progress, and answers approval requests.

**Architecture:**
- **Bridge:** spawns `claude -p --output-format stream-json` per turn. It normalizes the output into small `WatchEvent`s on an in-memory bus (ring buffer, replay by `seq`), serves them over Fastify (REST + WebSocket), and answers `--permission-prompt-tool` calls through a local MCP server that waits on the watch's decision.
- **Watch:** a Compose for Wear OS app that holds one `StateFlow<AgentsState>` fed by a reconnecting WebSocket and reduced by a pure reducer.

**Tech Stack:**
- **Bridge:** Node ≥ 20, TypeScript, Fastify 5 + @fastify/websocket, @modelcontextprotocol/sdk, vitest, tsx.
- **Watch:** Kotlin 2.x, AGP 8.x, Compose for Wear OS Material 3, OkHttp 4, kotlinx.serialization, DataStore, androidx.security-crypto, JUnit 4 + kotlinx-coroutines-test.

**Spec:** `docs/design/specs/2026-11-04-wearclaude-design.md`

## Global Constraints

- Run the official, unmodified `claude` binary. **Never pass `--bare`.** Never read, copy, or log anything under `~/.claude` credentials or the `ANTHROPIC_API_KEY` value.
- Minimum `claude` version 2.1.316.
- Pass the prompt to `claude` on **stdin**, never as an argv string (Windows quoting). Spawn with an args array and `shell: false`.
- Config dir: `~/.wearclaude/` (`config.json`, `devices.json`, `agents.json`). Override with the `WEARCLAUDE_HOME` env var (tests use a temp dir).
- Defaults:
  - port 8787, bindHost `0.0.0.0`
  - permission MCP on `127.0.0.1:<port+1>`
  - approvalTimeoutSec 300
  - pairing code: 6 digits, valid 5 min; 5 failed attempts → locked 5 min
  - token: 32 random bytes, base64url, stored as SHA-256 hex
  - event ring buffer: 2000 events; `GET /agents/:id` returns the last 200
- Truncation: `message` 500 chars, `summary` 1500 chars, agent title = first 60 chars of the first prompt.
- The watch never receives filesystem paths. Projects are referenced by `id`.
- Copy and branding: product name "WearClaude" (working name). Wording may say "runs Claude Code". No Anthropic/Claude Code logos.
- Watch: minSdk 30, compileSdk 35, targetSdk 35. Package `dev.wearclaude.watch`.
- Android builds use JDK `C:\Program Files\Android\Android Studio\jbr` (17) and SDK `%LOCALAPPDATA%\Android\Sdk`.
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Shared event contract (bridge ⇄ watch)

Defined in `bridge/src/types.ts` and mirrored in `watch/app/src/main/java/dev/wearclaude/watch/data/Models.kt`. Field names must match exactly.

```ts
type AgentStatus = 'running' | 'waiting_approval' | 'idle' | 'done' | 'failed' | 'stopped' | 'interrupted';
// 'done' = last turn succeeded; 'idle' is unused in v1 (reserved)

type WatchEvent = { seq: number; ts: number; agentId: string } & (
  | { type: 'status'; status: AgentStatus; reason?: string }
  | { type: 'step'; text: string; depth: number }            // depth 0 = main agent, 1+ = subagent
  | { type: 'message'; text: string }
  | { type: 'summary'; text: string; costUsd?: number }
  | { type: 'approval_requested'; approvalId: string; tool: string; summary: string; expiresAt: number }
  | { type: 'approval_resolved'; approvalId: string; decision: 'allow' | 'deny' | 'timeout' }
  | { type: 'agent_created'; title: string; projectId: string }
  | { type: 'reset' }                                        // client must reload GET /agents
);

type AgentInfo = { id: string; title: string; projectId: string; status: AgentStatus;
  lastStep?: string; summary?: string; createdAt: number; updatedAt: number; sessionId: string };
```

## Review Focus

1. **Prompts with quotes, newlines, `%`, `&` and emoji on Windows** must reach `claude` byte-for-byte. Test it in Task 6 with a fake claude that echoes stdin.
2. **Stopping an agent that is waiting for approval:** the pending approval resolves as `deny`, emits `approval_resolved`, and the agent ends `stopped`. It must not hang or stay `waiting_approval`. Test in Task 6.
3. **Watch reconnects with a `since` older than the ring buffer** → it gets a single `reset` event and reloads. Events are never silently skipped. Tests in Task 3 (bus) and Task 10 (reducer).
4. **Non-JSON or unknown lines on claude's stdout/stderr** (warnings, new message types) are ignored without crashing or failing the agent. Test in Task 4.
5. **Answering an approval twice, or after it timed out** (two devices, slow tap) → second answer gets 404, and the broker resolves only once. Tests in Task 5 and Task 7.

---

## Phase A — Bridge (`/bridge`)

### Task 1: Spike — confirm the claude contracts and record fixtures

Throwaway exploration; the only kept output is fixtures and a notes file.

**Files:**
- Create: `bridge/spike/permission-echo.mjs` (throwaway minimal MCP stdio server whose `approve` tool logs its input to a file and returns allow)
- Create: `bridge/test/fixtures/*.ndjson`, `bridge/test/fixtures/NOTES.md`

- [ ] **Step 1:** In a scratch folder, run `claude -p --output-format stream-json --verbose` with prompt "List the files here, then create hello.txt containing hi" (stdin). Pass `--mcp-config` (pointing at permission-echo) and `--permission-prompt-tool mcp__wearclaude__approve`. Save stdout as `fixtures/approve-write.ndjson`.
- [ ] **Step 2:** Record the exact JSON the `approve` tool received and what return shape claude accepted. Try both `{"behavior":"allow","updatedInput":{...}}` and `{"behavior":"deny","message":"..."}`, returned as a JSON **text** content block. Write the confirmed shapes into `NOTES.md`.
- [ ] **Step 3:** Record more fixtures:
  - `simple-text.ndjson`: a "say hi" prompt
  - `subagent.ndjson`: "use a subagent to count files"
  - `failed-auth.ndjson`: run with `CLAUDE_CONFIG_DIR` pointing at an empty temp dir, which yields an auth failure result; never touch the real config
- [ ] **Step 4:** Confirm that `--resume <id>` continues a `--session-id <uuid>` run, and note how `claude.exe` reacts to `child.kill()` on Windows (exit code, whether a `result` line is emitted). Put both in `NOTES.md`.
- [ ] **Step 5:** If any contract differs from the spec (§4.3, §4.4), stop and report before Task 2. Otherwise commit fixtures + NOTES.md (not the spike script).

### Task 2: Bridge scaffold + config

**Files:**
- Create: `bridge/package.json` (name `wearclaude-bridge`, `type: module`, bin `wearclaude-bridge` → `dist/cli.js`, scripts `build`, `test`, `dev`), `bridge/tsconfig.json`, `bridge/vitest.config.ts`, `bridge/src/types.ts` (shared contract above), `bridge/src/config.ts`
- Test: `bridge/test/config.test.ts`

**Interfaces — Produces:**
- `type Project = { id: string; name: string; path: string }`
- `type Config = { port: number; bindHost: string; projects: Project[]; defaultPermissionMode: 'default' | 'acceptEdits'; approvalTimeoutSec: number; claudeBin?: string }`
- `homeDir(): string`: `WEARCLAUDE_HOME` or `~/.wearclaude`
- `loadConfig(): Config`: defaults when the file is missing
- `saveConfig(c: Config): void`
- `addProject(c: Config, path: string, name?: string): Project`
  - Throws `Error('Not a directory: <path>')` if the folder is missing.
  - `id` = slugified name, made unique with a `-2`, `-3` suffix.
  - `name` defaults to the folder basename.
  - `path` is stored resolved and absolute.

- [ ] **Step 1:** Write tests:
  - `loadConfig` returns defaults `{port:8787,bindHost:'0.0.0.0',projects:[],defaultPermissionMode:'default',approvalTimeoutSec:300}` in an empty temp `WEARCLAUDE_HOME`.
  - A save then load round-trips.
  - `addProject` on a temp dir named `My App` gives `id 'my-app'`. Adding it twice gives `'my-app-2'`.
  - A missing path throws.
- [ ] **Step 2:** `npm i`, then run `npx vitest run test/config.test.ts`. Expected: FAIL (module missing).
- [ ] **Step 3:** Implement `config.ts`.
- [ ] **Step 4:** `npx vitest run`. Expected: PASS. `npx tsc --noEmit` is clean.
- [ ] **Step 5:** Add `bridge/.gitignore` (`node_modules`, `dist`). Commit.

### Task 3: Event bus with replay

**Files:** Create `bridge/src/events.ts`. Test `bridge/test/events.test.ts`.

**Interfaces — Produces:**
- `class EventBus { constructor(capacity = 2000); emit(e: WatchEventInput): WatchEvent; since(seq: number): WatchEvent[] | 'reset'; recent(agentId: string, n = 200): WatchEvent[]; subscribe(fn: (e: WatchEvent) => void): () => void }`
- `WatchEventInput` = `WatchEvent` without `seq`/`ts`. `seq` starts at 1 and increments.

- [ ] **Step 1:** Write tests:
  - `seq` values are monotonic.
  - Subscribers receive events, and stop after unsubscribing.
  - `since(0)` on an empty bus is `[]`.
  - After 2005 emits with capacity 2000: `since(3)` is `'reset'`, `since(5)` returns events 6..2005, and `since(2005)` is `[]`.
  - `recent('a', 2)` returns the last 2 events for agent `a` only.
- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement with a fixed-size array ring.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 4: stream-json normalizer

**Files:** Create `bridge/src/normalize.ts`. Test `bridge/test/normalize.test.ts` (uses Task 1 fixtures).

**Interfaces — Produces:**
- `describeTool(name: string, input: Record<string, unknown>): string`
  - `Bash` → `` Running `<command, first 60 chars>` ``
  - `Edit` / `Write` / `MultiEdit` / `NotebookEdit` → `Editing <basename(file_path)>`
  - `Read` → `Reading <basename>`
  - `Grep` / `Glob` → `Searching`
  - `WebFetch` / `WebSearch` → `Browsing the web`
  - `Agent` / `Task` → `Started subagent`
  - `mcp__*` → `Using <tool name>`
  - else → `name`
- `normalizeLine(line: string, agentId: string): WatchEventInput[]`
  - Pure function. Returns `[]` for blank, non-JSON, or unknown lines.
  - Mapping per spec §4.5. Assistant `tool_use` blocks become `step` events.
  - `depth` = `parent_tool_use_id ? 1 : 0`.
  - Assistant `text` becomes `message`, truncated to 500 chars.
  - `system/api_retry` becomes step `Retrying (<error>)…`.
  - `result` with `subtype:'success'` and no `is_error` → `summary` (1500 chars, `costUsd` from `total_cost_usd`) + `status done`.
  - `result` error → `status failed` with `reason` = `friendlyError(result)`.
- `friendlyError(result: any): string`
  - auth problems → `"Not signed in — run claude, then /login, on your PC"`
  - rate/usage limit → `"Usage limit reached"`
  - otherwise the first 200 chars of the result text

- [ ] **Step 1:** Write tests:
  - `simple-text.ndjson` → ends with `summary` + `status done`.
  - `approve-write.ndjson` produces a step `Editing hello.txt` (or `Running …` per what was recorded).
  - `subagent.ndjson` has at least one `depth:1` step.
  - `failed-auth.ndjson` → `status failed` with the "Not signed in" reason.
  - `normalizeLine('Warning: something', 'a')` and `normalizeLine('{"type":"brand_new"}','a')` both return `[]` (Review Focus 4).
  - `describeTool('Bash',{command:'npm test'})` returns `` 'Running `npm test`' ``.
- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 5: Approval broker + permission MCP server

**Files:** Create `bridge/src/approvals.ts`, `bridge/src/permissionServer.ts`. Tests `bridge/test/approvals.test.ts`, `bridge/test/permissionServer.test.ts`.

**Interfaces — Consumes:** `EventBus` (Task 3), `describeTool` (Task 4).

**Interfaces — Produces:**
- `type Decision = 'allow' | 'deny' | 'timeout'`
- `class ApprovalBroker { constructor(bus: EventBus, timeoutSec: number, onChange?: (agentId: string, waiting: boolean) => void); request(agentId: string, tool: string, input: unknown): Promise<Decision>; resolve(approvalId: string, decision: 'allow' | 'deny'): 'ok' | 'not_found'; cancelForAgent(agentId: string): void; pending(): {approvalId, agentId, tool, summary, expiresAt}[] }`
  - `request` emits `approval_requested` (`summary = describeTool`).
  - On resolve, timeout, or cancel it emits `approval_resolved` exactly once. Cancel emits `deny`.
  - `onChange` tells AgentManager to flip `waiting_approval` ↔ `running`.
- `startPermissionServer(broker: ApprovalBroker, port: number): Promise<{ mcpConfigFor(agentId: string): string /* JSON string for --mcp-config */; close(): Promise<void> }>`
  - Streamable-HTTP MCP server on `127.0.0.1:port`. The route is `/mcp/:agentId/:secret`; the secret is per agent (random 16 bytes, kept in a map).
  - Unknown secret → 403.
  - Server name `wearclaude`, one tool `approve`.
  - Return shape is exactly what Task 1 NOTES.md confirmed (`allow` → `{behavior:'allow', updatedInput: input}`; `deny`/`timeout` → `{behavior:'deny', message:'Denied from watch'}` / `'Timed out — denied'`).

- [ ] **Step 1:** Write broker tests (fake timers):
  - allow, deny, and timeout each emit exactly one `approval_resolved`.
  - `resolve` on an unknown or already-resolved id returns `'not_found'` (Review Focus 5).
  - `cancelForAgent` resolves pending requests as deny.
- [ ] **Step 2:** Write the server test. Connect with the MCP SDK client to `mcpConfigFor('a1')`'s URL, call `approve` with `{tool_name:'Bash', input:{command:'ls'}}`, resolve via the broker, and assert the returned text parses to the allow shape. A wrong secret returns 403.
- [ ] **Step 3:** Run. Expected: FAIL.
- [ ] **Step 4:** Implement both.
- [ ] **Step 5:** Run. Expected: PASS.
- [ ] **Step 6:** Commit.

### Task 6: AgentManager (spawning claude)

**Files:**
- Create: `bridge/src/claudeArgs.ts`, `bridge/src/agents.ts`
- Create: `bridge/test/fake-claude.mjs` (Node script):
  - reads stdin and the `FAKE_CLAUDE_FIXTURE` env var
  - replays the fixture lines to stdout with a 5 ms gap
  - if `FAKE_CLAUDE_ECHO=1`, writes stdin into a `result` line's `result` field
  - if `FAKE_CLAUDE_HANG=1`, never exits
- Test: `bridge/test/agents.test.ts`

**Interfaces — Consumes:** Config/Project (Task 2), EventBus (3), normalizeLine (4), ApprovalBroker + `mcpConfigFor` (5).

**Interfaces — Produces:**
- `buildClaudeArgs(o: { sessionId: string; resume: boolean; mcpConfig: string; permissionMode: string }): string[]`
  - → `['-p', '--output-format', 'stream-json', '--verbose', (resume ? '--resume' : '--session-id'), sessionId, '--mcp-config', mcpConfig, '--permission-prompt-tool', 'mcp__wearclaude__approve', '--permission-mode', permissionMode]`
  - Never contains `--bare`.
- `class AgentManager`
  - `constructor(o: { config: Config; bus: EventBus; broker: ApprovalBroker; mcpConfigFor: (id: string) => string; claudeCmd: { bin: string; preArgs: string[] } })`
  - `create(projectId: string, prompt: string): AgentInfo`: throws `UnknownProject`
  - `followUp(id: string, prompt: string): void`: throws `NotFound` or `Busy`
  - `stop(id: string): void`
  - `list(): AgentInfo[]`
  - `get(id: string): AgentInfo | undefined`
  - `setWaiting(id: string, waiting: boolean): void`
  - `loadOnStartup(): void`: persisted `running`/`waiting_approval` → `interrupted`
- Behavior:
  - Persist to `agents.json` on every status change.
  - Emit `agent_created`, then `status running`.
  - Pipe normalized events to the bus and update `lastStep`/`summary`.
  - Stop: `broker.cancelForAgent`, then kill the child.
    - Windows: `taskkill /PID <pid> /T /F`; elsewhere SIGINT, then SIGKILL after 10 s.
    - Then emit `status stopped`.
  - Exit without a `result` line and not stopped → `failed` with reason `"claude exited (code N)"`.
- Tests use `claudeCmd = { bin: process.execPath, preArgs: ['test/fake-claude.mjs'] }`.

- [ ] **Step 1:** Write tests:
  - `buildClaudeArgs` includes `--session-id` for new runs and `--resume` for follow-ups, and never `--bare`.
  - `create` with the `simple-text` fixture → bus sees `agent_created`, `running`, …, `summary`, `done`, and `get(id).status === 'done'`.
  - Echo mode: prompt `` He said "hi" & 100% done\nline2 🎉 `` comes back identical in the summary (Review Focus 1).
  - `followUp` while running throws `Busy`.
  - Hang mode + a pending `broker.request` for that agent, then `stop(id)` → `approval_resolved deny`, then `status stopped` (Review Focus 2).
  - `loadOnStartup` marks a persisted `running` agent `interrupted`.
  - An unknown projectId throws.
- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS on Windows.
- [ ] **Step 5:** Commit.

### Task 7: Pairing/auth + HTTP/WebSocket API

**Files:** Create `bridge/src/auth.ts`, `bridge/src/server.ts`. Tests `bridge/test/auth.test.ts`, `bridge/test/server.test.ts`.

**Interfaces — Produces:**
- `class DeviceAuth`
  - `constructor(now = Date.now)`; persists to `devices.json`
  - `startPairing(): { code: string; expiresAt: number }`
  - `pair(code: string, deviceName: string): { token: string } | { error: 'invalid' | 'expired' | 'locked' }`
  - `verify(token: string): { name: string } | null`: constant-time comparison of SHA-256 hashes
  - `list(): { name: string; createdAt: number }[]`
  - `revoke(name: string): boolean`
- `createServer(d: { config: Config; auth: DeviceAuth; agents: AgentManager; broker: ApprovalBroker; bus: EventBus }): FastifyInstance`
  - Routes per spec §4.6. Bearer auth on everything except `/health` and `/pair`.
  - `/projects` returns `{id,name}` only.
  - `GET /agents/:id` → `{ agent, events: bus.recent(id) , approvals: pending for agent }`
  - Errors:
    - `Busy` → 409
    - `NotFound` / `UnknownProject` / approval `not_found` → 404
    - bad body → 400
  - `GET /events?since=N` (WebSocket):
    - Auth via an `Authorization` header or a `token` query param.
    - Sends the `since` backlog (or one `{type:'reset'}`), then live events. The socket closes when the client goes away (unsubscribe).

- [ ] **Step 1:** Write auth tests (injected clock):
  - A valid code pairs once, and the code is single-use.
  - After 5 minutes → `expired`.
  - 5 bad codes → `locked` for 5 minutes, even for the right code.
  - `verify` works with the token and fails after `revoke`.
  - `devices.json` contains no raw token.
- [ ] **Step 2:** Write server tests with `fastify.inject` and a real `ws` client against `listen(0)`. Use AgentManager with the fake claude.
  - No token → 401.
  - `/projects` has no `path` key.
  - `POST /agents` → 200 + id.
  - Follow-up while running → 409.
  - `POST /approvals/x` → 404.
  - Approve the same id twice → 200, then 404 (Review Focus 5).
  - WebSocket with `since=0` receives the backlog, then live events.
  - WebSocket with a stale `since` receives `reset`.
- [ ] **Step 3:** Run. Expected: FAIL.
- [ ] **Step 4:** Implement.
- [ ] **Step 5:** Run. Expected: PASS.
- [ ] **Step 6:** Commit.

### Task 8: CLI, startup checks, manual end-to-end

**Files:** Create `bridge/src/doctor.ts`, `bridge/src/cli.ts`, `bridge/README.md`. Test `bridge/test/doctor.test.ts`.

**Interfaces — Produces:**
- `checkClaude(cmd: {bin, preArgs}): Promise<{ ok: true; version: string } | { ok: false; problem: string }>`
  - Runs `--version`; requires ≥ 2.1.316.
  - Then runs a 1-turn probe (`-p` with stdin "Reply with OK", `--output-format json`, `--max-turns 1`). On an auth failure it returns `problem: 'Not signed in. Run `claude`, then type /login.'`
  - Never `--bare`.
- `resolveClaude(config): {bin, preArgs}`: `config.claudeBin`, else `where claude` (Windows) / `which claude`.
- CLI commands:
  - `wearclaude-bridge` / `start`: check, start the servers, print the LAN URL(s), print the Funnel URL if `tailscale funnel status --json` reports one, and show a pairing code.
  - `pair`: prints a fresh code from the running bridge via `POST /local/pairing-code`, which is accepted only from 127.0.0.1.
  - `add-project <path> [--name]`
  - `projects`
  - `devices`
  - `devices revoke <name>`

- [ ] **Step 1:** Write doctor tests with fake-claude variants:
  - version 2.1.100 → problem mentions 2.1.316.
  - The auth-fail fixture → the "Not signed in" problem.
  - Success → `ok`.
- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement doctor + CLI.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Manual end-to-end against the real claude:
  1. `npm run build`
  2. `node dist/cli.js add-project <scratch dir>`
  3. `node dist/cli.js`
  4. Pair with curl.
  5. `POST /agents` with "create hello.txt with hi".
  6. Watch `websocat`/a node ws script print steps and an `approval_requested`, then `POST /approvals/:id allow`.
  7. Confirm `done` and that hello.txt exists.
  8. Report the output to the user.
- [ ] **Step 6:** Commit.

## Phase B — Watch app (`/watch`)

### Task 9: Toolchain + empty Wear app that builds and runs

**Files:**
- Create: `watch/settings.gradle.kts`, `watch/build.gradle.kts`, `watch/gradle/libs.versions.toml`, `watch/gradle.properties`, `watch/gradlew*`, `watch/gradle/wrapper/*`, `watch/app/build.gradle.kts`, `watch/app/src/main/AndroidManifest.xml`, `watch/app/src/main/java/dev/wearclaude/watch/MainActivity.kt`
- Create: `watch/local.properties` (gitignored)

- [ ] **Step 1:** Get a Gradle wrapper (8.10+, matching the AGP version chosen). Download the official Gradle distribution zip from services.gradle.org into the scratchpad, then run `gradle wrapper` once in `/watch`. Use `JAVA_HOME` = Android Studio JBR.
- [ ] **Step 2:** Set up the app module:
  - Manifest: `<uses-feature android:name="android.hardware.type.watch"/>`, meta-data `com.google.android.wearable.standalone=true`, permissions `INTERNET`, `VIBRATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`.
  - `MainActivity` shows a Wear `Text("WearClaude")`.
- [ ] **Step 3:** Run `gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 4:** Install the Wear OS 5 emulator image with `sdkmanager "system-images;android-34;android-wear;x86_64"` (ask the user before this ~1 GB download), then create an AVD `wear_round`. Run `adb install`, launch the app, and screenshot it.
- [ ] **Step 5:** Commit (with `.gitignore` for `build/`, `.gradle/`, `local.properties`).

### Task 10: Data layer — models, API client, event stream, reducer

**Files:**
- Create in `watch/app/src/main/java/dev/wearclaude/watch/data/`: `Models.kt`, `BridgeApi.kt`, `EventStream.kt`, `AgentsReducer.kt`, `AgentRepository.kt`
- Tests in `watch/app/src/test/java/dev/wearclaude/watch/data/`: `AgentsReducerTest.kt`, `BridgeApiTest.kt` (OkHttp MockWebServer)

**Interfaces — Produces:**
- `@Serializable` mirrors of the shared contract:
  - `WatchEvent` as a sealed class with `@SerialName` per `type`, using a `classDiscriminator = "type"` Json config with `ignoreUnknownKeys = true`.
  - An unknown `type` decodes to `WatchEvent.Unknown` (custom serializer fallback).
  - `AgentInfo`, `ProjectInfo(id, name)`, `PendingApproval(approvalId, agentId, tool, summary, expiresAt)`.
- `class BridgeApi(baseUrl: String, token: String?, client: OkHttpClient)`
  - All functions are `suspend` and throw `BridgeException(code: Int, message: String)` on HTTP errors.
  - `pair(code, deviceName): String`
  - `projects(): List<ProjectInfo>`
  - `agents(): List<AgentInfo>`
  - `agent(id): AgentDetail(agent, events, approvals)`
  - `createAgent(projectId, prompt): String`
  - `followUp(id, prompt)`
  - `stop(id)`
  - `decide(approvalId, allow: Boolean)`
- `class EventStream(api: BridgeApi, client: OkHttpClient)`
  - `fun events(startSince: Long): Flow<WatchEvent>`
  - Reconnects with backoff of 1, 2, 5, 10, 30 s (capped) and resumes from the last seen `seq`.
- `data class AgentsState(agents: Map<String, AgentInfo>, steps: Map<String, List<String>>, approvals: Map<String, PendingApproval>, lastSeq: Long, needsReload: Boolean)`
- `object AgentsReducer { fun reduce(s: AgentsState, e: WatchEvent): AgentsState }`
  - Pure function.
  - Steps are capped at 50 per agent.
  - `reset` → `needsReload = true`.
  - `approval_requested` adds to `approvals` and `approval_resolved` removes from it.
  - A `status` event sets the agent's status.
- `class AgentRepository(api, stream, scope)`
  - `val state: StateFlow<AgentsState>`
  - `fun start()`: loads agents, then collects the stream; reloads on `needsReload`
  - `stop()`

- [ ] **Step 1:** Write reducer tests:
  - `agent_created` + steps + `summary` + `status done` produce the expected state.
  - The 51st step drops the oldest one.
  - Approval requested, then resolved → empty approvals.
  - `reset` sets `needsReload` (Review Focus 3).
  - Unknown events leave state unchanged except `lastSeq`.
- [ ] **Step 2:** Write BridgeApi tests (MockWebServer):
  - The bearer header is sent.
  - A 409 maps to `BridgeException(409, …)`.
  - `pair` parses the token.
- [ ] **Step 3:** `gradlew :app:testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 4:** Implement.
- [ ] **Step 5:** Run. Expected: PASS.
- [ ] **Step 6:** Commit.

### Task 11: Settings + Pair screen

**Files:** Create `.../data/Settings.kt`, `.../ui/PairScreen.kt`, `.../ui/AppNav.kt`, `.../ui/MainViewModel.kt`. Modify `MainActivity.kt`.

**Interfaces — Produces:**
- `class Settings(context)`
  - `bridgeUrl: Flow<String?>`, `lastProjectId: Flow<String?>`; setters are suspend.
  - `token`: get/set via `EncryptedSharedPreferences`.
  - `clear()`
- `MainViewModel` exposes `paired: StateFlow<Boolean>`, `repo: AgentRepository?`, and `pair(url, code)`. The latter normalizes the URL (adds `https://` if no scheme and the host isn't a LAN IP, else `http://`) and maps errors to plain copy:
  - `"Wrong or expired code"` (401/400)
  - `"Too many tries — wait 5 minutes"` (423/429)
  - `"Can't reach your PC"` (IO)
- Navigation: unpaired → Pair, paired → Home.

- [ ] **Step 1:** Write a unit test for the `normalizeBridgeUrl(input: String): String` helper:
  - `192.168.1.5:8787` → `http://192.168.1.5:8787`
  - `my-pc.tail1234.ts.net` → `https://my-pc.tail1234.ts.net`
  - A trailing slash is stripped.
- [ ] **Step 2:** Implement. Pair screen: URL field + 6-digit field (number keyboard) + Pair button + error text.
- [ ] **Step 3:** Tests pass. On the emulator, pair against the running bridge at `http://10.0.2.2:8787`, then screenshot.
- [ ] **Step 4:** Commit.

### Task 12: Home + Dictate

**Files:** Create `.../ui/HomeScreen.kt`, `.../ui/DictateScreen.kt`, `.../ui/StatusDot.kt`.

- [ ] **Step 1:** Home:
  - `ScalingLazyColumn` with a large mic `Button` first.
  - A pending-approvals chip when the count is > 0 (opens the first approval).
  - Agent chips sorted by `updatedAt` desc, each showing the status dot color per `AgentStatus`, the title, and `lastStep`.
  - Empty state copy: "Tap the mic to start an agent".
- [ ] **Step 2:** Dictate:
  - Launch `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` via `rememberLauncherForActivityResult`. If no recognizer is available, fall back to the `RemoteInput` keyboard.
  - Show the transcript, a project chip (cycles through `projects()`, defaults to `lastProjectId`), and Send / Redo.
  - Send calls `createAgent`, then navigates to Detail. With `replyTo = agentId`, the same screen calls `followUp` instead.
- [ ] **Step 3:** Check on the emulator: dictate (or type) "create hello.txt with hi" against the bridge, and the agent appears on Home. Screenshot.
- [ ] **Step 4:** Commit.

### Task 13: Agent detail + approval screen

**Files:** Create `.../ui/AgentDetailScreen.kt`, `.../ui/ApprovalScreen.kt`.

- [ ] **Step 1:** Detail:
  - Header shows the title and status.
  - The step list auto-scrolls; depth-1 steps are indented with a "↳" prefix.
  - A summary card appears when present.
  - Buttons: Reply (→ Dictate with `replyTo`, disabled while running), Stop (visible while running or waiting).
  - Shows `reason` for failed agents.
- [ ] **Step 2:** Approval:
  - Shows the tool name, the summary (scrollable), and a countdown to `expiresAt`.
  - Allow (primary) and Deny buttons call `decide`.
  - A 404 shows "Already answered" and pops back.
  - Navigation auto-opens this screen when `state.approvals` gains a new entry while the activity is resumed.
- [ ] **Step 3:** Check on the emulator: the hello.txt run shows an approval, tap Allow, and it reaches done with the summary. Screenshot.
- [ ] **Step 4:** Commit.

### Task 14: Foreground service + alerts

**Files:** Create `.../service/AgentWatchService.kt`, `.../service/Notifier.kt`. Modify the manifest and `MainViewModel`.

**Interfaces — Produces:**
- `AgentWatchService`: a foreground service of type `dataSync` that owns the `AgentRepository` (the ViewModel binds to it).
  - Starts when an agent is created/followed up or the app opens with any agent `running`/`waiting_approval`.
  - Stops itself 60 s after no agent is running or waiting.
  - Ongoing notification: "N agents running".
- `Notifier.alert(e: WatchEvent, agent: AgentInfo)`
  - Notifies on `status done` / `failed` and `approval_requested`, with a high-importance channel and a vibration pattern (approval = long-short-long).
  - Tapping opens the detail or approval screen via a deep-link extra.
  - Requests the `POST_NOTIFICATIONS` runtime permission on first launch.

- [ ] **Step 1:** Write a unit test for the pure `alertFor(e: WatchEvent): AlertKind?` helper: done/failed/approval map to kinds, and everything else maps to null.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Check on the emulator: start an agent, press Home to leave the app, and an approval notification arrives. Tapping it opens the Approval screen, and Allow works.
- [ ] **Step 4:** Commit.

## Phase C — Ship

### Task 15: Docs, license, on-watch verification

**Files:** Create `README.md`, `LICENSE` (MIT), `docs/setup.md`, `docs/remote-access-tailscale.md`.

- [ ] **Step 1:** README:
  - What it is, with "runs Claude Code on your PC; sign in with your own Claude plan or API key".
  - A statement that the bridge never touches your Claude credentials.
  - Limits: no push when the app is closed, the trusted-folders warning, and the usage-policy note linking https://code.claude.com/docs/en/legal-and-compliance.
  - Quickstart: install the bridge, `claude` `/login`, add a project, sideload the watch APK, pair.
- [ ] **Step 2:** Tailscale guide:
  - `tailscale funnel 8787` (public, token-protected).
  - `tailscale serve` for tailnet-only use.
  - How to find the URL.
- [ ] **Step 3:** Real device:
  1. Enable watch Developer options → Wireless debugging.
  2. `adb pair` / `adb connect`.
  3. `gradlew :app:installDebug`.
  4. Pair over LAN.
  5. Dictate a real prompt by voice and approve it on the wrist.
  6. Do it with the user watching.
- [ ] **Step 4:** Commit. Report the finished state and the open items: public name, and whether to publish to GitHub.
