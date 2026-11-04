// Fake `claude` CLI for tests. Ignores argv.
import { readFileSync, writeFileSync } from 'node:fs';

if (process.env.FAKE_CLAUDE_VERSION && process.argv.includes('--version')) {
  console.log(process.env.FAKE_CLAUDE_VERSION + ' (Claude Code)');
  process.exit(0);
}

if (process.env.FAKE_CLAUDE_PID_FILE) writeFileSync(process.env.FAKE_CLAUDE_PID_FILE, String(process.pid));
if (process.env.FAKE_CLAUDE_ARGS_FILE) writeFileSync(process.env.FAKE_CLAUDE_ARGS_FILE, JSON.stringify(process.argv.slice(2)));

let stdin = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => (stdin += c));
process.stdin.on('end', async () => {
  if (process.env.FAKE_CLAUDE_STDIN_FILE) writeFileSync(process.env.FAKE_CLAUDE_STDIN_FILE, stdin);
  if (process.env.FAKE_CLAUDE_HANG === '1' || (process.env.FAKE_CLAUDE_HANG_ON_PROMPT && stdin === process.env.FAKE_CLAUDE_HANG_ON_PROMPT)) {
    setInterval(() => {}, 1 << 30);
    return;
  }
  let lines = [];
  if (process.env.FAKE_CLAUDE_RESULT !== undefined) {
    // Single `--output-format json` result object.
    lines = [JSON.stringify({ type: 'result', subtype: 'success', is_error: process.env.FAKE_CLAUDE_IS_ERROR === '1', result: process.env.FAKE_CLAUDE_RESULT })];
  } else if (process.env.FAKE_CLAUDE_ECHO === '1') {
    lines = [JSON.stringify({ type: 'result', subtype: 'success', is_error: false, result: stdin, total_cost_usd: 0 })];
  } else if (process.env.FAKE_CLAUDE_FIXTURE) {
    lines = readFileSync(process.env.FAKE_CLAUDE_FIXTURE, 'utf8').split('\n').filter((l) => l.trim());
  }
  for (let i = 0; i < lines.length; i++) {
    const last = i === lines.length - 1;
    process.stdout.write(lines[i] + (last && process.env.FAKE_CLAUDE_NO_TRAILING_NEWLINE === '1' ? '' : '\n'));
    await new Promise((r) => setTimeout(r, 5));
  }
  if (process.env.FAKE_CLAUDE_STDERR) process.stderr.write(process.env.FAKE_CLAUDE_STDERR);
  if (process.env.FAKE_CLAUDE_LINGER_MS) await new Promise((r) => setTimeout(r, Number(process.env.FAKE_CLAUDE_LINGER_MS)));
  process.exitCode = Number(process.env.FAKE_CLAUDE_EXIT_CODE ?? 0);
  process.exit();
});
