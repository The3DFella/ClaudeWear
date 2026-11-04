import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import WebSocket from 'ws';
import type { FastifyInstance } from 'fastify';
import { EventBus } from '../src/events.js';
import { ApprovalBroker } from '../src/approvals.js';
import { AgentManager } from '../src/agents.js';
import { DeviceAuth } from '../src/auth.js';
import { createServer } from '../src/server.js';
import type { Config } from '../src/config.js';
import { ChatIndex } from '../src/chatIndex.js';
import type { RelayOutcome } from '../src/relay.js';
import { mkdirSync, writeFileSync } from 'node:fs';

let home: string;
let prevHome: string | undefined;
let app: FastifyInstance | undefined;
beforeEach(() => {
  home = mkdtempSync(join(tmpdir(), 'wc-server-'));
  prevHome = process.env.WEARCLAUDE_HOME;
  process.env.WEARCLAUDE_HOME = home;
});
afterEach(async () => {
  await app?.close();
  app = undefined;
  if (prevHome === undefined) delete process.env.WEARCLAUDE_HOME;
  else process.env.WEARCLAUDE_HOME = prevHome;
  rmSync(home, { recursive: true, force: true });
});

const claudeCmd = { bin: process.execPath, preArgs: ['test/fake-claude.mjs'] };

function setup(env: NodeJS.ProcessEnv, bus = new EventBus(), extra: { heartbeatMs?: number; chats?: ChatIndex; showPcChats?: boolean; relay?: (title: string, text: string) => Promise<RelayOutcome> } = {}) {
  const config: Config = {
    port: 0, bindHost: '127.0.0.1',
    projects: [{ id: 'p1', name: 'Proj', path: process.cwd() }],
    defaultPermissionMode: 'default', approvalTimeoutSec: 30, showPcChats: extra.showPcChats ?? true, pcChatsMaxAgeDays: 30,
  };
  const holder: { mgr?: AgentManager } = {};
  const broker = new ApprovalBroker(bus, 30, (id, w) => holder.mgr?.setWaiting(id, w));
  const agents = new AgentManager({ config, bus, broker, mcpConfigFor: () => '{}', claudeCmd, env });
  holder.mgr = agents;
  const auth = new DeviceAuth();
  const server = createServer({ config, auth, agents, broker, bus, bootId: 'boot-1', heartbeatMs: extra.heartbeatMs, chats: extra.chats, relay: extra.relay });
  app = server;
  const token = (auth.pair(auth.startPairing().code, 'watch') as { token: string }).token;
  const h = { authorization: `Bearer ${token}` };
  return { config, bus, broker, agents, auth, app: server, token, h };
}
const SIMPLE = { FAKE_CLAUDE_FIXTURE: resolve('test/fixtures/simple-text.ndjson') };

function collect(ws: WebSocket) {
  const msgs: any[] = [];
  ws.on('message', (d) => msgs.push(JSON.parse(d.toString())));
  const until = (pred: () => boolean, ms = 10_000) =>
    new Promise<void>((res, rej) => {
      const start = Date.now();
      const i = setInterval(() => {
        if (pred()) { clearInterval(i); res(); }
        else if (Date.now() - start > ms) { clearInterval(i); rej(new Error('timeout')); }
      }, 20);
    });
  return { msgs, until };
}
const open = (ws: WebSocket) =>
  new Promise<void>((res, rej) => { ws.on('open', () => res()); ws.on('error', rej); });

