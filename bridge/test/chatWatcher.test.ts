import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { appendFileSync, openSync, statSync, mkdtempSync, mkdirSync, rmSync, truncateSync, writeFileSync, utimesSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ChatIndex } from '../src/chatIndex.js';
import { ChatWatcher } from '../src/chatWatcher.js';
import { DesktopSessions } from '../src/desktopSessions.js';
import { EventBus } from '../src/events.js';
import type { WatchEvent } from '../src/types.js';

const SID = '00000000-0000-4000-8000-000000000001';
const u = (text: string) =>
  JSON.stringify({ type: 'user', cwd: '/home/me/code/my-app', timestamp: '2026-11-04T19:00:00.000Z', message: { role: 'user', content: text } });
const a = (text: string, stop: string | null) =>
  JSON.stringify({ type: 'assistant', timestamp: '2026-11-04T19:00:05.000Z', message: { role: 'assistant', content: [{ type: 'text', text }], stop_reason: stop } });

let root: string;
let file: string;
let clock: number;
let events: WatchEvent[];
let w: ChatWatcher;
let index: ChatIndex;
let excluded: Set<string>;
let births: Map<string, number>;
let opens: string[];

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'wc-watch-'));
  mkdirSync(join(root, 'proj', 'dir'), { recursive: true });
  file = join(root, 'proj', 'dir', `${SID}.jsonl`);
  writeFileSync(file, u('old prompt') + '\n' + a('old reply', 'end_turn') + '\n');
  clock = Date.now();
  const t = new Date(clock - 3600_000);
  utimesSync(file, t, t);
  index = new ChatIndex({ rootDir: join(root, 'proj'), excludeSessionIds: () => excluded, maxAgeDays: 30, now: () => clock, tmpDir: join(root, 'faketmp') });
  const bus = new EventBus();
  excluded = new Set();
  births = new Map();
  opens = [];
  events = [];
  bus.subscribe((e) => events.push(e));
  w = new ChatWatcher({
    index,
    bus,
    now: () => clock,
    birthOf: (p) => births.get(p) ?? statSync(p).birthtimeMs,
    openFile: (p) => {
      opens.push(p);
      return openSync(p, 'r');
    },
  });
});
afterEach(() => {
  w.stop();
  rmSync(root, { recursive: true, force: true });
});

const append = (s: string) => appendFileSync(file, s);

