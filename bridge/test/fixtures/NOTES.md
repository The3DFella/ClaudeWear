# claude CLI contract notes (spike, claude v2.1.316, Windows 11)

All fixtures are real `claude -p --output-format stream-json --verbose` stdout, recorded with the Max subscription login.
Scrubbed: absolute paths replaced with `C:\scratch\project`, `C:\scratch\emptycfg`, `C:\scratch\claude-tmp`, `C:\Users\<user>`.
Grepped for `@`, `sk-ant`, `token`, bearer/account/org/email keys: only `*@builtin` plugin ids and token-count fields remain. No credentials.
Session ids, uuids, rate-limit utilization numbers and costs are untouched (not secret).
`system/init` lines are trimmed to `type`, `subtype`, `cwd` (placeholder), `session_id`, `tools`, `model` (plugin lists, plugin paths,
memory paths and account fields removed); `system/hook_response` `output`/`stdout`/`stderr` are blanked (plugin-injected content).
The field lists below describe the original, untrimmed CLI output.

Command used (prompt on stdin, cwd = scratch project, never `--bare`):

    claude -p --output-format stream-json --verbose --session-id <uuid> \
      --mcp-config <file-or-json> --permission-prompt-tool mcp__wearclaude__approve --permission-mode default

## Fixtures
| file | prompt | notes |
|---|---|---|
| simple-text.ndjson | "Say hi in five words." | plain run, ends in `result` |
| approve-write.ndjson | "List the files here, then create hello.txt containing hi" | `approve` called once (for `Write`), allowed |
| approve-deny.ndjson | "Create deny.txt containing hi. If denied, say so and stop." | `approve` returned deny |
| subagent.ndjson | "Use a subagent (Agent tool) to count the files..." | Agent tool + subagent messages |
| failed-auth.ndjson | "say hi" with `CLAUDE_CONFIG_DIR=<empty dir>` | exit code 1, 3 lines |

## Flags
- `--permission-mode default` is ACCEPTED by 2.1.316 and works (approve was invoked for Write). NOTE: the CLI validator lists only
  `acceptEdits, auto, bypassPermissions, manual, dontAsk, plan`, and an invalid value such as `bogus` or `Manual` (capital) errors with that
  list (stderr, no stdout) -- yet `default` is silently accepted. `manual` (lowercase) is also accepted and also routes to the approve tool.
  Recommendation: use `default` (init reports `"permissionMode":"default"`) but be ready to fall back to `manual` if a later version drops it.
- `--permission-prompt-tool mcp__wearclaude__approve` with an MCP server named `wearclaude`, tool `approve`: works. In `system/init`,
  `mcp_servers` contains `{"name":"wearclaude","status":"connected","source":"dynamic"}`.
