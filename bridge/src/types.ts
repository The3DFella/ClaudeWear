export type AgentStatus =
  | 'running'
  | 'waiting_approval'
  | 'idle'
  | 'done'
  | 'failed'
  | 'stopped'
  | 'interrupted';

export type WatchEvent = { seq: number; ts: number; agentId: string } & (
  | { type: 'status'; status: AgentStatus; reason?: string }
  | { type: 'step'; text: string; depth: number }
  | { type: 'message'; text: string }
  | { type: 'summary'; text: string; costUsd?: number }
  | { type: 'approval_requested'; approvalId: string; tool: string; summary: string; expiresAt: number }
  | { type: 'approval_resolved'; approvalId: string; decision: 'allow' | 'deny' | 'timeout' }
  | { type: 'agent_created'; title: string; projectId: string }
  | { type: 'chat_item'; kind: 'prompt' | 'reply' | 'step'; text: string; ts: number }
  | { type: 'chat_status'; status: 'working' | 'idle'; /** idle only: true when the turn ended (end_turn), false for a no-growth timeout */ ended: boolean; title: string; projectName: string; /** Desktop app archived flag */ archived: boolean }
);

/**
 * Control messages on the /events WebSocket (not part of the event log).
 * `hello` is always sent first on every connection; `reset` follows it when the
 * requested `since` cannot be replayed. Both carry the bridge boot id and the
 * latest seq at connect time.
 */
export type StreamControl =
  | { type: 'hello'; bootId: string; seq: number }
  | { type: 'reset'; bootId: string; seq: number };

export type AgentInfo = { id: string; title: string; projectId: string; status: AgentStatus; lastStep?: string; summary?: string; createdAt: number; updatedAt: number; sessionId: string };

// Distributive: maps over each union member so the variant structure is preserved.
export type WatchEventInput = WatchEvent extends infer E
  ? E extends unknown
    ? Omit<E, 'seq' | 'ts'>
    : never
  : never;
