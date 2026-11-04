import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { openSync, readSync, closeSync, mkdtempSync, mkdirSync, rmSync, writeFileSync, utimesSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ChatIndex } from '../src/chatIndex.js';

const NOW = Date.parse('2026-11-04T20:00:00.000Z');
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const u = (text: string, cwd: string, ts = '2026-11-04T19:00:00.000Z') =>
  JSON.stringify({ type: 'user', cwd, timestamp: ts, message: { role: 'user', content: text } });
const a = (text: string, stop: string | null, ts = '2026-11-04T19:00:05.000Z') =>
  JSON.stringify({ type: 'assistant', timestamp: ts, message: { role: 'assistant', content: [{ type: 'text', text }], stop_reason: stop } });
const ct = (t: string) => JSON.stringify({ type: 'custom-title', customTitle: t });

let root: string;
let other: string; // a "project" cwd outside the temp dir
let fakeTmp: string;
beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'wc-chats-'));
  fakeTmp = join(root, 'faketmp');
  other = String.raw`C:\Users\me\code\my-app`;
});
afterEach(() => rmSync(root, { recursive: true, force: true }));

function put(sid: string, lines: string[], ageMs = 3600_000, dir = 'C--Users-me-code-my-app') {
  mkdirSync(join(root, 'proj', dir), { recursive: true });
  const f = join(root, 'proj', dir, `${sid}.jsonl`);
  writeFileSync(f, lines.join('\n') + '\n');
  const t = new Date(NOW - ageMs);
  utimesSync(f, t, t);
  return f;
}
const mk = (o: Partial<ConstructorParameters<typeof ChatIndex>[0]> = {}) =>
  new ChatIndex({ rootDir: join(root, 'proj'), excludeSessionIds: () => new Set(), maxAgeDays: 30, now: () => NOW, tmpDir: fakeTmp, ...o });