- `--mcp-config` accepts a file path (used) containing `{"mcpServers":{"wearclaude":{"command":"node","args":[...]}}}`. Inline JSON and
  `type:"http"` servers were not tested (spike used stdio; the spec's Streamable HTTP is untested against the CLI).
- Prompt via stdin works. `--session-id <uuid>` is honoured: every line carries `session_id` equal to the passed uuid.
- Stdout is clean NDJSON, one JSON object per line. stderr was empty in all successful runs.

## Permission tool contract (§4.4) -- CONFIRMED
Claude calls tool `approve` with exactly these arguments (captured in the MCP handler):

    {"tool_name":"Write","input":{"file_path":"C:\\...\\hello.txt","content":"hi"},"tool_use_id":"toolu_01JL9TkSdkgs9h4fi3SLcyVU"}

Fields: `tool_name` (string), `input` (the tool's input object), `tool_use_id` (matches the `tool_use` block id in the stream).
The MCP request `_meta` also carries `{"progressToken":2,"claudecode/toolUseId":"<same id>"}`.

Return value: a single MCP content block of type `text` whose text is the JSON-stringified decision:
- allow: `{"behavior":"allow","updatedInput":<input object>}` -> tool executes. Stream shows the normal `user` tool_result
  ("File created successfully at: ...") and the file exists.
- deny: `{"behavior":"deny","message":"Denied from watch"}` -> tool does NOT execute. Stream shows a `user` message with
  `tool_result` `{"content":"Denied from watch","is_error":true}`, `tool_use_result:"Error: Denied from watch"`, and
  `tool_result_meta[0].permission_decision = {"decision":"reject","source":"user_reject"}`. Claude then continues (assistant text
  acknowledging the denial) and the run still ends with a normal `result` (`subtype:"success"`, `is_error:false`). A denial does not fail the run.
- The call blocks claude until it returns (normal MCP tools/call; no short timeout observed).
- The permission request does NOT appear as a distinct stream line: the stream shows the assistant `tool_use` (Write), then after the
  decision the `user` tool_result. The bridge must emit `approval_requested` from its own MCP server, not from the stream.
- Read-only tool use (PowerShell `Get-ChildItem`) did NOT trigger approve in `default` mode; Write did.

## Stream shapes (see fixtures for full examples)
Normal run: `system/hook_started`, `system/hook_response` (SessionStart hooks, environment dependent -- ignore), `system/init`,
`assistant` (text / `tool_use` / `thinking` with empty text), `rate_limit_event`, `system/task_summary`, `user` (tool_result), ...,
`system/post_turn_summary`, `result`, and a trailing `system/task_summary` AFTER `result` (do not treat `result` as the last line).
- `system/init`: `cwd`, `session_id`, `tools`, `mcp_servers`, `model`, `permissionMode`, `apiKeySource`, `claude_code_version`.
- assistant: `message.content[]` blocks `text` | `thinking` | `tool_use {id,name,input}`; each content block arrives as its own `assistant`
  line (same `message.id`). `parent_tool_use_id` is `null` for main-agent lines.
- Tool names seen: `Write`, `Agent`, `Bash` (inside subagent), and **`PowerShell`** (the main agent used PowerShell, not Bash, on this
  Windows box: `{"name":"PowerShell","input":{"command":"Get-ChildItem -Force | ..."}}`). The normalizer label table must treat `PowerShell`
  like `Bash`.
- result: `{"type":"result","subtype":"success","is_error":false,"result":"<text>","session_id","total_cost_usd","duration_ms","num_turns","stop_reason","terminal_reason","usage","permission_denials":[]}`.
  Small runs cost ~$0.15-0.45 notional (large system prompt cache creation).
- `system/task_summary` (`detail`) and `system/post_turn_summary` (`status_detail`, e.g. "listed 2 files; created hello.txt containing hi")
  are extra human-readable progress strings usable by the normalizer.
- `rate_limit_event` appears.

### Unobserved shapes (NOT captured in this spike)
- `system/api_retry`: never observed. Shape below is from the official docs, NOT captured: `type:"system"`, `subtype:"api_retry"`,
  `attempt`, `max_retries`, `retry_delay_ms`, `error_status` (int|null), `error` (string category, e.g. `rate_limit`, `overloaded`,
  `authentication_failed`, `billing_error`, `server_error`, `unknown`), `uuid`, `session_id`. The normalizer must handle it defensively
  (any field may be missing; unknown `error` categories fall back to a generic "Retrying..." label).
- `result` subtypes other than `success` (e.g. `error_max_turns`, `error_during_execution`, or any unknown subtype) and mid-run
  `is_error:true` results were not observed (only the auth failure above). Normalizer rule: treat `is_error === true` OR
  `subtype !== "success"` as failure; tolerate unknown subtypes.
- Fixture files may have CRLF line endings after checkout (git autocrlf); parsers must strip a trailing `\r` from each line.

## Subagents (subagent.ndjson)
- Main agent calls tool `Agent` (not `Task`) with `input {description, subagent_type, prompt}`.
- `system/task_started` (`task_id`, `tool_use_id` = the Agent tool_use id, `description`, `subagent_type`), `system/task_progress`
  (`description`, `last_tool_name`, `usage`), `system/task_updated`, `system/task_notification` (`status:"completed"`, `summary`).
- The subagent's own `assistant`/`user` lines carry `parent_tool_use_id` = the Agent `tool_use` id (and `agent_id`). Its initial prompt also
  appears as a `user` line with `parent_tool_use_id` set. The final Agent `tool_result` (parent null) has a text block starting
  "[Subagent hand-back] ...".
- Confirms the spec: subagent messages are those with non-null `parent_tool_use_id`.

## Failed auth (failed-auth.ndjson)
`CLAUDE_CONFIG_DIR=<fresh empty dir>`: no hang, no prompt. Exits in ~150 ms with **exit code 1**, 3 stdout lines, empty stderr:
1. `system/init` (`apiKeySource:"none"`)
2. `assistant` synthetic (`model:"<synthetic>"`, text `"Not logged in · Please run /login"`, top-level `"error":"authentication_failed"`)
3. `result` with `"is_error":true`, **`"subtype":"success"` (misleading)**, `"result":"Not logged in · Please run /login"`,
   `"terminal_reason":"api_error"`, `"total_cost_usd":0`.
=> Failure detection must use `is_error:true` (plus assistant `error:"authentication_failed"` for the auth case), NOT `subtype`.
(The temp config dir got populated with backups/plugins/projects/sessions folders; the real config was untouched.)

## Resume
Run 1: `--session-id 75b3b632-...` (approve-write run). Run 2 (same cwd): `--resume 75b3b632-...`, prompt "What was the name of the file you
created earlier?" -> answered `hello.txt`; `session_id` stayed `75b3b632-...` (no fork). Works with the same `--mcp-config` / permission
flags. `total_cost_usd` in the resumed `result` is cumulative for the session (0.4126 -> 0.4232). Not saved as a fixture.
Resume must be run from the same cwd as the original.

## Windows stop behaviour (claude.exe spawned from Node)
Prompt: count 1..50 with Bash `sleep 1`; stopped 15 s in (Node `child_process.spawn`):
- `child.kill()` (SIGTERM): returns true; exit event `code=null, signal=SIGTERM`, immediate. NO `result` line (stdout just stops mid-run).
  On Windows this is TerminateProcess, a hard kill of that one pid only.
- `child.kill("SIGINT")`: returns true; also a hard terminate on Windows: `code=null, signal=SIGINT`, immediate, NO `result` line. It is NOT a
  graceful "end the turn" as on POSIX. The spec's "SIGINT, escalate after 10 s" does not work as designed on Windows.
- `taskkill /PID <pid> /T /F`: terminated a 9-process tree (claude.exe, its MCP server node process, shells, sleep); Node saw
  `code=1, signal=null`; NO `result` line.
- Conclusion: after any stop the bridge must synthesise the terminal state itself (status stopped/cancelled). Prefer `taskkill /T /F` on win32
  to avoid orphaning the MCP server / tool subprocesses. A graceful stop (closing stdin, control messages) was not tested.

## Divergences from the spec / carry into later tasks
1. Stop: SIGINT is not graceful on Windows; no `result` line is emitted after any kill (§4.3).
2. Failed run detection: `result.subtype` is `"success"` even when `is_error:true`; key off `is_error`.
3. Tool name `PowerShell` exists alongside `Bash`; subagent tool is `Agent`.
4. `--permission-mode default` works but is not in the CLI's advertised choices (`manual` is the advertised equivalent and also works).
5. `result` is followed by one more `system/task_summary` line.
6. User SessionStart hooks/plugins add `system/hook_*` lines and bulky content; the parser must ignore unknown `system` subtypes.
No divergence in the permission tool contract itself (§4.4).
