import type { WatchEvent, WatchEventInput } from './types.js';

const RECENT_PER_AGENT = 200;

/**
 * In-memory event log with fixed-capacity ring storage, replay by sequence
 * number, and synchronous fan-out to subscribers.
 */
export class EventBus {
  private readonly ring: (WatchEvent | undefined)[];
  private lastSeq = 0;
  private readonly subscribers = new Set<(e: WatchEvent) => void>();
  /** Last RECENT_PER_AGENT events per agent, independent of the ring, so chatty agents can't evict others' history. */
  private readonly perAgent = new Map<string, WatchEvent[]>();

  constructor(private readonly capacity = 5000) {
    this.ring = new Array<WatchEvent | undefined>(capacity);
  }

  emit(e: WatchEventInput): WatchEvent {
    const event = { ...e, seq: this.lastSeq + 1, ts: Date.now() } as WatchEvent;
    this.lastSeq = event.seq;
    this.ring[(event.seq - 1) % this.capacity] = event;
    let list = this.perAgent.get(event.agentId);
    if (!list) this.perAgent.set(event.agentId, (list = []));
    list.push(event);
    if (list.length > RECENT_PER_AGENT) list.splice(0, list.length - RECENT_PER_AGENT);

    for (const fn of [...this.subscribers]) {
      try {
        fn(event);
      } catch {
        // A failing subscriber must not break emit or other subscribers.
      }
    }
    return event;
  }

  /**
   * Events with seq > `seq`, oldest first. Returns 'reset' when some events
   * after `seq` have already been evicted from the ring.
   */
  since(seq: number): WatchEvent[] | 'reset' {
    if (this.lastSeq === 0) return [];
    const oldest = this.oldestSeq();
    if (oldest > seq + 1) return 'reset';

    const out: WatchEvent[] = [];
    for (let s = Math.max(seq + 1, oldest); s <= this.lastSeq; s++) {
      out.push(this.ring[(s - 1) % this.capacity]!);
    }
    return out;
  }

  /** The last `n` (at most RECENT_PER_AGENT) events for `agentId`, oldest first. */
  recent(agentId: string, n = RECENT_PER_AGENT): WatchEvent[] {
    if (n <= 0) return [];
    const list = this.perAgent.get(agentId);
    return list ? list.slice(-n) : [];
  }

  /** Sequence number of the most recently emitted event (0 if none). */
  latestSeq(): number {
    return this.lastSeq;
  }

  subscribe(fn: (e: WatchEvent) => void): () => void {
    this.subscribers.add(fn);
    return () => {
      this.subscribers.delete(fn);
    };
  }

  private oldestSeq(): number {
    return Math.max(1, this.lastSeq - this.capacity + 1);
  }
}
