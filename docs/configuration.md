# Configuration

Everything the bridge stores lives in one folder on your PC. You only need to touch it if the defaults don't suit you.

> **About the names.** The project is called **ClaudeWear**. A few internal identifiers still use the older spelling `wearclaude` (the `wearclaude-bridge` command, the `~/.wearclaude` folder, the Android package `dev.wearclaude.watch`). They are deliberately kept stable so existing installs and paired watches keep working. See [RENAMING.md](RENAMING.md).

## The data folder

| Platform | Default location |
| --- | --- |
| Windows | `%USERPROFILE%\.wearclaude` (for example `C:\Users\you\.wearclaude`) |
| macOS | `~/.wearclaude` |
| Linux | `~/.wearclaude` |

Set the `WEARCLAUDE_HOME` environment variable to use a different folder.

| File | What it is | Safe to delete? |
| --- | --- | --- |
| `config.json` | Your settings (below). Created the first time you run `wearclaude-bridge add-project`; until then the defaults are used. | Yes. You get the defaults back and lose your project list. |
| `devices.json` | Paired watches: name, creation time and a SHA-256 **hash** of each device token. The token itself is never stored on the PC. | Yes, with the bridge stopped. Every watch then has to pair again. |
| `agents.json` | Agents the bridge has started (status, last step, summary). Runs that were still going when the bridge stopped are marked `interrupted` on the next start. | Yes, with the bridge stopped. |
| `admin.secret` | Random secret for the local admin listener, written at every start and removed on a clean shutdown. Used by `wearclaude-bridge pair` and `devices`. | It is recreated on the next start. |

## `config.json`

`config.json` is plain JSON. Any key you leave out uses its default. Restart the bridge after editing it.

```json
{
  "port": 8787,
  "bindHost": "0.0.0.0",
  "projects": [],
  "defaultPermissionMode": "default",
  "approvalTimeoutSec": 300,
  "showPcChats": true,
  "pcChatsMaxAgeDays": 30,
  "useDesktopMetadata": true
}
```

| Key | Default | Meaning |
| --- | --- | --- |
| `port` | `8787` | The port the watch connects to. The two local-only helper listeners use the next two ports (see [Ports](#ports)). |
| `bindHost` | `"0.0.0.0"` | Address the main port listens on. `0.0.0.0` accepts connections from your LAN. Set `"127.0.0.1"` if you only use Tailscale Funnel or Serve, which forward to localhost. |
| `projects` | `[]` | Folders the bridge may run its own Claude Code agents in. Manage it with `wearclaude-bridge add-project` rather than by hand. Each entry is `{ "id", "name", "path" }`. |
| `defaultPermissionMode` | `"default"` | Permission mode for agents the bridge runs: `"default"` (risky tool calls ask for approval on the watch) or `"acceptEdits"` (file edits are allowed without asking). Does not affect your desktop chats. |
| `approvalTimeoutSec` | `300` | How long an approval waits for your answer before it is automatically denied. |
| `claudeBin` | *(unset)* | Full path to the `claude` executable. When unset, the bridge finds `claude` on your `PATH` (`where claude` on Windows, `which claude` elsewhere). Set this if the bridge says it can't run `claude --version`, or when you start the bridge from a service manager with a minimal `PATH`. |
| `showPcChats` | `true` | Show your Claude Code chats from this PC on the watch. `false` hides them and turns off the `/chats` routes, including reply by voice. |
| `pcChatsMaxAgeDays` | `30` | Only chats active within this many days are listed. Invalid values (not a positive number) fall back to `30`. |
| `useDesktopMetadata` | `true` | Read chat titles and the archived flag from the Claude desktop app's per-chat metadata. `false` uses titles from the transcript only and shows every chat as active. |

A config file that is not valid JSON stops the bridge with `Invalid config at <path>: <reason>`.

### Example: a Windows `claudeBin`

JSON needs backslashes doubled, or use forward slashes:

```json
{
  "claudeBin": "C:/Users/you/.local/bin/claude.exe"
}
```

If `claudeBin` points at a `.cmd` or `.bat` file (as the npm-installed `claude` does on Windows), the bridge runs it through `cmd.exe` for you.

## Ports

| Port (default) | Listens on | Used for | Expose it? |
| --- | --- | --- | --- |
| `port` (8787) | `bindHost` (all interfaces by default) | HTTP API and WebSocket for the watch | Yes: LAN, or Tailscale Funnel/Serve |
| `port + 1` (8788) | `127.0.0.1` only | Approval MCP server that the bridge's own agents call to ask the watch | **Never** |
| `port + 2` (8789) | `127.0.0.1` only | Admin listener used by `wearclaude-bridge pair` / `devices` | **Never** |

Changing `port` moves all three. The watch must then use the new port in its bridge address.

## Environment variables

| Variable | Effect |
| --- | --- |
| `WEARCLAUDE_HOME` | Use this folder instead of `~/.wearclaude`. Set it the same way for the running bridge and for CLI commands like `pair`, or they won't find each other. |
| `PATH` | Used to find `claude` when `claudeBin` is not set. |
| `LOCALAPPDATA`, `APPDATA` (Windows) | Used to locate the Claude desktop app's chat metadata. You don't normally set these yourself. |

Everything else in the environment is passed through unchanged to the `claude` processes the bridge starts. That is how an `ANTHROPIC_API_KEY` you have set reaches Claude Code; the bridge itself never reads it.

## Files the bridge reads (but never writes)

| Path | Why |
| --- | --- |
| `~/.claude/projects/*/<id>.jsonl` | Claude Code chat transcripts, to list your PC chats and tail new lines. For large files only the first 64 KB and last 512 KB are read. |
| `~/.claude/projects/*/<id>/custom-title.json` | A chat's custom title, if it has one. |
| Claude desktop app `claude-code-sessions` folders (only when `useDesktopMetadata` is on) | Only four fields from each `local_*.json`: the chat id, title, archived flag and last-activity time. Looked for in `%LOCALAPPDATA%\Claude-Data\*\claude-code-sessions`, `%APPDATA%\Claude\claude-code-sessions`, `~/Library/Application Support/Claude/claude-code-sessions` and `~/.config/Claude/claude-code-sessions` (whichever exist). |

The bridge never reads Claude Code's login or credentials files. Chats whose working directory is inside your system temp folder are hidden, which also hides the bridge's own relay jobs (they run in `<tmp>/wearclaude-relay`).

## Built-in limits

Fixed in code, not configurable:

| Limit | Value |
| --- | --- |
| Pairing code lifetime | 5 minutes, single use |
| Pairing lockout | 5 wrong codes within 15 minutes lock pairing for 5 minutes (already-paired watches are unaffected) |
| Chats listed | Up to 100 active and 50 archived |
| Messages per chat on the watch | The most recent 60 |
| A chat counts as "working" | Its transcript changed in the last 60 seconds and the turn hasn't ended (up to 10 minutes while a tool or permission prompt is pending) |
| Reply by voice | One message per chat at a time, at most 3 at once; each relay job times out after 90 seconds |
| Prompt length | 10,000 characters |
| WebSocket keep-alive | Ping every 30 seconds; a socket that misses a pong is dropped |
