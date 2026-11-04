# Changelog

## v0.1.0 - 2026-11-05

First public release.

No prebuilt APK yet: build the watch app from source (see docs/setup.md), or download the debug APK artifact from a CI run if you are logged in to GitHub.

- Pairing: 6-digit code, per-device tokens stored hashed, revoke from the PC.
- PC chats on Home, grouped by project, with archived chats at the bottom.
- Live progress for working chats and a read-only chat view.
- Finish buzzes when a chat or agent completes.
- Approvals: approve or deny risky tool calls from the watch.
- Reply to PC chats by voice, delivered through the relay into the open desktop chat.
- Remote access over Tailscale (tailnet or Funnel).
- Watch-started agents removed from Home; Home is focused on PC chats.
