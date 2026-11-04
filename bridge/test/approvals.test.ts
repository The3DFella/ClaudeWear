import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { EventBus } from '../src/events.js';
import { ApprovalBroker } from '../src/approvals.js';

describe('ApprovalBroker', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function setup() {
    const bus = new EventBus();
    const changes: [string, boolean][] = [];
    const broker = new ApprovalBroker(bus, 30, (a, w) => changes.push([a, w]));
    const resolved = () => bus.recent('a1').filter((e) => e.type === 'approval_resolved');
    return { bus, broker, changes, resolved };
  }

  it('allow emits one approval_resolved', async () => {
    const { bus, broker, changes, resolved } = setup();
    const p = broker.request('a1', 'Bash', { command: 'ls' });
    const req = bus.recent('a1').find((e) => e.type === 'approval_requested') as any;
    expect(req.tool).toBe('Bash');
    expect(req.expiresAt).toBe(Date.now() + 30_000);
    expect(broker.pending()).toHaveLength(1);
    expect(broker.resolve(req.approvalId, 'allow')).toBe('ok');
    expect(await p).toBe('allow');
    expect(resolved()).toHaveLength(1);
    expect(changes).toEqual([['a1', true], ['a1', false]]);
    vi.advanceTimersByTime(60_000);
    expect(resolved()).toHaveLength(1);
  });

  it('deny emits one approval_resolved', async () => {
    const { bus, broker, resolved } = setup();
    const p = broker.request('a1', 'Bash', {});
    const id = (bus.recent('a1')[0] as any).approvalId;
    broker.resolve(id, 'deny');
    expect(await p).toBe('deny');
    expect((resolved()[0] as any).decision).toBe('deny');
  });

  it('timeout emits one approval_resolved', async () => {
    const { broker, resolved, changes } = setup();
    const p = broker.request('a1', 'Bash', {});
    vi.advanceTimersByTime(30_000);
    expect(await p).toBe('timeout');
    expect(resolved()).toHaveLength(1);
    expect((resolved()[0] as any).decision).toBe('timeout');
    expect(broker.pending()).toHaveLength(0);
    expect(changes).toEqual([['a1', true], ['a1', false]]);
  });

  it('resolve unknown or already-resolved returns not_found', async () => {
    const { bus, broker } = setup();
    expect(broker.resolve('nope', 'allow')).toBe('not_found');
    const p = broker.request('a1', 'Bash', {});
    const id = (bus.recent('a1')[0] as any).approvalId;
    broker.resolve(id, 'allow');
    await p;
    expect(broker.resolve(id, 'deny')).toBe('not_found');
  });

  it('cancelForAgent denies pending, only for that agent', async () => {
    const { bus, broker, resolved, changes } = setup();
    const p1 = broker.request('a1', 'Bash', {});
    const p2 = broker.request('a1', 'Write', {});
    const p3 = broker.request('a2', 'Write', {});
    broker.cancelForAgent('a1');
    expect(await p1).toBe('deny');
    expect(await p2).toBe('deny');
    expect(resolved()).toHaveLength(2);
    expect(broker.pending()).toHaveLength(1);
    expect(changes.filter((c) => c[0] === 'a1')).toEqual([['a1', true], ['a1', false]]);
    void p3; void bus;
  });
});
