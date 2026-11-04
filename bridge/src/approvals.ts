import { randomBytes } from 'node:crypto';
import type { EventBus } from './events.js';
import { describeTool } from './normalize.js';

export type Decision = 'allow' | 'deny' | 'timeout';

interface Entry {
  approvalId: string;
  agentId: string;
  tool: string;
  summary: string;
  expiresAt: number;
  settle: (d: Decision) => void;
}

export class ApprovalBroker {
  private readonly entries = new Map<string, Entry>();

  constructor(
    private readonly bus: EventBus,
    private readonly timeoutSec: number,
    private readonly onChange?: (agentId: string, waiting: boolean) => void,
  ) {}

  request(agentId: string, tool: string, input: unknown): Promise<Decision> {
    const approvalId = randomBytes(8).toString('hex');
    const summary = describeTool(tool, (input && typeof input === 'object' ? input : {}) as Record<string, unknown>);
    const expiresAt = Date.now() + this.timeoutSec * 1000;

    return new Promise<Decision>((resolvePromise) => {
      const wasWaiting = this.countFor(agentId) > 0;
      const timer = setTimeout(() => entry.settle('timeout'), this.timeoutSec * 1000);
      const entry: Entry = {
        approvalId, agentId, tool, summary, expiresAt,
        settle: (decision) => {
          // Guard: exactly one approval_resolved per approval.
          if (!this.entries.delete(approvalId)) return;
          clearTimeout(timer);
          this.bus.emit({ type: 'approval_resolved', agentId, approvalId, decision });
          if (this.countFor(agentId) === 0) this.onChange?.(agentId, false);
          resolvePromise(decision);
        },
      };
      this.entries.set(approvalId, entry);
      this.bus.emit({ type: 'approval_requested', agentId, approvalId, tool, summary, expiresAt });
      if (!wasWaiting) this.onChange?.(agentId, true);
    });
  }

  resolve(approvalId: string, decision: 'allow' | 'deny'): 'ok' | 'not_found' {
    const e = this.entries.get(approvalId);
    if (!e) return 'not_found';
    e.settle(decision);
    return 'ok';
  }

  cancelForAgent(agentId: string): void {
    for (const e of [...this.entries.values()]) {
      if (e.agentId === agentId) e.settle('deny');
    }
  }

  pending(): { approvalId: string; agentId: string; tool: string; summary: string; expiresAt: number }[] {
    return [...this.entries.values()].map(({ approvalId, agentId, tool, summary, expiresAt }) => ({
      approvalId, agentId, tool, summary, expiresAt,
    }));
  }

  private countFor(agentId: string): number {
    let n = 0;
    for (const e of this.entries.values()) if (e.agentId === agentId) n++;
    return n;
  }
}
