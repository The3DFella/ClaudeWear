import { describe, it, expect, afterEach } from 'vitest';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { EventBus } from '../src/events.js';
import { ApprovalBroker } from '../src/approvals.js';
import { startPermissionServer } from '../src/permissionServer.js';

let closer: (() => Promise<void>) | undefined;
afterEach(async () => { await closer?.(); closer = undefined; });

async function call(url: string, bus: EventBus, broker: ApprovalBroker, decision: 'allow' | 'deny' | 'timeout') {
  const client = new Client({ name: 't', version: '1' });
  await client.connect(new StreamableHTTPClientTransport(new URL(url)));
  const p = client.callTool({ name: 'approve', arguments: { tool_name: 'Bash', input: { command: 'ls' }, tool_use_id: 'tu1' } });
  await new Promise((r) => setTimeout(r, 100));
  if (decision !== 'timeout') {
    const id = (bus.recent('a1').find((e) => e.type === 'approval_requested') as any).approvalId;
    broker.resolve(id, decision);
  }
  const res: any = await p;
  await client.close();
  return JSON.parse(res.content[0].text);
}

describe('permission server', () => {
  it('allow returns allow shape with original input', async () => {
    const bus = new EventBus(); const broker = new ApprovalBroker(bus, 30);
    const srv = await startPermissionServer(broker, 0); closer = srv.close;
    const url = JSON.parse(srv.mcpConfigFor('a1')).mcpServers.wearclaude.url;
    expect(srv.mcpConfigFor('a1')).toBe(srv.mcpConfigFor('a1'));
    expect(JSON.parse(srv.mcpConfigFor('a1')).mcpServers.wearclaude.type).toBe('http');
    expect(await call(url, bus, broker, 'allow')).toEqual({ behavior: 'allow', updatedInput: { command: 'ls' } });
  });

  it('deny returns deny message', async () => {
    const bus = new EventBus(); const broker = new ApprovalBroker(bus, 30);
    const srv = await startPermissionServer(broker, 0); closer = srv.close;
    const url = JSON.parse(srv.mcpConfigFor('a1')).mcpServers.wearclaude.url;
    expect(await call(url, bus, broker, 'deny')).toEqual({ behavior: 'deny', message: 'Denied from watch' });
  });

  it('timeout returns timeout message', async () => {
    const bus = new EventBus(); const broker = new ApprovalBroker(bus, 0.2);
    const srv = await startPermissionServer(broker, 0); closer = srv.close;
    const url = JSON.parse(srv.mcpConfigFor('a1')).mcpServers.wearclaude.url;
    expect(await call(url, bus, broker, 'timeout')).toEqual({ behavior: 'deny', message: 'Timed out — denied' });
  });

  it('wrong secret or unknown agent gives 403', async () => {
    const bus = new EventBus(); const broker = new ApprovalBroker(bus, 30);
    const srv = await startPermissionServer(broker, 0); closer = srv.close;
    const url: string = JSON.parse(srv.mcpConfigFor('a1')).mcpServers.wearclaude.url;
    const bad = url.replace(/[^/]+$/, 'wrong');
    const r = await fetch(bad, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
    expect(r.status).toBe(403);
    srv.forgetAgent('a1');
    const r2 = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
    expect(r2.status).toBe(403);
  });
});