describe('server REST', () => {
  it('health is public; others need a token', async () => {
    const s = setup(SIMPLE);
    const r = await s.app.inject('/health');
    expect(r.statusCode).toBe(200);
    expect(r.json().ok).toBe(true);
    expect(typeof r.json().version).toBe('string');
    for (const url of ['/projects', '/agents', '/agents/x']) {
      const u = await s.app.inject(url);
      expect(u.statusCode).toBe(401);
      expect(u.json()).toEqual({ error: 'unauthorized' });
    }
    const bad = await s.app.inject({ url: '/projects', headers: { authorization: 'Bearer nope' } });
    expect(bad.statusCode).toBe(401);
    // ?token= is never accepted
    const q = await s.app.inject({ url: `/projects?token=${s.token}` });
    expect(q.statusCode).toBe(401);
  });

  it('GET /agents carries boot id and snapshot seq headers', async () => {
    const s = setup(SIMPLE);
    s.bus.emit({ type: 'message', agentId: 'a', text: 'one' });
    s.bus.emit({ type: 'message', agentId: 'a', text: 'two' });
    const r = await s.app.inject({ url: '/agents', headers: s.h });
    expect(r.statusCode).toBe(200);
    expect(r.headers['x-bridge-boot']).toBe('boot-1');
    expect(r.headers['x-bridge-seq']).toBe('2');
  });

  it('/projects has no path', async () => {
    const s = setup(SIMPLE);
    const r = await s.app.inject({ url: '/projects', headers: s.h });
    expect(r.json()).toEqual([{ id: 'p1', name: 'Proj' }]);
    expect(r.body).not.toContain('path');
  });

  it('POST /pair status codes', async () => {
    const s = setup(SIMPLE);
    const post = (payload: unknown) => s.app.inject({ method: 'POST', url: '/pair', payload: payload as object });
    expect((await post({ code: 1, deviceName: 'x' })).statusCode).toBe(400);
    expect((await post({ code: '123456', deviceName: '' })).statusCode).toBe(400);
    expect((await post({ code: '123456', deviceName: 'x'.repeat(65) })).statusCode).toBe(400);
    const inv = await post({ code: '000000', deviceName: 'w2' });
    expect(inv.statusCode).toBe(401);
    expect(inv.json()).toEqual({ error: 'invalid' });
    const code = s.auth.startPairing().code;
    const ok = await post({ code, deviceName: 'w2' });
    expect(ok.statusCode).toBe(200);
    expect(typeof ok.json().token).toBe('string');
    for (let i = 0; i < 5; i++) await post({ code: '000001', deviceName: 'w3' });
    const locked = await post({ code: '000001', deviceName: 'w3' });
    expect(locked.statusCode).toBe(429);
    expect(locked.json()).toEqual({ error: 'locked' });
  });

  it('agent lifecycle, validation, and error mapping', async () => {
    const s = setup({ FAKE_CLAUDE_HANG: '1' });
    const create = (payload: unknown) =>
      s.app.inject({ method: 'POST', url: '/agents', headers: s.h, payload: payload as object });
    const bad = await create({ projectId: 'p1', prompt: '' });
    expect(bad.statusCode).toBe(400);
    expect(bad.json()).toEqual({ error: 'bad_request' });
    expect((await create({ projectId: 'p1', prompt: 'a'.repeat(10001) })).statusCode).toBe(400);
    expect((await create({ projectId: 5, prompt: 'x' })).statusCode).toBe(400);
    expect((await create({ projectId: 'zz', prompt: 'hi' })).statusCode).toBe(404);
    const c = await create({ projectId: 'p1', prompt: 'hi' });
    expect(c.statusCode).toBe(200);
    const id = c.json().agentId as string;
    expect(typeof id).toBe('string');
    const fu = await s.app.inject({ method: 'POST', url: `/agents/${id}/messages`, headers: s.h, payload: { prompt: 'more' } });
    expect(fu.statusCode).toBe(409);
    expect(fu.json()).toEqual({ error: 'busy' });
    const list = await s.app.inject({ url: '/agents', headers: s.h });
    expect(list.json()).toHaveLength(1);
    const det = await s.app.inject({ url: `/agents/${id}`, headers: s.h });
    expect(det.json().agent.id).toBe(id);
    expect(Array.isArray(det.json().events)).toBe(true);
    expect(det.json().approvals).toEqual([]);
    expect((await s.app.inject({ url: '/agents/nope', headers: s.h })).statusCode).toBe(404);
    expect((await s.app.inject({ method: 'POST', url: '/agents/nope/stop', headers: s.h })).statusCode).toBe(404);
    expect((await s.app.inject({ method: 'POST', url: '/agents/nope/messages', headers: s.h, payload: { prompt: 'x' } })).statusCode).toBe(404);
    const st = await s.app.inject({ method: 'POST', url: `/agents/${id}/stop`, headers: s.h });
    expect(st.statusCode).toBe(200);
    expect(st.json()).toEqual({ ok: true });
  });

  it('approvals: bad body 400, unknown 404, allow once then 404', async () => {
    const s = setup(SIMPLE);
    const post = (id: string, payload: unknown) =>
      s.app.inject({ method: 'POST', url: `/approvals/${id}`, headers: s.h, payload: payload as object });
    expect((await post('x', { decision: 'allow' })).statusCode).toBe(404);
    expect((await post('x', { decision: 'maybe' })).statusCode).toBe(400);
    const p = s.broker.request('agent1', 'Bash', { command: 'ls' });
    const approvalId = s.broker.pending()[0]!.approvalId;
    const r1 = await post(approvalId, { decision: 'allow' });
    expect(r1.statusCode).toBe(200);
    expect(r1.json()).toEqual({ ok: true });
    expect(await p).toBeTruthy();
    expect((await post(approvalId, { decision: 'allow' })).statusCode).toBe(404);
  });
});

