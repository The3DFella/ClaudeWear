# ClaudeWear bridge (`wearclaude-bridge`)

The PC side of [ClaudeWear](../README.md): a small Node.js server that shows your local Claude Code chats on a paired Wear OS watch, relays dictated replies into open Claude desktop chats, and asks the watch to approve tool calls for the agents it runs.

> Unofficial community project, not affiliated with or endorsed by Anthropic. The package and command keep the internal name `wearclaude-bridge` (see [RENAMING.md](../docs/RENAMING.md)).

## Requirements

- Node.js 20 or newer
- Claude Code 2.1.316 or newer, signed in (`claude`, then `/login`)
- Optional: the Claude desktop app (chat titles, archive, reply by voice)

## Install

```sh
cd bridge
npm ci
npm run build
npm link        # optional: puts `wearclaude-bridge` on your PATH
```

Without `npm link`, run `node dist/cli.js …` from this folder instead of `wearclaude-bridge …`.

## Commands

| Command | What it does |
| --- | --- |
| `wearclaude-bridge` or `wearclaude-bridge start` | Check Claude Code, start the bridge, print LAN URL(s), a Tailscale Funnel URL if one is active, and a pairing code (valid 5 minutes). |
| `wearclaude-bridge start --skip-probe` | Same, but skip the one-turn sign-in check (still checks `claude --version`). |
| `wearclaude-bridge pair` | Print a fresh pairing code from the running bridge. Works only on the same PC. |
| `wearclaude-bridge add-project <path> [--name <name>]` | Add a trusted folder the bridge may run its own agents in. |
| `wearclaude-bridge projects` | List projects (`id`, `name`, `path`). |
| `wearclaude-bridge devices` | List paired watches and when they were paired. |
| `wearclaude-bridge devices revoke <name>` | Remove a watch. On a running bridge this takes effect immediately and closes its connections; otherwise it edits `devices.json`. |

Pairing the same watch again adds `<name> (2)`, `<name> (3)`, … instead of replacing the existing entry.

Only add folders you trust with `add-project`: `claude -p` runs a project's hooks and `.mcp.json` without a trust prompt. Projects are not needed to see your desktop chats.

## Configuration

Settings live in `~/.wearclaude/config.json` (override the folder with `WEARCLAUDE_HOME`):

| Key | Default | |
| --- | --- | --- |
| `port` | `8787` | Main port. `port+1` and `port+2` are used on 127.0.0.1. |
| `bindHost` | `"0.0.0.0"` | `"127.0.0.1"` for Tailscale-only use. |
| `projects` | `[]` | Managed by `add-project`. |
| `defaultPermissionMode` | `"default"` | Or `"acceptEdits"`, for bridge-run agents. |
| `approvalTimeoutSec` | `300` | Auto-deny after this many seconds. |
| `claudeBin` | *(PATH)* | Full path to `claude`. |
| `showPcChats` | `true` | Show desktop chats on the watch. |
| `pcChatsMaxAgeDays` | `30` | How far back to list chats. |
| `useDesktopMetadata` | `true` | Use the desktop app's titles and archived flag. |

Full reference, including files and limits: [docs/configuration.md](../docs/configuration.md).

## Ports

| Port | Bound to | Purpose |
| --- | --- | --- |
| 8787 | `bindHost` | HTTP + WebSocket for the watch. Expose on the LAN or with Tailscale. |
| 8788 | 127.0.0.1 | Approval MCP server for bridge-run agents. **Never expose.** |
| 8789 | 127.0.0.1 | Admin listener for `pair` / `devices`, secret in `~/.wearclaude/admin.secret`. **Never expose.** |

## HTTP API

Every route except `/health` and `/pair` requires `Authorization: Bearer <device token>`, including the WebSocket upgrade. Tokens in query strings are not accepted.

| Route | |
| --- | --- |
| `GET /health` | `{ ok, version }` |
| `POST /pair` | `{ code, deviceName }` → `{ token }`; `401 invalid/expired`, `429 locked` |
| `GET /chats` | Visible PC chats |
| `GET /chats/:id` | One chat with its last 60 items |
| `POST /chats/:id/messages` | `{ prompt }` → relay into the open desktop chat; `409 not_live / ambiguous / untitled`, `429 busy`, `502 relay_failed` |
| `GET /projects` | Projects (`id`, `name`) |
| `GET /agents`, `POST /agents` | List agents (with `X-Bridge-Boot` / `X-Bridge-Seq` headers) or start one: `{ projectId, prompt }` |
| `GET /agents/:id` | Agent, recent events, pending approvals |
| `POST /agents/:id/messages`, `POST /agents/:id/stop` | Follow up or stop; `409 busy` while a turn runs |
| `POST /approvals/:id` | `{ decision: "allow" \| "deny" }` |
| `GET /events?since=<seq>` (WebSocket) | Sends `{"type":"hello","bootId","seq"}`, then the backlog after `since` or a `{"type":"reset",…}`, then live events. Pinged every 30 s. |

Prompts are limited to 10,000 characters. Failure reasons sent to the watch have the project path replaced with `<project>` and your home folder with `~`.

## How PC chats and replies work

- **Chats** are read (never written) from `~/.claude/projects/*/<id>.jsonl`. Large files are read only at the start (64 KB) and end (512 KB), then tailed every 2 seconds. Titles and archived state come from the Claude desktop app's per-chat metadata (only id, title, archived flag and last-activity time are read). Credentials files are never read.
- **Replies** run `claude -p --no-session-persistence --tools ListAgents,SendMessage --allowedTools ListAgents,SendMessage --strict-mcp-config` in `<tmp>/wearclaude-relay`. The job finds the one live session whose title matches exactly and sends it `From my watch (dictated by me): <text>`. One relay per chat, three at once, 90-second timeout. The answer reaches the watch through the transcript tail.
- The transcript and metadata formats are undocumented and may change with Claude Code updates. Lines the bridge doesn't understand are ignored.

## Development

```sh
npm run dev            # run from source (tsx)
npx tsc --noEmit       # type-check
npx vitest run         # tests
```

See [CONTRIBUTING.md](../CONTRIBUTING.md). License: [MIT](LICENSE).
