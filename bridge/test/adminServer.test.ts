import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DeviceAuth } from '../src/auth.js';
import { EventBus } from '../src/events.js';
import { ApprovalBroker } from '../src/approvals.js';
import { AgentManager } from '../src/agents.js';
import { createServer } from '../src/server.js';
import { startAdminServer } from '../src/adminServer.js';

let home: string;
let prev: string | undefined;
beforeEach(() => {
  home = mkdtempSync(join(tmpdir(), 'wc-admin-'));
  prev = process.env.WEARCLAUDE_HOME;
  process.env.WEARCLAUDE_HOME = home;
});
afterEach(() => {
  if (prev === undefined) delete process.env.WEARCLAUDE_HOME;
  else process.env.WEARCLAUDE_HOME = prev;
  rmSync(home, { recursive: true, force: true });
});

const SECRET = 'a'.repeat(64);
async function boot() {
  const auth = new DeviceAuth();
  const admin = await startAdminServer({ auth, port: 0, secret: SECRET });
  const url = (p: string) => `http://127.0.0.1:${admin.port}${p}`;
  const call = (p: string, headers: Record<string, string> = { 'x-wearclaude-admin': SECRET }, body?: unknown) =>
    fetch(url(p), { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body ?? {}) });
  return { auth, admin, call, url };
}

describe('admin server', () => {
  it('issues a pairing code with the secret', async () => {
    const s = await boot();
    const r = await s.call('/pairing-code');
    expect(r.status).toBe(200);
    expect((await r.json()).code).toMatch(/^\d{6}$/);
    await s.admin.close();
  });

  it('rejects missing or wrong secret', async () => {
    const s = await boot();
    expect((await s.call('/pairing-code', {})).status).toBe(403);
    expect((await s.call('/pairing-code', { 'x-wearclaude-admin': 'nope' })).status).toBe(403);
    await s.admin.close();
  });

  it('rejects proxied requests even with the secret', async () => {
    const s = await boot();
    const base = { 'x-wearclaude-admin': SECRET };
    expect((await s.call('/pairing-code', { ...base, 'x-forwarded-for': '1.2.3.4' })).status).toBe(403);
    expect((await s.call('/pairing-code', { ...base, forwarded: 'for=1.2.3.4' })).status).toBe(403);
    expect((await s.call('/pairing-code', { ...base, 'tailscale-user-login': 'a@b.c' })).status).toBe(403);
    await s.admin.close();
  });

  it('revoke takes effect on the main server immediately', async () => {
    const s = await boot();
    const bus = new EventBus();
    const broker = new ApprovalBroker(bus, 30);
    const agents = new AgentManager({
      config: { port: 0, bindHost: '127.0.0.1', projects: [], defaultPermissionMode: 'default', approvalTimeoutSec: 30, showPcChats: true, pcChatsMaxAgeDays: 30, useDesktopMetadata: true },
      bus, broker, mcpConfigFor: () => '{}', claudeCmd: { bin: process.execPath, preArgs: [] },
    });
    const app = createServer({
      config: { port: 0, bindHost: '127.0.0.1', projects: [], defaultPermissionMode: 'default', approvalTimeoutSec: 30, showPcChats: true, pcChatsMaxAgeDays: 30, useDesktopMetadata: true },
      auth: s.auth, agents, broker, bus,
    });
    const token = (s.auth.pair(s.auth.startPairing().code, 'e2e') as { token: string }).token;
    const h = { authorization: `Bearer ${token}` };
    expect((await app.inject({ url: '/agents', headers: h })).statusCode).toBe(200);
    const r = await s.call('/devices/revoke', undefined, { name: 'e2e' });
    expect(await r.json()).toEqual({ revoked: true });
    expect((await app.inject({ url: '/agents', headers: h })).statusCode).toBe(401);
    expect((await app.inject({ method: 'POST', url: '/local/pairing-code' })).statusCode).toBe(401);
    const list = await fetch(s.url('/devices'), { headers: { 'x-wearclaude-admin': SECRET } });
    expect(await list.json()).toEqual([]);
    await s.admin.close();
  });
});
