# Chat Relay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Dictate from the watch into a live Claude desktop chat using Claude Code cross-session messaging.

**Architecture:** The bridge runs a tiny `claude -p` relay job that only has `ListAgents` and `SendMessage`. The job finds the live peer session by title and delivers the wrapped text. The answer shows up on the watch through the existing transcript tailing.

**Tech Stack:** As in the main plan.

**Spec:** `docs/design/specs/2026-11-04-chat-relay-design.md`

## Global Constraints
- Relay args are exactly `-p --no-session-persistence --output-format json --max-turns 6 --tools "ListAgents,SendMessage" --allowedTools "ListAgents,SendMessage" --strict-mcp-config --permission-mode default`.
  - Never pass `--bare`.
  - The prompt goes in on stdin.
  - `cwd` is a dedicated temp dir.
  - Timeout is 90 s.
- Use the exact message wrapper from the spec.
- HTTP mapping:
  - sent → 200 `{ok:true}`
  - not_found → 409 `not_live`
  - ambiguous → 409 `ambiguous`
  - untitled → 409 `untitled`
  - busy → 429
  - failed → 502 `relay_failed`
- Concurrency: one relay per chat, and at most 3 in flight overall.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- The live bridge runs on ports 8787-8789. Tests use port 0 and temp dirs.

## Review Focus
1. The relay prompt can't be steered by the user's text into other actions. Only two tools are allowed, the text is JSON-encoded, and the model may answer only with a fixed outcome token.
2. A hung relay never blocks other chats. The timeout kills the whole process tree, as in agents.ts.
3. The watch shows "You (watch): …", not the raw cross-session wrapper.

### Task 22: Bridge relay + route + transcript unwrap
**Files:**
- Create: `bridge/src/relay.ts`.
- Modify:
  - `server.ts`: add the route.
  - `chatIndex.ts`: add `titled`.
  - `transcript.ts`: unwrap cross-session messages.
  - `cli.ts`: wiring.
- Tests: `bridge/test/relay.test.ts`, `server.test.ts`, `transcript.test.ts`, `chatIndex.test.ts`, plus fake-claude modes as needed.

**Tests:**
- The relay maps SENT, NOT_FOUND, AMBIGUOUS, garbage text, `is_error`, non-zero exit and timeout correctly.
- The relay args equal the constant and never include `--bare`.
- The stdin prompt contains the JSON-encoded title and message.
- Route status codes, the per-chat lock returning 429, the global cap of 3, and untitled returning 409.
- Unwrap:
  - A watch message becomes `You (watch): …`.
  - Another peer's message becomes `Message from <name>: …`.
  - The appended boilerplate is dropped.

### Task 23: Watch reply flow
**Files:**
- Modify:
  - `data/Models.kt`: add `titled`.
  - `BridgeApi.kt`: add `sendToChat(id, prompt)`.
  - `AgentCommands`: add the same method.
  - `ui/ChatScreen.kt`: Reply button, footer text.
  - `AppNav.kt`: route `dictate-chat/{id}`.
  - `DictateScreen.kt`: chat mode with no project chip.
  - `DictateLogic.kt`: pure `chatSendErrorMessage`.
- Tests:
  - Error mapping, using the exact copy from the spec.
  - The Reply button is visible only when the chat is titled.
  - The API posts the correct body and path.

### Task 24: Docs + on-watch check
- Add a README section, "Reply to PC chats (relay)", covering how it works, its limits and its cost.
- Manual check with the user.
