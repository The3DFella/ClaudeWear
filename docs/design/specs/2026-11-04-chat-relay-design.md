# Dictate into PC chats via cross-session relay — Design Addendum

Date: 2026-11-04
Status: Approved in chat
Extends: `2026-11-04-pc-chats-readonly-design.md`

## Goal
From a PC chat's screen on the watch, dictate a message that arrives in that chat in the Claude desktop app. The answer appears there, and the watch shows it live through the existing chat tailing.

## Why this route
- Appending to a transcript with `claude -p --resume` creates branches the open desktop app doesn't show.
- Remote Control has no third-party protocol.
- Channels only work for terminal sessions started with a launch flag.
- Cross-session messaging (documented Claude Code feature: `ListAgents` + `SendMessage`; a per-session named pipe on Windows) delivers into a *live* session. The spike on 2026-11-04 confirmed this: a desktop chat received the message, answered in its own UI, and the answer was written to the transcript.

## Accepted limits (shown in the UI and docs)
- Only chats currently open or running in the desktop app (live peers) can receive messages.
- The receiving Claude treats the message as a teammate's request, not as typed by the user. It can't approve permission prompts; those still appear on the PC.
- Each message costs one small extra Claude call (the relay job).
- Chats without a title can't be targeted, because matching is by title.

## Bridge

### `relay.ts`
`relayToChat(o: { title: string; text: string; claudeCmd; cwd: string; env?; timeoutMs = 90_000 }): Promise<'sent'|'not_found'|'ambiguous'|'failed'>`

It spawns the official `claude` with:
```
-p --no-session-persistence --output-format json --max-turns 6 \
   --tools "ListAgents,SendMessage" --allowedTools "ListAgents,SendMessage" \
   --strict-mcp-config --permission-mode default
```
- `cwd` is a dedicated temp dir.
- The prompt goes on stdin.
- Never use `--bare`, because messaging needs the normal session.

The stdin prompt is fixed text:
- Call ListAgents.
- Find the peer session whose title equals the JSON-encoded title exactly, among sessions that are not offline.
- If there are zero matches, answer `NOT_FOUND`. If there is more than one, answer `AMBIGUOUS`.
- Otherwise call SendMessage to that peer with the JSON-encoded message, verbatim, and answer `SENT`.
- Do nothing else.

The result is mapped from the final `result` text, by its first token. A non-zero exit, a timeout or an `is_error` result maps to `failed`.

The delivered message is:
```
From my watch (dictated by me): <text>

Reply here in this chat as usual — I'm reading this chat on my watch. Don't message the sender back; it has already closed.
```

### Route
`POST /chats/:id/messages {prompt}` (auth required; prompt must be non-empty and at most 10,000 chars):
- 404 `not_found` for an unknown id.
- 409 `{error:'untitled'}` when the chat has no custom title.
- 429 `{error:'busy'}` while a relay for this chat is in flight. There is one relay per chat at a time, and at most 3 relays in flight overall.
- Otherwise run the relay:
  - `sent` → 200 `{ok:true}`
  - `not_found` → 409 `{error:'not_live'}`
  - `ambiguous` → 409 `{error:'ambiguous'}`
  - `failed` → 502 `{error:'relay_failed'}`
- When `showPcChats` is false, the route returns 404.

`ChatInfo` gains `titled: boolean`, which is true when the title comes from a custom title rather than the first prompt.

### Transcript display
Prompts that wrap a `<cross-session-message …>…</cross-session-message>` are unwrapped:
- If the sender's `from-name` starts with `wearclaude-relay` and the inner text starts with `From my watch (dictated by me):`, the item becomes a `prompt` with text `You (watch): <dictated text>`, with the instruction paragraph removed.
- Any other peer message becomes `prompt` text `Message from <from-name>: <inner text>`.
- The boilerplate the receiver appends after the tag is dropped.

## Watch
- The Chat screen shows a **🎤 Reply** button when `chat.titled` is true. It reuses the Dictate flow with a new route `dictate-chat/{id}`; there is no project chip.
- On send, it calls `POST /chats/:id/messages`, shows a progress indicator during the wait (up to 90 s), then goes back to the chat.
- Errors:
  - `not_live` → "Open this chat in the Claude desktop app, then try again"
  - `ambiguous` → "Several open chats have this title — rename one on your PC"
  - `untitled` → "This chat has no title yet"
  - `busy` → "Still sending your last message"
  - `relay_failed` and other errors → "Couldn't deliver (code)"
- The footer text becomes "Replies go to the open desktop chat as a message from your watch".

## Testing
- Bridge:
  - `relayToChat` tested with fake-claude fixtures for all four outcomes, a timeout, a check that `--bare` is never passed, and a check that the prompt goes in on stdin.
  - Route tests for status mapping and concurrency.
  - Transcript unwrap tests.
- Watch: send-error mapping and the reply button's visibility logic.
- Manual: dictate from the watch into a live desktop chat; the answer appears on the PC and on the watch.
