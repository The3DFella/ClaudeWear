import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from 'fastify';
import websocket from '@fastify/websocket';
import type { Config } from './config.js';
import type { DeviceAuth } from './auth.js';
import { Busy, NotFound, UnknownProject, type AgentManager } from './agents.js';
import type { ApprovalBroker } from './approvals.js';
import type { EventBus } from './events.js';
import type { ChatIndex } from './chatIndex.js';
import { RelayGate, type RelayOutcome } from './relay.js';
import type { StreamControl, WatchEvent } from './types.js';
import type { WebSocket } from 'ws';

type Deps = {
  config: Config;
  auth: DeviceAuth;
  agents: AgentManager;
  broker: ApprovalBroker;
  bus: EventBus;
  /** PC chat index; undefined disables the /chats routes. */
  chats?: ChatIndex;
  /** Random id for this bridge process; lets the watch detect restarts. */
  bootId?: string;
  /** WebSocket ping interval; a socket that has not answered the previous ping is terminated. */
  heartbeatMs?: number;
  /** Delivers dictated text to the live desktop chat with this exact title; undefined disables the route. */
  relay?: (title: string, text: string) => Promise<RelayOutcome>;
};

const RELAY_STATUS: Record<RelayOutcome, [number, unknown]> = {
  sent: [200, { ok: true }],
  not_found: [409, { error: 'not_live' }],
  ambiguous: [409, { error: 'ambiguous' }],
  failed: [502, { error: 'relay_failed' }],
};

/** Close code sent to sockets of a revoked device. */
export const REVOKED_CLOSE_CODE = 4001;

declare module 'fastify' {
  interface FastifyRequest {
    deviceName?: string;
  }
}

class BadRequest extends Error {}


function readVersion(): string {
  try {
    const url = new URL('../package.json', import.meta.url);
    return (JSON.parse(readFileSync(url, 'utf8')) as { version?: string }).version ?? '0.0.0';
  } catch {
    return '0.0.0';
  }
}

const isObj = (b: unknown): b is Record<string, unknown> => typeof b === 'object' && b !== null && !Array.isArray(b);

function promptOf(b: unknown): string {
  if (!isObj(b) || typeof b.prompt !== 'string' || b.prompt.trim() === '' || b.prompt.length > 10_000) {
    throw new BadRequest();
  }
  return b.prompt;
}

function bearer(req: FastifyRequest): string | null {
  const h = req.headers.authorization;
  if (typeof h !== 'string') return null;
  const m = /^Bearer (.+)$/i.exec(h);
  return m ? m[1]! : null;
}

