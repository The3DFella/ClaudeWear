import { describe, it, expect, vi } from 'vitest';
import { EventBus } from '../src/events.js';
import type { WatchEventInput } from '../src/types.js';

const msg = (agentId: string, text = 'x'): WatchEventInput => ({ type: 'message', agentId, text });

describe('EventBus', () => {
  it('assigns monotonic seq values starting at 1 and sets ts', () => {
    const bus = new EventBus();
    const before = Date.now();
    const a = bus.emit(msg('a'));
    const b = bus.emit(msg('a'));
    const c = bus.emit(msg('b'));
    expect([a.seq, b.seq, c.seq]).toEqual([1, 2, 3]);
    expect(a.ts).toBeGreaterThanOrEqual(before);
    expect(a.type).toBe('message');
    expect(a.agentId).toBe('a');
  });

  it('delivers events to subscribers and stops after unsubscribe', () => {
    const bus = new EventBus();
    const fn = vi.fn();
    const unsubscribe = bus.subscribe(fn);
    const first = bus.emit(msg('a', 'one'));
    expect(fn).toHaveBeenCalledTimes(1);
    expect(fn).toHaveBeenCalledWith(first);
    unsubscribe();
    bus.emit(msg('a', 'two'));
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('isolates subscriber exceptions from emit and other subscribers', () => {
    const bus = new EventBus();
    const good = vi.fn();
    bus.subscribe(() => {
      throw new Error('boom');
    });
    bus.subscribe(good);
    expect(() => bus.emit(msg('a'))).not.toThrow();
    expect(good).toHaveBeenCalledTimes(1);
    expect(bus.since(0)).toHaveLength(1);
  });

  it('since(0) on an empty bus is []', () => {
    const bus = new EventBus();
    expect(bus.since(0)).toEqual([]);
  });

  it('since(x) returns events with seq > x, and [] at or past the latest seq', () => {
    const bus = new EventBus();
    for (let i = 0; i < 5; i++) bus.emit(msg('a'));
    expect(bus.since(2).map((e) => e.seq)).toEqual([3, 4, 5]);
    expect(bus.since(5)).toEqual([]);
    expect(bus.since(9)).toEqual([]);
  });

  it('replays with eviction: reset when the gap was evicted, otherwise the retained tail', () => {
    const bus = new EventBus(2000);
    for (let i = 0; i < 2005; i++) bus.emit(msg('a'));
    expect(bus.since(3)).toBe('reset');
    const tail = bus.since(5);
    expect(tail).not.toBe('reset');
    expect((tail as { seq: number }[]).map((e) => e.seq)).toEqual(
      Array.from({ length: 2000 }, (_, i) => i + 6),
    );
    expect(bus.since(2005)).toEqual([]);
  });

  it('recent(agentId, n) returns the last n events for that agent only, oldest first', () => {
    const bus = new EventBus();
    bus.emit(msg('a', 'a1'));
    bus.emit(msg('b', 'b1'));
    bus.emit(msg('a', 'a2'));
    bus.emit(msg('b', 'b2'));
    bus.emit(msg('a', 'a3'));
    const recent = bus.recent('a', 2);
    expect(recent.map((e) => (e.type === 'message' ? e.text : ''))).toEqual(['a2', 'a3']);
    expect(recent.every((e) => e.agentId === 'a')).toBe(true);
  });

  it('recent defaults to 200 and returns [] for unknown agents', () => {
    const bus = new EventBus();
    for (let i = 0; i < 3; i++) bus.emit(msg('a'));
    expect(bus.recent('a')).toHaveLength(3);
    expect(bus.recent('nobody')).toEqual([]);
  });

  it("keeps an agent's recent history even after heavy pc:* traffic evicts it from the ring", () => {
    const bus = new EventBus(100);
    for (let i = 0; i < 5; i++) bus.emit(msg('w1', `w${i}`));
    for (let i = 0; i < 1000; i++) bus.emit(msg(`pc:${i % 7}`, 'chat'));
    expect(bus.since(0)).toBe('reset');
    expect(bus.recent('w1').map((e) => (e.type === 'message' ? e.text : ''))).toEqual(['w0', 'w1', 'w2', 'w3', 'w4']);
  });

  it('per-agent recent store keeps the last 200 events', () => {
    const bus = new EventBus(10);
    for (let i = 0; i < 250; i++) bus.emit(msg('a', `a${i}`));
    const r = bus.recent('a');
    expect(r).toHaveLength(200);
    expect(r[0]!.type === 'message' && r[0]!.text).toBe('a50');
    expect(r[199]!.type === 'message' && r[199]!.text).toBe('a249');
    expect(bus.recent('a', 3).map((e) => e.seq)).toEqual([248, 249, 250]);
  });

  it('default ring capacity is 5000', () => {
    const bus = new EventBus();
    for (let i = 0; i < 5001; i++) bus.emit(msg('a'));
    expect(bus.since(1)).not.toBe('reset');
    expect(bus.since(0)).toBe('reset');
  });

  it('latestSeq reflects the last emitted seq', () => {
    const bus = new EventBus();
    expect(bus.latestSeq()).toBe(0);
    bus.emit(msg('a'));
    bus.emit(msg('a'));
    expect(bus.latestSeq()).toBe(2);
  });
});
