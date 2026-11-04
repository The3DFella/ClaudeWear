# Changelog

## v0.1.1 - 2026-11-05

### Fixed

- The finish buzz now works while the app is open, after replying to a chat, and in the background.

## v0.1.0 - 2026-11-05

First public release.

A prebuilt, signed APK (`ClaudeWear-0.1.0.apk`, with a SHA-256 checksum) is available on the Releases page; see docs/setup.md. You can still build from source.

- Pairing: 6-digit code, per-device tokens stored hashed, revoke from the PC.
- PC chats on Home, grouped by project, with archived chats at the bottom.
- Live progress for working chats and a read-only chat view.
- Finish buzzes when a chat or agent completes.
- Approvals: approve or deny risky tool calls from the watch.
- Reply to PC chats by voice, delivered through the relay into the open desktop chat.
- Remote access over Tailscale (tailnet or Funnel).
- Watch-started agents removed from Home; Home is focused on PC chats.