describe('ChatIndex', () => {
  it('reads title from tail and first prompt from head across a 1 MB middle', () => {
    const filler = Array.from({ length: 1000 }, (_, i) => JSON.stringify({ type: 'progress', n: i, pad: 'z'.repeat(1100) }));
    put(id(1), [u('first prompt here', other), ...filler, a('done', 'end_turn'), ct('Custom Name')]);
    put(id(2), [u('only prompt', other), ...filler, a('done', 'end_turn')]);
    const l = mk().list();
    const byId = Object.fromEntries(l.map((c) => [c.id, c]));
    expect(byId[id(1)]!.title).toBe('Custom Name');
    expect(byId[id(2)]!.title).toBe('only prompt');
    expect(byId[id(1)]!.projectName).toBe('my-app');
  });

  it('excludes old files, temp-dir cwds, excluded ids, and subagents', () => {
    put(id(1), [u('keep', other)]);
    put(id(2), [u('old', other)], 40 * 86400_000);
    put(id(3), [u('tmp', join(fakeTmp, 'x'))]);
    put(id(4), [u('excluded', other)]);
    put(id(5), [u('sub', other)], 3600_000, join('C--Users-me-code-my-app', 'subagents'));
    mkdirSync(join(root, 'proj', 'C--Users-me-code-my-app', id(6), 'subagents'), { recursive: true });
    writeFileSync(join(root, 'proj', 'C--Users-me-code-my-app', id(6), 'subagents', `${id(7)}.jsonl`), u('deep', other) + '\n');
    const l = mk({ excludeSessionIds: () => new Set([id(4)]) }).list();
    expect(l.map((c) => c.id)).toEqual([id(1)]);
  });

  it('caches by mtime+size', () => {
    const f = put(id(1), [u('hi', other)]);
    const reader = vi.fn((p: string, pos: number, len: number) => {
      const fd = openSync(p, 'r');
      try { const b = Buffer.alloc(len); const n = readSync(fd, b, 0, len, pos); return b.subarray(0, n).toString('utf8'); } finally { closeSync(fd); }
    });
    const idx = mk({ readRange: reader });
    idx.list();
    const n = reader.mock.calls.length;
    expect(n).toBeGreaterThan(0);
    idx.list();
    expect(reader.mock.calls.length).toBe(n);
    writeFileSync(f, u('hi', other) + '\n' + a('more', 'end_turn') + '\n');
    idx.list();
    expect(reader.mock.calls.length).toBeGreaterThan(n);
  });

  it('status working when fresh mtime and turn not ended', () => {
    put(id(1), [u('go', other), a('x', 'tool_use')], 5_000);
    put(id(2), [u('go', other), a('x', 'end_turn')], 5_000);
    put(id(3), [u('go', other), a('x', 'tool_use')], 60_000);
    const s = Object.fromEntries(mk().list().map((c) => [c.id, c.status]));
    expect(s).toEqual({ [id(1)]: 'working', [id(2)]: 'idle', [id(3)]: 'idle' });
  });

  it('working window is 60 s, matching the watcher', () => {
    put(id(1), [u('go', other), a('x', 'tool_use')], 45_000);
    put(id(2), [u('go', other), a('x', 'tool_use')], 61_000);
    const s = Object.fromEntries(mk().list().map((c) => [c.id, c.status]));
    expect(s).toEqual({ [id(1)]: 'working', [id(2)]: 'idle' });
  });

  it('a prompt after the last end_turn shows as working', () => {
    put(id(1), [u('go', other), a('x', 'end_turn'), u('next', other)], 5_000);
    expect(mk().list()[0]!.status).toBe('working');
  });

  it('get returns last 60 items and validates id', () => {
    const lines = [u('p', other)];
    for (let i = 0; i < 100; i++) lines.push(a(`r${i}`, 'tool_use', `2026-11-04T19:${String(i % 60).padStart(2, '0')}:00.000Z`));
    put(id(1), lines);
    const r = mk().get(id(1))!;
    expect(r.items).toHaveLength(60);
    expect(r.items[59]!.text).toBe('r99');
    expect(r.items[59]!.ts).toBeGreaterThan(0);
    expect(r.chat.id).toBe(id(1));
    expect(mk().get('../etc')).toBeUndefined();
    expect(mk().get(id(9))).toBeUndefined();
    expect(mk().file(id(1))).toMatch(/\.jsonl$/);
  });

  it('uses custom-title.json fallback before first prompt, defensively', () => {
    put(id(1), [u('prompt', other)]);
    mkdirSync(join(root, 'proj', 'C--Users-me-code-my-app', id(1)), { recursive: true });
    writeFileSync(join(root, 'proj', 'C--Users-me-code-my-app', id(1), 'custom-title.json'), JSON.stringify({ title: 'From JSON' }));
    put(id(2), [u('prompt2', other)]);
    mkdirSync(join(root, 'proj', 'C--Users-me-code-my-app', id(2)), { recursive: true });
    writeFileSync(join(root, 'proj', 'C--Users-me-code-my-app', id(2), 'custom-title.json'), '{bad');
    const s = Object.fromEntries(mk().list().map((c) => [c.id, c.title]));
    expect(s).toEqual({ [id(1)]: 'From JSON', [id(2)]: 'prompt2' });
  });

  it('titled is true only for a custom title or custom-title.json; relayTarget carries the full title', () => {
    const long = 'L'.repeat(80);
    put(id(1), [u('p1', other), ct(long)]);
    put(id(2), [u('p2', other)]);
    mkdirSync(join(root, 'proj', 'C--Users-me-code-my-app', id(2)), { recursive: true });
    writeFileSync(join(root, 'proj', 'C--Users-me-code-my-app', id(2), 'custom-title.json'), JSON.stringify({ title: ' From JSON ' }));
    put(id(3), [u('just a prompt', other)]);
    const idx = mk();
    const t = Object.fromEntries(idx.list().map((c) => [c.id, c.titled]));
    expect(t).toEqual({ [id(1)]: true, [id(2)]: true, [id(3)]: false });
    expect(idx.get(id(1))!.chat.title.length).toBe(60);
    expect(idx.relayTarget(id(1))).toMatchObject({ chat: { id: id(1), titled: true }, relayTitle: long });
    expect(idx.relayTarget(id(2))!.relayTitle).toBe('From JSON');
    expect(idx.relayTarget(id(3))).toMatchObject({ chat: { id: id(3), titled: false } });
    expect(idx.relayTarget(id(3))!.relayTitle).toBeUndefined();
    expect(idx.relayTarget(id(9))).toBeUndefined();
  });

  it('projectName falls back to folder name when cwd unknown', () => {
    put(id(1), [JSON.stringify({ type: 'custom-title', customTitle: 'T' })], 3600_000, 'C--Users-me-code-widget');
    expect(mk().list()[0]!.projectName).toBe('widget');
  });

  it('ignores an oversized or symlinked custom-title.json', () => {
    const d = join(root, 'proj', 'C--Users-me-code-my-app');
    put(id(1), [u('prompt1', other)]);
    mkdirSync(join(d, id(1)), { recursive: true });
    writeFileSync(join(d, id(1), 'custom-title.json'), JSON.stringify({ title: 'Big', pad: 'x'.repeat(9000) }));
    put(id(2), [u('prompt2', other)]);
    mkdirSync(join(d, id(2)), { recursive: true });
    writeFileSync(join(root, 'target.json'), JSON.stringify({ title: 'Linked' }));
    let linked = true;
    try {
      symlinkSync(join(root, 'target.json'), join(d, id(2), 'custom-title.json'));
    } catch {
      linked = false; // no symlink privilege on this machine
    }
    const s = Object.fromEntries(mk().list().map((c) => [c.id, c.title]));
    expect(s[id(1)]).toBe('prompt1');
    if (linked) expect(s[id(2)]).toBe('prompt2');
  });

  it('does not cache a transient read failure', () => {
    put(id(1), [u('hello', other)]);
    let fail = true;
    const reader = (p: string, pos: number, len: number) => {
      if (fail) throw Object.assign(new Error('busy'), { code: 'EBUSY' });
      const fd = openSync(p, 'r');
      try { const b = Buffer.alloc(len); const n = readSync(fd, b, 0, len, pos); return b.subarray(0, n).toString('utf8'); } finally { closeSync(fd); }
    };
    const idx = mk({ readRange: reader });
    expect(idx.list()[0]!.title).toBe('Untitled chat');
    fail = false;
    expect(idx.list()[0]!.title).toBe('hello');
  });

  it('stops parsing once 100 visible chats are collected, newest first', () => {
    const reads = new Set<string>();
    for (let i = 1; i <= 130; i++) put(id(i), [u(`p${i}`, other)], 1000 + i * 1000);
    const reader = (p: string, pos: number, len: number) => {
      reads.add(p);
      const fd = openSync(p, 'r');
      try { const b = Buffer.alloc(len); const n = readSync(fd, b, 0, len, pos); return b.subarray(0, n).toString('utf8'); } finally { closeSync(fd); }
    };
    const l = mk({ readRange: reader }).list();
    expect(l).toHaveLength(100);
    expect(reads.size).toBe(100);
    expect(l[0]!.id).toBe(id(1));
  });
});

