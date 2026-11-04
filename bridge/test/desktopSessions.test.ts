import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DesktopSessions } from '../src/desktopSessions.js';

const sid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
let root: string;
let clock: number;
beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'wc-desk-'));
  clock = 1_000_000;
});
afterEach(() => rmSync(root, { recursive: true, force: true }));

function put(name: string, body: unknown, sub = 'org/acct') {
  mkdirSync(join(root, sub), { recursive: true });
  const f = join(root, sub, name);
  writeFileSync(f, typeof body === 'string' ? body : JSON.stringify(body));
  return f;
}
const mk = () => new DesktopSessions({ roots: [root], now: () => clock });

describe('DesktopSessions', () => {
  it('extracts only title, archived, lastActivityAt keyed by cliSessionId', () => {
    put('local_aaa-1.json', { cliSessionId: sid(1), title: ' My chat ', isArchived: true, lastActivityAt: 5000, cwd: '/secret', permissionMode: 'x', bridgeSessionIds: ['q'] });
    const m = mk().lookup();
    expect(m.get(sid(1))).toEqual({ title: 'My chat', archived: true, lastActivityAt: 5000 });
    expect(JSON.stringify([...m])).not.toMatch(/secret|permissionMode|bridgeSessionIds/);
  });

  it('defaults archived to false and tolerates junk, oversize, wrong names and symlinks', () => {
    put('local_bbb.json', { cliSessionId: sid(2) });
    put('local_ccc.json', '{not json');
    put('local_ddd.json', JSON.stringify({ cliSessionId: sid(3), title: 'x'.repeat(300_000) }));
    put('other.json', { cliSessionId: sid(4), title: 'nope' });
    put('local_eee.json', { cliSessionId: 'not-an-id', title: 'bad' });
    put('local_fff.json', { cliSessionId: sid(6), title: 'deep' }, 'a/b/c/d');
    const real = put('local_a77.json', { cliSessionId: sid(7), title: 'linked' }, 'elsewhere');
    try {
      symlinkSync(real, join(root, 'org', 'acct', 'local_a88.json'));
    } catch {
      // symlinks unavailable
    }
    const m = mk().lookup();
    expect(m.get(sid(2))).toEqual({ archived: false });
    expect(m.has(sid(3))).toBe(false);
    expect(m.has(sid(4))).toBe(false);
    expect(m.has(sid(6))).toBe(false);
    expect(m.size).toBe(2); // sid(2) and the 'elsewhere' file (depth 1)
  });

  it('newer lastActivityAt wins for duplicate sessions', () => {
    put('local_a1.json', { cliSessionId: sid(1), title: 'old', lastActivityAt: 1 });
    put('local_a2.json', { cliSessionId: sid(1), title: 'new', lastActivityAt: 2 }, 'org/other');
    expect(mk().lookup().get(sid(1))?.title).toBe('new');
  });

  it('rescans at most every 10 s and caches per file', () => {
    const f = put('local_a1.json', { cliSessionId: sid(1), title: 'one' });
    const d = mk();
    expect(d.lookup().get(sid(1))?.title).toBe('one');
    writeFileSync(f, JSON.stringify({ cliSessionId: sid(1), title: 'two!' }));
    clock += 9_000;
    expect(d.lookup().get(sid(1))?.title).toBe('one');
    clock += 2_000;
    expect(d.lookup().get(sid(1))?.title).toBe('two!');
    rmSync(f);
    clock += 11_000;
    expect(d.lookup().size).toBe(0);
  });

  it('missing roots yield an empty map', () => {
    expect(new DesktopSessions({ roots: [join(root, 'nope')] }).lookup().size).toBe(0);
  });
});