export function createServer(d: Deps): FastifyInstance {
  const { config, auth, agents, broker, bus } = d;
  const bootId = d.bootId ?? randomUUID();
  const version = readVersion();
  const app = Fastify({ logger: false, bodyLimit: 64 * 1024 });
  void app.register(websocket);

  // Live sockets, tagged with the device that opened them.
  const sockets = new Map<WebSocket, { device: string; alive: boolean }>();
  const heartbeat = setInterval(() => {
    for (const [ws, meta] of sockets) {
      if (!meta.alive) {
        ws.terminate();
        continue;
      }
      meta.alive = false;
      try {
        ws.ping();
      } catch {
        ws.terminate();
      }
    }
  }, d.heartbeatMs ?? 30_000);
  heartbeat.unref();
  const offRevoke = auth.onRevoke((name) => {
    for (const [ws, meta] of sockets) {
      if (meta.device === name) ws.close(REVOKED_CLOSE_CODE, 'revoked');
    }
  });
  app.addHook('onClose', async () => {
    clearInterval(heartbeat);
    offRevoke();
  });

  app.setErrorHandler((err: Error & { statusCode?: number }, _req, reply) => {
    if (err instanceof Busy) return reply.code(409).send({ error: 'busy' });
    if (err instanceof NotFound || err instanceof UnknownProject) return reply.code(404).send({ error: 'not_found' });
    if (err instanceof BadRequest) return reply.code(400).send({ error: 'bad_request' });
    if (typeof err.statusCode === 'number' && err.statusCode >= 400 && err.statusCode < 500) {
      return reply.code(400).send({ error: 'bad_request' });
    }
    return reply.code(500).send({ error: 'internal' });
  });
  app.setNotFoundHandler((_req, reply) => reply.code(404).send({ error: 'not_found' }));

  app.addHook('onRequest', async (req: FastifyRequest, reply: FastifyReply) => {
    const path = req.url.split('?')[0];
    if (path === '/health' || path === '/pair') return;
    const token = bearer(req);
    const device = token === null ? null : auth.verify(token);
    if (device === null) {
      return reply.code(401).send({ error: 'unauthorized' });
    }
    req.deviceName = device.name;
  });

  app.get('/health', async () => ({ ok: true, version }));

  app.post('/pair', async (req, reply) => {
    const b = req.body;
    if (
      !isObj(b) ||
      typeof b.code !== 'string' ||
      b.code.length > 32 ||
      typeof b.deviceName !== 'string' ||
      b.deviceName.length < 1 ||
      b.deviceName.length > 64
    ) {
      throw new BadRequest();
    }
    const r = auth.pair(b.code, b.deviceName);
    if ('token' in r) return { token: r.token };
    return reply.code(r.error === 'locked' ? 429 : 401).send({ error: r.error });
  });

  app.get('/chats', async () => {
    if (!config.showPcChats || !d.chats) throw new NotFound();
    return d.chats.list();
  });

  app.get<{ Params: { id: string } }>('/chats/:id', async (req) => {
    if (!config.showPcChats || !d.chats) throw new NotFound();
    if (!/^[0-9a-f-]{36}$/.test(req.params.id)) throw new BadRequest();
    const r = d.chats.get(req.params.id);
    if (!r) throw new NotFound();
    return r;
  });

  const relayGate = new RelayGate(3);
  app.post<{ Params: { id: string } }>('/chats/:id/messages', async (req, reply) => {
    if (!config.showPcChats || !d.chats || !d.relay) throw new NotFound();
    const id = req.params.id;
    if (!/^[0-9a-f-]{36}$/.test(id)) throw new BadRequest();
    const prompt = promptOf(req.body);
    const target = d.chats.relayTarget(id);
    if (!target) throw new NotFound();
    const title = target.relayTitle;
    if (title === undefined) return reply.code(409).send({ error: 'untitled' });
    if (!relayGate.tryAcquire(id)) return reply.code(429).send({ error: 'busy' });
    let outcome: RelayOutcome;
    try {
      outcome = await d.relay(title, prompt);
    } catch {
      outcome = 'failed';
    } finally {
      relayGate.release(id);
    }
    const [code, body] = RELAY_STATUS[outcome] ?? RELAY_STATUS.failed;
    return reply.code(code).send(body);
  });

  app.get('/projects', async () => config.projects.map((p) => ({ id: p.id, name: p.name })));

  app.get('/agents', async (_req, reply) => {
    // Snapshot and seq are taken in the same tick, so the seq exactly describes the snapshot.
    const list = agents.list();
    void reply.header('X-Bridge-Boot', bootId).header('X-Bridge-Seq', String(bus.latestSeq()));
    return list;
  });

  app.post('/agents', async (req) => {
    const b = req.body;
    if (!isObj(b) || typeof b.projectId !== 'string' || b.projectId === '') throw new BadRequest();
    const prompt = promptOf(b);
    return { agentId: agents.create(b.projectId, prompt).id };
  });

  app.get<{ Params: { id: string } }>('/agents/:id', async (req) => {
    const agent = agents.get(req.params.id);
    if (!agent) throw new NotFound();
    return {
      agent,
      events: bus.recent(agent.id),
      approvals: broker.pending().filter((a) => a.agentId === agent.id),
    };
  });

  app.post<{ Params: { id: string } }>('/agents/:id/messages', async (req) => {
    const prompt = promptOf(req.body);
    agents.followUp(req.params.id, prompt);
    return { ok: true };
  });

  app.post<{ Params: { id: string } }>('/agents/:id/stop', async (req) => {
    agents.stop(req.params.id);
    return { ok: true };
  });

  app.post<{ Params: { id: string } }>('/approvals/:id', async (req, reply) => {
    const b = req.body;
    if (!isObj(b) || (b.decision !== 'allow' && b.decision !== 'deny')) throw new BadRequest();
    if (broker.resolve(req.params.id, b.decision) === 'not_found') {
      return reply.code(404).send({ error: 'not_found' });
    }
    return { ok: true };
  });

  app.register(async (inst) => {
    inst.get('/events', { websocket: true }, (socket, req) => {
      const raw = (req.query as Record<string, unknown>).since;
      let backlog: WatchEvent[] | 'reset';
      if (raw === undefined) {
        backlog = bus.since(0);
      } else if (typeof raw !== 'string' || !/^\d{1,15}$/.test(raw) || Number(raw) > bus.latestSeq()) {
        backlog = 'reset';
      } else {
        backlog = bus.since(Number(raw));
      }

      const send = (e: unknown) => {
        if (socket.readyState !== socket.OPEN) return;
        try {
          socket.send(JSON.stringify(e));
        } catch {
          // socket going away; close handler will clean up
        }
      };

      // Hello, replay and subscribe in the same tick so no event is missed or duplicated.
      const seq = bus.latestSeq();
      send({ type: 'hello', bootId, seq } satisfies StreamControl);
      if (backlog === 'reset') send({ type: 'reset', bootId, seq } satisfies StreamControl);
      else for (const e of backlog) send(e);
      const unsubscribe = bus.subscribe(send);
      const meta = { device: req.deviceName ?? '', alive: true };
      sockets.set(socket, meta);
      socket.on('pong', () => {
        meta.alive = true;
      });
      let done = false;
      const cleanup = () => {
        if (done) return;
        done = true;
        sockets.delete(socket);
        unsubscribe();
      };
      socket.on('close', cleanup);
      socket.on('error', () => {
        cleanup();
        socket.terminate();
      });
    });
  });

  return app;
}
