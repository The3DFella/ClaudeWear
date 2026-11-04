# Security Policy

## Threat model summary

ClaudeWear runs a bridge on your PC that starts Claude Code on request from a paired watch.

- **Device token.** Pairing exchanges a short-lived 6-digit code for a long random device token. Only a hash is stored on the PC. The watch sends the token as an `Authorization: Bearer` header (including on the WebSocket). Pairing locks for 5 minutes after 5 bad codes. Lost devices can be revoked with `devices revoke <name>`, which also closes their open connections.
- **Tailscale Funnel exposure.** A Funnel URL is public on the internet and protected only by the device token. Use Tailscale without Funnel (tailnet only) if you do not need public reachability. The LAN connection is plain http; use it on trusted networks only.
- **Local-only listeners.** The approval MCP listener (port+1) and the admin listener (port+2, used by the `pair` command) are meant for the local machine only and require a per-run secret. Never expose them.
- **Relay limits.** Replies to PC chats go through a short headless `claude` job restricted to Claude Code's cross-session messaging tools. It cannot approve anything, runs in a temp directory, and is limited to one message per chat and three at once.
- **Approvals.** Risky tool calls wait for an explicit approve or deny on the watch and auto-deny after 5 minutes.
- **Credentials.** The bridge never reads, copies or logs Claude credentials or `ANTHROPIC_API_KEY`; it runs the unmodified `claude` binary. It reads Claude Code's local chat transcripts (read-only) to show PC chats.
- **Trusted folders only.** `claude -p` runs a project's hooks and `.mcp.json` without a trust prompt, so only add projects you trust.

## Reporting a vulnerability

Please report privately through GitHub security advisories: <https://github.com/The3DFella/ClaudeWear/security/advisories/new>. Do not open a public issue for security problems. Include steps to reproduce and the affected version.