describe('server WebSocket /events', () => {
  async function connect(s: ReturnType<typeof setup>, query = '', opts: WebSocket.ClientOptions = {}) {
    if (!s.app.server.listening) await s.app.listen({ port: 0, host: '127.0.0.1' });
    const port = (s.app.server.address() as { port: number }).port;
    const ws = new WebSocket(`ws://127.0.0.1:${port}/events${query}`, { headers: s.h, ...opts });
    const c = collect(ws);
    await open(ws);
    return { ws, ...c };
  }
  const closed = (ws: WebSocket) => new Promise<number>((res) => ws.on('close', (code) => res(code)));

  it('rejects unauthenticated upgrades, including ?token=', async () => {
    const s = setup(SIMPLE);
    await s.app.listen({ port: 0, host: '127.0.0.1' });
    const port = (s.app.server.address() as { port: number }).port;
    for (const q of ['?token=bad', `?token=${s.token}`, '']) {
      const ws = new WebSocket(`ws://127.0.0.1:${port}/events${q}`);
      const err = await new Promise<Error>((res) => ws.on('error', res));
      expect(err.message).toMatch(/401/);
    }
  });

  it('hello first, then backlog, then live (header auth)', async () => {
    const s = setup(SIMPLE);
    s.bus.emit({ type: 'message', agentId: 'a', text: 'one' });
    s.bus.emit({ type: 'message', agentId: 'a', text: 'two' });
    const c = await connect(s, '?since=0');
    await c.until(() => c.msgs.length >= 3);
    expect(c.msgs[0]).toEqual({ type: 'hello', bootId: 'boot-1', seq: 2 });
    expect(c.msgs.slice(1).map((m) => m.text)).toEqual(['one', 'two']);
    s.bus.emit({ type: 'message', agentId: 'a', text: 'three' });
    await c.until(() => c.msgs.length >= 4);
    expect(c.msgs[3].text).toBe('three');
    expect(c.msgs.some((m) => m.type === 'reset')).toBe(false);
    c.ws.close();
    const h = await connect(s);
    await h.until(() => h.msgs.length >= 4);
    expect(h.msgs[0]).toEqual({ type: 'hello', bootId: 'boot-1', seq: 3 });
    expect(h.msgs).toHaveLength(4);
    h.ws.close();
  });

  it('resumes after since=N', async () => {
    const s = setup(SIMPLE);
    s.bus.emit({ type: 'message', agentId: 'a', text: 'one' });
    s.bus.emit({ type: 'message', agentId: 'a', text: 'two' });
    const c = await connect(s, '?since=1');
    await c.until(() => c.msgs.length >= 2);
    expect(c.msgs[0].type).toBe('hello');
    expect(c.msgs.slice(1).map((m) => m.text)).toEqual(['two']);
    c.ws.close();
  });

  it.each(['999', 'abc', '-1', '1.5'])('stale/invalid since=%s gets hello, exactly one reset, then live', async (v) => {
    const s = setup(SIMPLE);
    s.bus.emit({ type: 'message', agentId: 'a', text: 'one' });
    const c = await connect(s, `?since=${v}`);
    await c.until(() => c.msgs.length >= 2);
    expect(c.msgs).toEqual([
      { type: 'hello', bootId: 'boot-1', seq: 1 },
      { type: 'reset', bootId: 'boot-1', seq: 1 },
    ]);
    s.bus.emit({ type: 'message', agentId: 'a', text: 'live' });
    await c.until(() => c.msgs.length >= 3);
    expect(c.msgs[2].text).toBe('live');
    expect(c.msgs.filter((m) => m.type === 'reset')).toHaveLength(1);
    c.ws.close();
  });

  it('reset when the ring has evicted events', async () => {
    const small = new EventBus(2);
    const s = setup(SIMPLE, small);
    for (let i = 0; i < 5; i++) small.emit({ type: 'message', agentId: 'a', text: String(i) });
    const c = await connect(s, '?since=1');
    await c.until(() => c.msgs.length >= 2);
    expect(c.msgs).toEqual([
      { type: 'hello', bootId: 'boot-1', seq: 5 },
      { type: 'reset', bootId: 'boot-1', seq: 5 },
    ]);
    c.ws.close();
  });

  it('unsubscribes when the socket closes', async () => {
    const s = setup(SIMPLE);
    const count = () => (s.bus as unknown as { subscribers: Set<unknown> }).subscribers.size;
    const before = count();
    const c = await connect(s);
    await c.until(() => count() > before);
    c.ws.close();
    await c.until(() => count() === before);
  });

  it('heartbeat terminates sockets that miss a pong and keeps live ones', async () => {
    const s = setup(SIMPLE, new EventBus(), { heartbeatMs: 50 });
    const live = await connect(s);
    const dead = await connect(s, '', { autoPong: false });
    let liveClosed = false;
    live.ws.on('close', () => (liveClosed = true));
    const code = await closed(dead.ws);
    expect(code).toBe(1006); // terminated, no close frame
    await new Promise((r) => setTimeout(r, 200));
    expect(liveClosed).toBe(false);
    live.ws.close();
  });

  it('revoking a device closes its open sockets with 4001', async () => {
    const s = setup(SIMPLE);
    const other = (s.auth.pair(s.auth.startPairing().code, 'other') as { token: string }).token;
    const mine = await connect(s);
    const theirs = await connect(s, '', { headers: { authorization: `Bearer ${other}` } });
    let theirsClosed = false;
    theirs.ws.on('close', () => (theirsClosed = true));
    const code = closed(mine.ws);
    expect(s.auth.revoke('watch')).toBe(true);
    expect(await code).toBe(4001);
    await new Promise((r) => setTimeout(r, 100));
    expect(theirsClosed).toBe(false);
    theirs.ws.close();
  });
});

