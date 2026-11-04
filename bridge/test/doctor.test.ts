import { describe, it, expect } from 'vitest';
import { resolve } from 'node:path';
import { checkClaude, compareVersions } from '../src/doctor.js';

const cmd = { bin: process.execPath, preArgs: [resolve('test/fake-claude.mjs')] };
const okLine = JSON.stringify({ type: 'result', subtype: 'success', is_error: false, result: 'OK' });

describe('compareVersions', () => {
  it('compares numerically', () => {
    expect(compareVersions('2.1.100', '2.1.316')).toBeLessThan(0);
    expect(compareVersions('2.1.317', '2.1.316')).toBeGreaterThan(0);
    expect(compareVersions('2.10.0', '2.9.9')).toBeGreaterThan(0);
    expect(compareVersions('2.1.316', '2.1.316')).toBe(0);
  });
});

describe('checkClaude', () => {
  it('rejects old versions', async () => {
    const r = await checkClaude(cmd, { FAKE_CLAUDE_VERSION: '2.1.100' });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.problem).toContain('2.1.316');
  });

  it('reports not signed in on auth failure', async () => {
    const r = await checkClaude(cmd, {
      FAKE_CLAUDE_VERSION: '2.1.316',
      FAKE_CLAUDE_FIXTURE: resolve('test/fixtures/failed-auth.ndjson'),
    });
    expect(r).toEqual({ ok: false, problem: 'Not signed in. Run `claude`, then type /login.' });
  });

  it('succeeds with a good version and probe', async () => {
    const { writeFileSync, mkdtempSync } = await import('node:fs');
    const { tmpdir } = await import('node:os');
    const d = mkdtempSync(resolve(tmpdir(), 'wc-doc-'));
    const f = resolve(d, 'ok.ndjson');
    writeFileSync(f, okLine + '\n');
    const r = await checkClaude(cmd, { FAKE_CLAUDE_VERSION: '2.1.316', FAKE_CLAUDE_FIXTURE: f });
    expect(r).toEqual({ ok: true, version: '2.1.316' });
  });

  it('probes without creating a session', async () => {
    const { writeFileSync, mkdtempSync, readFileSync } = await import('node:fs');
    const { tmpdir } = await import('node:os');
    const d = mkdtempSync(resolve(tmpdir(), 'wc-doc-'));
    const f = resolve(d, 'ok.ndjson');
    const argsFile = resolve(d, 'args.json');
    writeFileSync(f, okLine + '\n');
    // --version also records args; the probe call runs last and overwrites them
    await checkClaude(cmd, { FAKE_CLAUDE_VERSION: '2.1.316', FAKE_CLAUDE_FIXTURE: f, FAKE_CLAUDE_ARGS_FILE: argsFile });
    expect(JSON.parse(readFileSync(argsFile, 'utf8'))).toContain('--no-session-persistence');
  });

  it('reports a missing binary', async () => {
    const r = await checkClaude({ bin: 'definitely-not-a-real-binary-xyz', preArgs: [] });
    expect(r.ok).toBe(false);
  });
});
