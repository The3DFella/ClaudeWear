# WearClaude — Design Spec

Date: 2026-11-04
Status: Draft for review

## 1. Goal

An open-source Wear OS app (first target: Samsung Galaxy Watch 7) that lets a person:

1. Dictate a prompt by voice on their watch.
2. Have it run as a Claude Code agent on **their own computer**, signed in with **their own** Claude account (Pro/Max subscription) or API key.
3. Watch the agent's progress live, approve or deny risky actions, reply with follow-ups, and get a vibration when it finishes or needs them.

Success = the author can, from their Galaxy Watch 7, away from home, say "run the tests in my-app and fix any failures", see each step, approve a command, and read the summary, while using their Max plan.

## 2. Constraints and decisions

| Decision | Choice | Why |
|---|---|---|
| What runs agents | The official, unmodified `claude` CLI, launched in headless mode (`claude -p`) by a bridge on the user's PC | Our reading of Anthropic's terms at the time (see <https://code.claude.com/docs/en/legal-and-compliance>): end users sign in to the unmodified Claude Code binary with their own subscription, and the Agent SDK is not for offering claude.ai login in third-party products, so the SDK is not used. |
| Sign-in | The user runs `claude` → `/login` themselves (or sets `ANTHROPIC_API_KEY`). The bridge never reads, stores, or forwards Claude credentials. | Our reading of Anthropic's terms at the time (see <https://code.claude.com/docs/en/legal-and-compliance>). |
| `--bare` mode | **Never used** | Bare mode ignores subscription (OAuth) login. |
| Hosting | No central server. Each user runs their own bridge. | Open source; no shared costs; no third-party credential handling. |
| Remote access | LAN by default; anywhere via **Tailscale Funnel** (public HTTPS URL to the bridge, protected by the device token). | The watch needs no VPN client; Funnel provides TLS. |
| Watch connectivity | Standalone Wear OS app talking HTTPS/WebSocket directly (Wi-Fi or LTE, or via phone's Bluetooth proxy). No phone companion app. | Fewer moving parts. |
| Push when app is fully closed | **Out of scope for v1** (would need Firebase Cloud Messaging). | Keeps the open-source setup free of a Google project. |
| Branding | Working name "WearClaude". Docs say it "runs Claude Code". No Claude Code/Anthropic logos. **Open item:** pick a public name without "Claude" in it before publishing (trademark guidelines). | Anthropic legal page on names and logos. |
| License | MIT | Simple and permissive. |

References:
- https://code.claude.com/docs/en/legal-and-compliance (authentication and credential use, names and logos)
- https://code.claude.com/docs/en/agent-sdk/overview (no claude.ai login for SDK products)
- https://code.claude.com/docs/en/headless and /cli-reference (flags below)

## 3. Architecture

```
┌────────────── Galaxy Watch 7 ──────────────┐        ┌──────────────── User's PC ────────────────┐
│ WearClaude app (Kotlin, Compose for Wear)  │        │ wearclaude-bridge (Node 20+, TypeScript)   │
│  - Voice input (system speech recognizer)  │ HTTPS  │  - HTTP API + WebSocket event stream       │
│  - Agent list / detail / approval UI       │◀──────▶│  - Pairing + device tokens                 │
│  - WebSocket client, vibration alerts      │  WSS   │  - AgentManager: spawns `claude -p` per run │
└────────────────────────────────────────────┘        │  - Permission MCP server (approve tool)    │
          ▲  via LAN, or Tailscale Funnel URL           │  - Event normalizer (stream-json → watch)  │
                                                       └───────────────┬────────────────────────────┘
                                                                       │ child process (stdout NDJSON)
                                                               ┌───────▼────────┐
                                                               │ claude (CLI)   │ ← user signed in via /login
                                                               └────────────────┘
```

Repository layout (monorepo):

```
/bridge        Node/TypeScript bridge (npm package `wearclaude-bridge`)
/watch         Android Studio project, Wear OS app module
/docs          Setup guide, Tailscale guide, specs, plans
README.md, LICENSE
```

## 4. Bridge

### 4.1 Setup and config

- Installed with `npx wearclaude-bridge` (or `npm i -g`).
- First run checks:
  - `claude --version` exists and is ≥ 2.1.316 (needed for `--permission-prompts`).
  - `claude -p "ok" --output-format json --max-turns 1` succeeds. If the result says it's not authenticated, it prints: "Run `claude` and type `/login` first."
- Config file: `~/.wearclaude/config.json`
  - `port` (default 8787), `bindHost` (default `0.0.0.0`)
  - `projects`: list of `{ name, path }`. Agents may only run in these folders. Managed with `wearclaude-bridge add-project <path> [--name x]`.
  - `defaultPermissionMode`: `default` (prompts go to watch). Option: `acceptEdits`.
  - `approvalTimeoutSec` (default 300). An unanswered approval → deny.
- Device tokens are stored hashed (SHA-256) in `~/.wearclaude/devices.json`.

### 4.2 Pairing

1. `wearclaude-bridge pair` (or the default start screen) shows a 6-digit code valid for 5 minutes, plus the LAN URL and the Funnel URL if detected.
2. The watch sends `POST /pair { code, deviceName }`. The bridge returns `{ token }` (32 random bytes, base64url).
3. All other calls require `Authorization: Bearer <token>`. Revoke with `wearclaude-bridge devices revoke <name>`.
4. Rate limit: 5 failed pair attempts → pairing locked for 5 minutes.

### 4.3 Running an agent

New run:

```
claude -p "<prompt>"
  --output-format stream-json --verbose
  --session-id <uuid generated by bridge>
  --mcp-config <bridge-generated JSON pointing to the bridge's permission MCP server>
  --permission-prompt-tool mcp__wearclaude__approve
  --permission-mode <defaultPermissionMode>
```

- cwd = the chosen project's path.
- Follow-up: the same command with `--resume <sessionId>` and the new prompt, in the same cwd.
- Stop: send SIGINT to the child (ends the turn cleanly). Escalate to kill after 10 s.
- One child process per active turn. Agent records persist in `~/.wearclaude/agents.json`: id, sessionId, project, title (first 60 chars of the first prompt), status, timestamps, last summary.

### 4.4 Permission MCP server

- An MCP server (Streamable HTTP on `127.0.0.1:<port+1>`, local only, with a per-run secret in the URL) exposing one tool `approve`.
- Input from Claude Code: tool name, tool input, tool_use_id.
- The bridge creates a pending approval `{ id, agentId, tool, summary }` and emits an `approval_requested` event. It waits for the watch's decision or the timeout.
- Returns `{"behavior":"allow","updatedInput":<original input>}` or `{"behavior":"deny","message":"Denied from watch"}`.
- **Verify in plan step 1 (spike):** the exact input/output JSON contract against claude v2.1.316.

### 4.5 Event normalizer

Converts `stream-json` lines into compact watch events:

| claude stream message | Watch event |
|---|---|
| `system/init` | `status: running` |
| assistant `tool_use` | `step` with a short label, e.g. `Bash` → "Running `npm test`", `Edit`/`Write` → "Editing auth.ts", `Read` → "Reading auth.ts", `Grep`/`Glob` → "Searching", `Agent` → "Started subagent", other → tool name |
| assistant `text` | `message` (truncated to 500 chars; full text available on request) |
| `system/api_retry` | `step` "Retrying (rate limit)…" |
| `result` success | `status: done` + `summary` (result text, truncated to 1500 chars) + cost |
| `result` error / non-zero exit | `status: failed` + reason (auth error, usage limit, etc. in plain words) |
| permission tool call | `approval_requested` |

Subagent messages (`parent_tool_use_id` ≠ null) are shown as indented steps.

### 4.6 HTTP / WebSocket API (v1)

All JSON. All endpoints except `/pair` and `/health` need a bearer token.

- `GET /health` → `{ ok, version }`
- `POST /pair`
- `GET /projects` → `[{ id, name }]` (paths are never sent to the watch)
- `GET /agents` → list with status and last step
- `POST /agents { projectId, prompt }` → `{ agentId }`
- `GET /agents/:id` → detail + recent events (last 200)
- `POST /agents/:id/messages { prompt }` → follow-up (409 if a turn is running)
- `POST /agents/:id/stop`
- `POST /approvals/:id { decision: "allow"|"deny" }`
- `GET /events?since=<seq>` as a WebSocket upgrade (bearer token in the `Authorization` header): first a `{type:"hello", bootId, seq}` control message, then a stream of `{ seq, agentId, type, ... }`. On reconnect, the bridge replays events after `since` from an in-memory ring buffer (last 2000 events), or sends `{type:"reset", bootId, seq}` when it cannot.
- `GET /agents` carries `X-Bridge-Boot` (random per bridge process) and `X-Bridge-Seq` (latest seq at snapshot time). The watch reloads first, then streams from that seq, drops any event with seq <= its last applied seq, and reloads on a hello with a new boot id or on a reset.

## 5. Watch app

- Kotlin, Jetpack Compose for Wear OS (Material 3), minSdk 30, targets Wear OS 5 (Galaxy Watch 7).
- Networking: OkHttp (REST + WebSocket), kotlinx.serialization.
- Storage: DataStore. The token goes in EncryptedSharedPreferences / Keystore.

Screens:

1. **Pair:** enter the bridge URL (with a hint to use the Funnel URL for remote use) and the 6-digit code.
2. **Home:** a large mic button at the top, then a scrolling list of agents (status dot, title, last step). A badge appears when approvals are pending.
3. **Dictate:**
   - Launches the system speech recognizer (`RecognizerIntent`); falls back to the keyboard.
   - Shows the transcript with Send / Redo, plus a project chip (last used, tap to change).
4. **Agent detail:** a live step feed, then a summary card when done. Buttons: Reply (mic), Stop.
5. **Approval:** full-screen card showing the tool and its summary, with Allow and Deny buttons. Opens automatically with a vibration when a request arrives while the app is in the foreground or recently used.

Background behavior (v1):
- A foreground service keeps the WebSocket alive while any agent is running and shows an ongoing notification ("2 agents running"). It stops when all agents are idle.
- Heads-up notifications and vibration fire for done, failed, and approval needed.
- With nothing running, there is no connection and no alerts (the FCM push limitation is documented).

## 6. Security

- Device token required on every call. Comparisons are constant-time. Tokens are revocable.
- Only allow-listed project folders. The watch sends project IDs, never paths.
- The permission MCP server binds to 127.0.0.1 only and checks a per-run secret.
- Approvals default to deny on timeout.
- Funnel exposes the bridge publicly, so the docs state that the token is the only protection and recommend Tailscale Serve (tailnet-only) for users who can put the watch on their tailnet.
- The bridge never touches `~/.claude` credentials. The README states this explicitly.
- `claude -p` runs project hooks and `.mcp.json` without a trust dialog, so the docs warn to add only trusted folders.

## 7. Error handling

- `claude` missing, too old, or signed out → the bridge fails its startup check with a clear fix. Mid-run auth or usage-limit errors → `failed` with plain text on the watch.
- Watch offline → agents continue. The watch replays missed events on reconnect, or reloads `GET /agents` if `since` is older than the buffer.
- Bridge restarts while an agent is running → the agent is marked `interrupted`. The user can reply to resume it (`--resume`).
- Approval timeout → deny, and the watch shows "Timed out — denied".

## 8. Testing

- **Bridge (vitest):**
  - Normalizer unit tests using recorded `stream-json` fixtures.
  - Pairing and token tests.
  - API tests with a fake `claude` script that emits fixture output.
  - Permission MCP round-trip tests.
  - One manual end-to-end check against the real `claude`.
- **Watch:**
  - JVM unit tests for event → UI-state reduction and API client parsing.
  - Manual testing on the Wear OS emulator and on the Galaxy Watch 7 via adb over Wi-Fi.

## 9. Out of scope for v1

- FCM push notifications when the app is closed
- Tiles and complications
- Multiple bridges per watch
- A phone companion app
- Claude Code cloud sessions
- Viewing diffs on the watch
- iOS / watchOS

## 10. Open items

1. Public project name without "Claude" in it (before publishing).
2. Confirm the permission-tool JSON contract (spike, plan step 1).
3. Confirm the Android build toolchain on this PC (JDK from Android Studio's JBR).