describe('chats routes', () => {
  const SID = '11111111-2222-4333-8444-555555555555';
  function chatRoot() {
    const root = join(home, 'proj');
    mkdirSync(join(root, 'C--code-demo'), { recursive: true });
    writeFileSync(
      join(root, 'C--code-demo', `${SID}.jsonl`),
      [
        JSON.stringify({ type: 'user', cwd: '/secret/place/demo', timestamp: '2026-11-04T19:00:00.000Z', message: { content: 'hello there' } }),
        JSON.stringify({ type: 'assistant', timestamp: '2026-11-04T19:00:01.000Z', message: { content: [{ type: 'text', text: 'hi' }], stop_reason: 'end_turn' } }),
      ].join(String.fromCharCode(10)) + String.fromCharCode(10),
    );
    return new ChatIndex({ rootDir: root, excludeSessionIds: () => new Set(), maxAgeDays: 36500, tmpDir: join(home, 'nothing') });
  }

  it('lists and gets chats without exposing cwd', async () => {
    const { app, h } = setup({}, undefined, { chats: chatRoot() });
    const l = await app.inject({ method: 'GET', url: '/chats', headers: h });
    expect(l.statusCode).toBe(200);
    expect(l.json()).toHaveLength(1);
    expect(l.json()[0]).toMatchObject({ id: SID, title: 'hello there', projectName: 'demo' });
    const g = await app.inject({ method: 'GET', url: `/chats/${SID}`, headers: h });
    expect(g.statusCode).toBe(200);
    expect(g.json().items.map((i: any) => i.kind)).toEqual(['prompt', 'reply']);
    expect(l.body + g.body).not.toContain('cwd');
    expect(l.body + g.body).not.toContain('secret');
  });

  it('400 on bad id, 404 on unknown, 401 without token', async () => {
    const { app, h } = setup({}, undefined, { chats: chatRoot() });
    expect((await app.inject({ method: 'GET', url: '/chats/not-an-id', headers: h })).statusCode).toBe(400);
    const nf = await app.inject({ method: 'GET', url: '/chats/00000000-0000-4000-8000-000000000000', headers: h });
    expect(nf.statusCode).toBe(404);
    expect(nf.json()).toEqual({ error: 'not_found' });
    expect((await app.inject({ method: 'GET', url: '/chats' })).statusCode).toBe(401);
  });

  it('404 when disabled or no index', async () => {
    const a = setup({}, undefined, { chats: chatRoot(), showPcChats: false });
    expect((await a.app.inject({ method: 'GET', url: '/chats', headers: a.h })).statusCode).toBe(404);
    expect((await a.app.inject({ method: 'GET', url: `/chats/${SID}`, headers: a.h })).statusCode).toBe(404);
    await a.app.close();
    const b = setup({});
    expect((await b.app.inject({ method: 'GET', url: '/chats', headers: b.h })).statusCode).toBe(404);
  });
});

