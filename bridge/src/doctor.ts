import { execFileSync, spawn } from 'node:child_process';
import type { Config } from './config.js';
import { friendlyError } from './normalize.js';

export type ClaudeCmd = { bin: string; preArgs: string[] };
export type CheckResult = { ok: true; version: string } | { ok: false; problem: string };

export const MIN_VERSION = '2.1.316';
const NOT_SIGNED_IN = 'Not signed in. Run `claude`, then type /login.';

export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d < 0 ? -1 : 1;
  }
  return 0;
}

export function resolveClaude(config: Pick<Config, 'claudeBin'>): ClaudeCmd {
  let path = config.claudeBin;
  if (!path) {
    try {
      const out = execFileSync(process.platform === 'win32' ? 'where' : 'which', ['claude'], {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
        timeout: 5000,
      });
      path = out.split(/\r?\n/).map((l) => l.trim()).find(Boolean);
    } catch {
      path = undefined;
    }
  }
  if (!path) path = 'claude';
  if (process.platform === 'win32' && /\.(cmd|bat)$/i.test(path)) {
    return { bin: process.env.ComSpec ?? 'cmd.exe', preArgs: ['/d', '/s', '/c', path] };
  }
  return { bin: path, preArgs: [] };
}

type RunResult = { code: number | null; stdout: string; error?: string; timedOut?: boolean };

function run(cmd: ClaudeCmd, args: string[], stdin: string | null, env: NodeJS.ProcessEnv | undefined, timeoutMs: number): Promise<RunResult> {
  return new Promise((resolve) => {
    let stdout = '';
    let settled = false;
    const finish = (r: RunResult) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(r);
    };
    const child = spawn(cmd.bin, [...cmd.preArgs, ...args], {
      stdio: ['pipe', 'pipe', 'ignore'],
      windowsHide: true,
      env: { ...process.env, ...env },
    });
    const timer = setTimeout(() => {
      child.kill();
      finish({ code: null, stdout, timedOut: true });
    }, timeoutMs);
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (c: string) => (stdout += c));
    child.on('error', (e) => finish({ code: null, stdout, error: e.message }));
    child.on('close', (code) => finish({ code, stdout }));
    child.stdin.on('error', () => {});
    child.stdin.end(stdin ?? '');
  });
}

/** Finds the `result` object in `--output-format json` output (object, array, or NDJSON). */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function parseResult(stdout: string): any {
  const t = stdout.trim();
  try {
    const v = JSON.parse(t);
    if (Array.isArray(v)) return [...v].reverse().find((x) => x?.type === 'result') ?? null;
    return v;
  } catch {
    // maybe NDJSON: take the last result line
  }
  for (const line of t.split('\n').reverse()) {
    try {
      const v = JSON.parse(line.trim());
      if (v?.type === 'result') return v;
    } catch {
      // keep looking
    }
  }
  return null;
}

export async function checkClaude(cmd: ClaudeCmd, env?: NodeJS.ProcessEnv, skipProbe = false): Promise<CheckResult> {
  const v = await run(cmd, ['--version'], null, env, 15_000);
  if (v.error || v.timedOut || v.code !== 0) {
    return { ok: false, problem: 'Could not run `claude --version`. Is Claude Code installed? Set claudeBin in config.json if needed.' };
  }
  const m = /\d+\.\d+\.\d+/.exec(v.stdout);
  if (!m) return { ok: false, problem: `Could not read the claude version from: ${v.stdout.trim().slice(0, 80)}` };
  const version = m[0];
  if (compareVersions(version, MIN_VERSION) < 0) {
    return { ok: false, problem: `claude ${version} is too old. Update to ${MIN_VERSION} or newer.` };
  }

  if (skipProbe) return { ok: true, version };

  const probeArgs = ['-p', '--output-format', 'json', '--max-turns', '1', '--no-session-persistence'];
  const p = await run(cmd, probeArgs, 'Reply with OK', env, 90_000);
  if (p.error || p.timedOut) {
    return { ok: false, problem: p.timedOut ? 'The claude probe timed out after 90 seconds.' : `Could not run claude: ${p.error}` };
  }
  const result = parseResult(p.stdout);
  if (result && result.is_error === true) {
    const msg = friendlyError(result);
    if (msg.startsWith('Not signed in')) return { ok: false, problem: NOT_SIGNED_IN };
    return { ok: false, problem: `claude probe failed: ${msg}` };
  }
  if (!result && p.code !== 0) {
    return { ok: false, problem: `claude probe exited with code ${p.code}.` };
  }
  return { ok: true, version };
}
