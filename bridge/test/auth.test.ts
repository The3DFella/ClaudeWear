import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DeviceAuth } from '../src/auth.js';

let home: string;
let prevHome: string | undefined;
let t = 1_000_000;
const now = () => t;
beforeEach(() => {
  home = mkdtempSync(join(tmpdir(), 'wc-auth-'));
  prevHome = process.env.WEARCLAUDE_HOME;
  process.env.WEARCLAUDE_HOME = home;
  t = 1_000_000;
});
afterEach(() => {
  if (prevHome === undefined) delete process.env.WEARCLAUDE_HOME;
  else process.env.WEARCLAUDE_HOME = prevHome;
  rmSync(home, { recursive: true, force: true });
});

describe('DeviceAuth', () => {
  it('issues a 6-digit code valid for 5 minutes and pairs once (single-use)', () => {
    const a = new DeviceAuth(now);
    const { code, expiresAt } = a.startPairing();
    expect(code).toMatch(/^\d{6}$/);
    expect(expiresAt).toBe(t + 300_000);
    const r = a.pair(code, 'watch');
    expect('token' in r).toBe(true);
    expect(a.pair(code, 'watch2')).toEqual({ error: 'invalid' });
  });

  it('expires after 5 minutes', () => {
    const a = new DeviceAuth(now);
    const { code } = a.startPairing();
    t += 300_001;
    expect(a.pair(code, 'watch')).toEqual({ error: 'expired' });
  });

  it('locks after 5 bad attempts for 5 minutes, even for the right code', () => {
    const a = new DeviceAuth(now);
    const { code } = a.startPairing();
    const bad = code === '000000' ? '111111' : '000000';
    for (let i = 0; i < 5; i++) expect(a.pair(bad, 'w')).toEqual({ error: 'invalid' });
    expect(a.pair(code, 'w')).toEqual({ error: 'locked' });
    t += 299_000;
    expect(a.pair(code, 'w')).toEqual({ error: 'locked' });
    t += 2_000;
    // lock over, but the code itself has expired by now
    expect(a.pair(code, 'w')).toEqual({ error: 'expired' });
  });

  it('counts failures even when no pairing window is open', () => {
    const a = new DeviceAuth(now);
    for (let i = 0; i < 5; i++) expect(a.pair('123456', 'w')).toEqual({ error: 'invalid' });
    expect(a.pair('123456', 'w')).toEqual({ error: 'locked' });
  });

  it('a successful pair clears the failure count', () => {
    const a = new DeviceAuth(now);
    const { code } = a.startPairing();
    const bad = code === '000000' ? '111111' : '000000';
    for (let i = 0; i < 4; i++) a.pair(bad, 'w');
    expect('token' in a.pair(code, 'w')).toBe(true);
    const c2 = a.startPairing().code;
    const bad2 = c2 === '222222' ? '333333' : '222222';
    for (let i = 0; i < 4; i++) a.pair(bad2, 'w');
    expect('token' in a.pair(c2, 'w2')).toBe(true);
  });

  it('verifies tokens, fails after revoke, never stores the raw token', () => {
    const a = new DeviceAuth(now);
    const r = a.pair(a.startPairing().code, 'watch') as { token: string };
    expect(a.verify(r.token)).toEqual({ name: 'watch' });
    expect(a.verify(r.token + 'x')).toBeNull();
    expect(a.verify('')).toBeNull();
    const raw = readFileSync(join(home, 'devices.json'), 'utf8');
    expect(raw).not.toContain(r.token);
    expect(Object.keys(JSON.parse(raw)[0]).sort()).toEqual(['createdAt', 'name', 'tokenHash']);
    expect(a.list()).toEqual([{ name: 'watch', createdAt: t }]);
    expect(a.revoke('watch')).toBe(true);
    expect(a.revoke('watch')).toBe(false);
    expect(a.verify(r.token)).toBeNull();
  });

  it('persists across instances; pairing with an existing name adds a numbered device', () => {
    const a = new DeviceAuth(now);
    const t1 = (a.pair(a.startPairing().code, 'watch') as { token: string }).token;
    const t2 = (a.pair(a.startPairing().code, 'watch') as { token: string }).token;
    const t3 = (a.pair(a.startPairing().code, 'watch') as { token: string }).token;
    expect(a.list().map((d) => d.name)).toEqual(['watch', 'watch (2)', 'watch (3)']);
    const b = new DeviceAuth(now);
    expect(b.verify(t1)).toEqual({ name: 'watch' });
    expect(b.verify(t2)).toEqual({ name: 'watch (2)' });
    expect(b.verify(t3)).toEqual({ name: 'watch (3)' });
    expect(b.revoke('watch (2)')).toBe(true);
    expect(b.verify(t1)).toEqual({ name: 'watch' });
    expect(b.verify(t2)).toBeNull();
  });

  it('failed attempts older than 15 minutes stop counting toward the lock', () => {
    const a = new DeviceAuth(now);
    for (let i = 0; i < 4; i++) expect(a.pair('123456', 'w')).toEqual({ error: 'invalid' });
    t += 15 * 60_000 + 1;
    for (let i = 0; i < 4; i++) expect(a.pair('123456', 'w')).toEqual({ error: 'invalid' });
    // only 4 failures inside the window, so the right code still pairs
    expect('token' in a.pair(a.startPairing().code, 'w')).toBe(true);
    for (let i = 0; i < 4; i++) a.pair('123456', 'w');
    t += 10 * 60_000;
    expect(a.pair('123456', 'w')).toEqual({ error: 'invalid' }); // 5th within 15 min -> locks
    expect(a.pair('123456', 'w')).toEqual({ error: 'locked' });
  });

  it('revoke notifies listeners', () => {
    const a = new DeviceAuth(now);
    a.pair(a.startPairing().code, 'watch');
    const got: string[] = [];
    const off = a.onRevoke((n) => got.push(n));
    a.revoke('watch');
    a.revoke('nope');
    off();
    expect(got).toEqual(['watch']);
  });
});