describe('POST /chats/:id/messages', () => {
  const sid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
  const NL = String.fromCharCode(10);
  function relayRoot(titledCount = 5) {
    const root = join(home, 'proj');
    mkdirSync(join(root, 'C--code-demo'), { recursive: true });
    for (let n = 1; n <= titledCount + 1; n++) {
      const lines = [JSON.stringify({ type: 'user', cwd: '/x/demo', timestamp: '2026-11-04T19:00:00.000Z', message: { content: `prompt ${n}` } })];
      if (n <= titledCount) lines.push(JSON.stringify({ type: 'custom-title', customTitle: `Chat ${n}` }));
      writeFileSync(join(root, 'C--code-demo', `${sid(n)}.jsonl`), lines.join(NL) + NL);
    }
    return new ChatIndex({ rootDir: root, excludeSessionIds: () => new Set(), maxAgeDays: 36500, tmpDir: join(home, 'nothing') });
  }
  const UNTITLED = sid(6);
  type Call = { title: string; text: string; done: (r: RelayOutcome) => void };
  function controlledRelay() {
    const calls: Call[] = [];
    const relay = (title: string, text: string) => new Promise<RelayOutcome>((done) => calls.push({ title, text, done }));
    return { calls, relay };
  }
  const post = (app: FastifyInstance, h: Record<string, string>, id: string, body: unknown = { prompt: 'hi there' }) =>
    app.inject({ method: 'POST', url: `/chats/${id}/messages`, headers: h, payload: body as object });

  it('maps relay outcomes to status codes and passes the full custom title', async () => {
    const seen: [string, string][] = [];
    let next: RelayOutcome = 'sent';
    const { app, h } = setup({}, undefined, { chats: relayRoot(), relay: async (t, x) => { seen.push([t, x]); return next; } });
    const cases: [RelayOutcome, number, unknown][] = [
      ['sent', 200, { ok: true }],
      ['not_found', 409, { error: 'not_live' }],
      ['ambiguous', 409, { error: 'ambiguous' }],
      ['failed', 502, { error: 'relay_failed' }],
    ];
    for (const [outcome, code, body] of cases) {
      next = outcome;
      const r = await post(app, h, sid(1));
      expect(r.statusCode).toBe(code);
      expect(r.json()).toEqual(body);
    }
    expect(seen[0]).toEqual(['Chat 1', 'hi there']);
  });

  it('looks the chat up with one directory scan', async () => {
    const chats = relayRoot();
    const { app, h } = setup({}, undefined, { chats, relay: async () => 'sent' });
    const before = chats.scanCount;
    expect((await post(app, h, sid(1))).statusCode).toBe(200);
    expect(chats.scanCount - before).toBe(1);
  });

  it('a throwing relay is 502 and frees the lock', async () => {
    let fail = true;
    const { app, h } = setup({}, undefined, { chats: relayRoot(), relay: async () => { if (fail) throw new Error('boom'); return 'sent'; } });
    expect((await post(app, h, sid(1))).statusCode).toBe(502);
    fail = false;
    expect((await post(app, h, sid(1))).statusCode).toBe(200);
  });

  it('validation: 401, 400 bad id/prompt, 404 unknown, 409 untitled', async () => {
    const { calls, relay } = controlledRelay();
    const { app, h } = setup({}, undefined, { chats: relayRoot(), relay });
    expect((await post(app, {}, sid(1))).statusCode).toBe(401);
    expect((await post(app, h, 'nope')).statusCode).toBe(400);
    expect((await post(app, h, sid(1), { prompt: '  ' })).statusCode).toBe(400);
    expect((await post(app, h, sid(1), { prompt: 'x'.repeat(10_001) })).statusCode).toBe(400);
    expect((await post(app, h, sid(1), {})).statusCode).toBe(400);
    const nf = await post(app, h, sid(42));
    expect(nf.statusCode).toBe(404);
    expect(nf.json()).toEqual({ error: 'not_found' });
    const un = await post(app, h, UNTITLED);
    expect(un.statusCode).toBe(409);
    expect(un.json()).toEqual({ error: 'untitled' });
    expect(calls).toHaveLength(0);
  });

  it('404 when showPcChats is false', async () => {
    const { app, h } = setup({}, undefined, { chats: relayRoot(), showPcChats: false, relay: async () => 'sent' });
    expect((await post(app, h, sid(1))).statusCode).toBe(404);
  });

  it('one relay per chat (429 busy) and at most 3 in flight overall', async () => {
    const { calls, relay } = controlledRelay();
    const { app, h } = setup({}, undefined, { chats: relayRoot(), relay });
    const p1 = post(app, h, sid(1));
    await vi.waitFor(() => expect(calls).toHaveLength(1));
    const again = await post(app, h, sid(1));
    expect(again.statusCode).toBe(429);
    expect(again.json()).toEqual({ error: 'busy' });
    const p2 = post(app, h, sid(2));
    const p3 = post(app, h, sid(3));
    await vi.waitFor(() => expect(calls).toHaveLength(3));
    const fourth = await post(app, h, sid(4));
    expect(fourth.statusCode).toBe(429);
    expect(fourth.json()).toEqual({ error: 'busy' });
    calls[0]!.done('sent');
    expect((await p1).statusCode).toBe(200);
    const p4 = post(app, h, sid(4));
    await vi.waitFor(() => expect(calls).toHaveLength(4));
    for (const c of calls.slice(1)) c.done('sent');
    for (const p of [p2, p3, p4]) expect((await p).statusCode).toBe(200);
    expect(calls.map((c) => c.title)).toEqual(['Chat 1', 'Chat 2', 'Chat 3', 'Chat 4']);
  });
});