describe('ChatWatcher', () => {
  it('emits nothing on the first tick', () => {
    w.tick();
    expect(events).toEqual([]);
  });

  it('appended lines emit working then items', () => {
    w.tick();
    append(u('new prompt') + '\n' + a('working on it', 'tool_use') + '\n');
    w.tick();
    expect(events.map((e) => e.type)).toEqual(['chat_status', 'chat_item', 'chat_item']);
    expect(events[0]).toMatchObject({ agentId: `pc:${SID}`, status: 'working', projectName: 'my-app', title: 'old prompt' });
    expect(events[1]).toMatchObject({ kind: 'prompt', text: 'new prompt' });
    expect(events[2]).toMatchObject({ kind: 'reply', text: 'working on it' });
    // already working: no second working status
    events.length = 0;
    append(a('more', 'tool_use') + '\n');
    w.tick();
    expect(events.map((e) => e.type)).toEqual(['chat_item']);
  });

  it('end_turn emits idle after items', () => {
    w.tick();
    append(u('go') + '\n' + a('done', 'end_turn') + '\n');
    w.tick();
    expect(events.map((e) => e.type)).toEqual(['chat_status', 'chat_item', 'chat_item', 'chat_status']);
    expect(events[0]).toMatchObject({ status: 'working', ended: false });
    expect(events[3]).toMatchObject({ status: 'idle', ended: true });
  });

  it('holds a partial line until its newline arrives', () => {
    w.tick();
    const line = a('hello there', 'tool_use');
    append(line.slice(0, 20));
    w.tick();
    expect(events).toEqual([]);
    append(line.slice(20) + '\n');
    w.tick();
    expect(events.filter((e) => e.type === 'chat_item')).toHaveLength(1);
  });

  it('a shrunk file resets its offset without emitting', () => {
    w.tick();
    writeFileSync(file, u('old prompt') + '\n');
    w.tick();
    expect(events).toEqual([]);
    append(a('after', 'tool_use') + '\n');
    w.tick();
    expect(events.filter((e) => e.type === 'chat_item')).toHaveLength(1);
  });

  it('goes idle (not ended) after idleMs without growth', () => {
    w.tick();
    append(u('go') + '\n' + a('x', null) + '\n');
    w.tick();
    events.length = 0;
    clock += 59_000;
    w.tick();
    expect(events).toEqual([]);
    clock += 2_000;
    w.tick();
    expect(events.map((e) => e.type)).toEqual(['chat_status']);
    expect(events[0]).toMatchObject({ status: 'idle', ended: false });
    clock += 120_000;
    w.tick();
    expect(events).toHaveLength(1);
  });

  it('waits 10 minutes before timing out while the last stop_reason is tool_use', () => {
    w.tick();
    append(u('go') + '\n' + a('running a long build', 'tool_use') + '\n');
    w.tick();
    events.length = 0;
    clock += 9 * 60_000;
    w.tick();
    expect(events).toEqual([]);
    clock += 61_000;
    w.tick();
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ type: 'chat_status', status: 'idle', ended: false, archived: false });
  });

  it('a later non-tool_use assistant line restores the 60 s timeout', () => {
    w.tick();
    append(u('go') + '\n' + a('x', 'tool_use') + '\n');
    w.tick();
    append(a('thinking', null) + '\n');
    w.tick();
    events.length = 0;
    clock += 61_000;
    w.tick();
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ status: 'idle', ended: false });
  });

  it('opens a file only when its size changed', () => {
    w.tick();
    expect(opens).toHaveLength(1); // first sight: check whether the last line is complete
    opens.length = 0;
    w.tick();
    w.tick();
    expect(opens).toEqual([]);
    append(u('go') + '\n');
    w.tick();
    expect(opens).toHaveLength(1);
    opens.length = 0;
    clock += 61_000;
    w.tick();
    expect(opens).toEqual([]);
    expect(events.filter((e) => e.type === 'chat_status').map((e) => (e as { status: string }).status)).toEqual(['working', 'idle']);
  });

  it('tolerates a vanished file and truncate to zero', () => {
    w.tick();
    truncateSync(file, 0);
    expect(() => w.tick()).not.toThrow();
    rmSync(file);
    expect(() => w.tick()).not.toThrow();
  });

  it('scans the directory once per tick', () => {
    for (let i = 2; i <= 6; i++) {
      writeFileSync(join(root, 'proj', 'dir', `00000000-0000-4000-8000-00000000000${i}.jsonl`), u('x') + '\n');
    }
    w.tick();
    const before = index.scanCount;
    w.tick();
    expect(index.scanCount - before).toBe(1);
  });

  it('a chat first seen after the first tick starts from offset 0', () => {
    w.tick();
    const sid2 = '00000000-0000-4000-8000-000000000002';
    writeFileSync(join(root, 'proj', 'dir', `${sid2}.jsonl`), u('brand new') + '\n');
    w.tick();
    expect(events.map((e) => e.type)).toEqual(['chat_status', 'chat_item']);
    expect(events[0]).toMatchObject({ agentId: `pc:${sid2}`, status: 'working' });
    expect(events[1]).toMatchObject({ kind: 'prompt', text: 'brand new' });
  });

  it('skips the in-progress line of a file that is mid-write at first sight', () => {
    const line = a('half written', 'tool_use');
    append(line.slice(0, 30));
    w.tick();
    append(line.slice(30) + '\n' + a('next', 'tool_use') + '\n');
    w.tick();
    expect(events.filter((e) => e.type === 'chat_item').map((e) => (e as { text: string }).text)).toEqual(['next']);
  });

  it('catches up on the tail of a huge append and still reports working', () => {
    w.tick();
    const big = 'x'.repeat(1000);
    const lines: string[] = [];
    for (let i = 0; i < 9000; i++) lines.push(a(`r${i} ${big}`, 'tool_use'));
    append(lines.join('\n') + '\n');
    w.tick();
    expect(events[0]).toMatchObject({ type: 'chat_status', status: 'working' });
    const items = events.filter((e) => e.type === 'chat_item') as { text: string }[];
    expect(items.length).toBeGreaterThan(100);
    expect(items.length).toBeLessThan(2000);
    expect(items[items.length - 1]!.text.startsWith('r8999 ')).toBe(true);
    expect(items[0]!.text.startsWith('r')).toBe(true);
  });

  it('does not emit idle twice', () => {
    w.tick();
    append(u('go') + '\n' + a('done', 'end_turn') + '\n');
    w.tick();
    events.length = 0;
    append(a('', 'end_turn') + '\n');
    w.tick();
    clock += 120_000;
    w.tick();
    expect(events.filter((e) => e.type === 'chat_status')).toEqual([]);
  });

  it('a pre-existing chat that re-enters the list starts at its current size', () => {
    excluded.add(SID);
    w.tick();
    expect(events).toEqual([]);
    excluded.delete(SID);
    births.set(file, clock - 40 * 86_400_000);
    w.tick();
    expect(events).toEqual([]);
    append(u('fresh') + '\n');
    w.tick();
    expect(events.map((e) => e.type)).toEqual(['chat_status', 'chat_item']);
    expect((events[1] as { text: string }).text).toBe('fresh');
  });
});

describe('ChatWatcher metadata changes', () => {
  it('pushes one chat_status when the desktop archived flag flips on an idle chat', () => {
    const metaDir = join(root, 'meta', 'o', 'a');
    mkdirSync(metaDir, { recursive: true });
    const mf = join(metaDir, 'local_abc.json');
    const write = (archived: boolean) => writeFileSync(mf, JSON.stringify({ cliSessionId: SID, title: 'T', isArchived: archived }));
    write(false);
    const desk = new DesktopSessions({ roots: [join(root, 'meta')], now: () => clock });
    const idx = new ChatIndex({ rootDir: join(root, 'proj'), excludeSessionIds: () => new Set(), maxAgeDays: 30, now: () => clock, tmpDir: join(root, 'faketmp'), desktop: desk });
    const bus = new EventBus();
    const ev: WatchEvent[] = [];
    bus.subscribe((e) => ev.push(e));
    const ww = new ChatWatcher({ index: idx, bus, now: () => clock });
    ww.tick();
    expect(ev).toEqual([]);
    write(true);
    clock += 3000;
    ww.tick();
    expect(ev).toEqual([]);
    clock += 11_000;
    ww.tick();
    ww.tick();
    expect(ev).toHaveLength(1);
    expect(ev[0]).toMatchObject({ type: 'chat_status', agentId: `pc:${SID}`, status: 'idle', archived: true, title: 'T' });
  });
});