describe('ChatIndex desktop metadata', () => {
  const desk = (m: Record<string, { title?: string; archived: boolean; lastActivityAt?: number }>) => ({ lookup: () => new Map(Object.entries(m)) });

  it('uses desktop title, archived and lastActivityAt; full title for relay', () => {
    put(id(1), [u('first', other), a('x', 'end_turn')], 3600_000);
    const long = 'Desktop sidebar title '.repeat(10).trim();
    const idx = mk({ desktop: desk({ [id(1)]: { title: long, archived: true, lastActivityAt: NOW - 60_000 } }) });
    const c = idx.list()[0]!;
    expect(c).toMatchObject({ archived: true, titled: true, lastActivity: NOW - 60_000 });
    expect(c.title.length).toBeLessThan(long.length + 1);
    expect(idx.relayTarget(id(1))?.relayTitle).toBe(long);
    expect(idx.get(id(1))?.chat.archived).toBe(true);
  });

  it('is unchanged without desktop data and never leaks extra fields', () => {
    put(id(1), [u('first', other), a('x', 'end_turn')]);
    const c = mk({ desktop: desk({}) }).list()[0]!;
    expect(c).toMatchObject({ archived: false, titled: false, title: 'first' });
    expect(JSON.stringify(c)).not.toMatch(/cwd|permissionMode|bridgeSessionIds/);
  });

  it('clamps future lastActivityAt to now', () => {
    put(id(1), [u('first', other), a('x', 'end_turn')], 3600_000);
    const c = mk({ desktop: desk({ [id(1)]: { archived: false, lastActivityAt: NOW + 10 * 86_400_000 } }) }).list()[0]!;
    expect(c.lastActivity).toBe(NOW);
    put(id(2), [u('old', other), a('x', 'end_turn')], 40 * 86_400_000);
    const l = mk({ desktop: desk({ [id(2)]: { archived: false, lastActivityAt: NOW + 86_400_000 * 400 } }) }).list();
    expect(l.find((x) => x.id === id(2))?.lastActivity).toBe(NOW);
  });

  it('caps 100 active plus 50 archived', () => {
    const m: Record<string, { archived: boolean }> = {};
    for (let i = 1; i <= 180; i++) {
      put(id(i), [u('p' + i, other), a('x', 'end_turn')], 1000 * i);
      if (i > 20) m[id(i)] = { archived: false };
      if (i <= 20) m[id(i)] = { archived: false };
    }
    for (let i = 1; i <= 80; i++) m[id(i)] = { archived: true };
    const l = mk({ desktop: desk(m) }).list();
    expect(l.filter((c) => c.archived)).toHaveLength(50);
    expect(l.filter((c) => !c.archived)).toHaveLength(100);
  });
});
