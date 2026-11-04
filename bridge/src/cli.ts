#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { homedir, networkInterfaces } from 'node:os';
import { randomBytes, randomUUID } from 'node:crypto';
import { readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { addProject, homeDir, loadConfig, saveConfig } from './config.js';
import { startAdminServer } from './adminServer.js';
import { EventBus } from './events.js';
import { ApprovalBroker } from './approvals.js';
import { startPermissionServer } from './permissionServer.js';
import { AgentManager } from './agents.js';
import { DeviceAuth } from './auth.js';
import { createServer } from './server.js';
import { ChatIndex } from './chatIndex.js';
import { DesktopSessions } from './desktopSessions.js';
import { ChatWatcher } from './chatWatcher.js';
import { checkClaude, resolveClaude } from './doctor.js';
import { relayCwd, relayToChat } from './relay.js';
import { PRODUCT_NAME } from './product.js';

const USAGE = `${PRODUCT_NAME} bridge

Usage:
  wearclaude-bridge [start] [--skip-probe]   Start the bridge
  wearclaude-bridge pair               Print a fresh pairing code from the running bridge
  wearclaude-bridge add-project <path> [--name <name>]
  wearclaude-bridge projects
  wearclaude-bridge devices
  wearclaude-bridge devices revoke <name>`;

function lanUrls(port: number): string[] {
  const out: string[] = [];
  for (const addrs of Object.values(networkInterfaces())) {
    for (const a of addrs ?? []) {
      if (a.family === 'IPv4' && !a.internal) out.push(`http://${a.address}:${port}`);
    }
  }
  return out;
}

function funnelUrl(): Promise<string | null> {
  return new Promise((resolve) => {
    try {
      execFile('tailscale', ['funnel', 'status', '--json'], { timeout: 3000, windowsHide: true }, (err, stdout) => {
        if (err) return resolve(null);
        try {
          JSON.parse(stdout); // must be valid JSON
          const m = /[A-Za-z0-9.-]+\.ts\.net/.exec(stdout);
          resolve(m ? `https://${m[0]}` : null);
        } catch {
          resolve(null);
        }
      });
    } catch {
      resolve(null);
    }
  });
}

async function start(): Promise<void> {
  const config = loadConfig();
  const claudeCmd = resolveClaude(config);
  const skipProbe = process.argv.includes('--skip-probe');
  const check = await checkClaude(claudeCmd, undefined, skipProbe);
  if (!check.ok) {
    console.error(`Problem: ${check.problem}`);
    process.exit(1);
  }
  console.log(`claude ${check.version} OK`);

  const bus = new EventBus();
  let agents: AgentManager | undefined;
  const broker = new ApprovalBroker(bus, config.approvalTimeoutSec, (id, waiting) => agents?.setWaiting(id, waiting));
  const perm = await startPermissionServer(broker, config.port + 1);
  agents = new AgentManager({ config, bus, broker, mcpConfigFor: perm.mcpConfigFor, claudeCmd });
  agents.loadOnStartup();
  const auth = new DeviceAuth();
  const mgr = agents;
  const chats = config.showPcChats
    ? new ChatIndex({
        rootDir: join(homedir(), '.claude', 'projects'),
        excludeSessionIds: () => new Set(mgr.list().map((a) => a.sessionId).filter((x): x is string => !!x)),
        maxAgeDays: config.pcChatsMaxAgeDays,
        desktop: config.useDesktopMetadata ? new DesktopSessions({}) : undefined,
      })
    : undefined;
  const relay = (title: string, text: string) => relayToChat({ title, text, claudeCmd, cwd: relayCwd() });
  const app = createServer({ config, auth, agents, broker, bus, chats, relay, bootId: randomUUID() });
  await app.listen({ port: config.port, host: config.bindHost });
  const secret = randomBytes(32).toString('hex');
  // Written before the admin listener starts, so a CLI that reaches the listener always finds the current secret.
  mkdirSync(homeDir(), { recursive: true });
  writeFileSync(secretFile(), secret, { mode: 0o600 });
  const adminSrv = await startAdminServer({ auth, port: config.port + 2, secret });
  const chatWatcher = chats ? new ChatWatcher({ index: chats, bus }) : undefined;
  chatWatcher?.start();

  console.log(`${PRODUCT_NAME} bridge listening on ${config.bindHost}:${config.port}`);
  for (const u of lanUrls(config.port)) console.log(`  LAN: ${u}`);
  const f = await funnelUrl();
  if (f) console.log(`  Funnel: ${f} (public URL, protected only by the device token)`);
  const { code } = auth.startPairing();
  console.log(`\nPairing code: ${code}  (valid 5 minutes)`);
  console.log('Run `wearclaude-bridge pair` for a new one.');

  let closing = false;
  const shutdown = () => {
    if (closing) return;
    closing = true;
    chatWatcher?.stop();
    rmSync(secretFile(), { force: true });
    void Promise.allSettled([app.close(), perm.close(), adminSrv.close()]).then(() => process.exit(0));
    setTimeout(() => process.exit(0), 3000).unref();
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

const secretFile = () => join(homeDir(), 'admin.secret');

// Returns null only when the bridge is not running (no secret file, or connection refused).
// Any other failure (timeout, reset, ...) is an error: the bridge may be running, so do not fall back.
async function admin(path: string, method: string, body?: unknown): Promise<unknown | null> {
  const config = loadConfig();
  let secret: string;
  try {
    secret = readFileSync(secretFile(), 'utf8').trim();
  } catch {
    return null;
  }
  let r: Response;
  try {
    r = await fetch(`http://127.0.0.1:${config.port + 2}${path}`, {
      method,
      headers: { 'x-wearclaude-admin': secret, 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(5000),
    });
  } catch (err) {
    const cause = (err as { cause?: { code?: unknown; errors?: { code?: unknown }[] } } | null)?.cause;
    const code = cause?.code;
    if (code === 'ECONNREFUSED' || cause?.errors?.some((e) => e.code === 'ECONNREFUSED')) return null;
    const why = err instanceof Error && err.name === 'TimeoutError' ? 'timed out' : code ? String(code) : String(err);
    console.error(`Could not reach the running bridge's admin port (${why}); nothing was changed.`);
    process.exit(1);
  }
  if (r.status === 403) {
    console.error('Admin secret mismatch — restart the bridge');
    process.exit(1);
  }
  if (r.status !== 200) {
    console.error(`Admin request failed: HTTP ${r.status}`);
    process.exit(1);
  }
  return r.json();
}

async function pair(): Promise<void> {
  const r = (await admin('/pairing-code', 'POST', {})) as { code: string } | null;
  if (!r) {
    console.error('Bridge is not running — start it with `wearclaude-bridge`');
    process.exit(1);
  }
  console.log(`Pairing code: ${r.code}  (valid 5 minutes)`);
}

async function main(): Promise<void> {
  const [cmd = 'start', ...rest] = process.argv.slice(2);
  switch (cmd) {
    case 'start':
      return start();
    case 'pair':
      return pair();
    case 'add-project': {
      const nameIdx = rest.indexOf('--name');
      let name: string | undefined;
      if (nameIdx !== -1) {
        name = rest[nameIdx + 1];
        rest.splice(nameIdx, 2);
      }
      const path = rest[0];
      if (!path) {
        console.error(USAGE);
        process.exit(1);
      }
      const config = loadConfig();
      const p = addProject(config, path, name);
      config.projects.push(p);
      saveConfig(config);
      console.log(`Added project ${p.name} (${p.id}) -> ${p.path}`);
      return;
    }
    case 'projects': {
      const config = loadConfig();
      if (config.projects.length === 0) console.log('No projects. Add one with `wearclaude-bridge add-project <path>`.');
      for (const p of config.projects) console.log(`${p.id}\t${p.name}\t${p.path}`);
      return;
    }
    case 'devices': {
      if (rest[0] === 'revoke') {
        const name = rest[1];
        if (!name) {
          console.error(USAGE);
          process.exit(1);
        }
        const live = (await admin('/devices/revoke', 'POST', { name })) as { revoked: boolean } | null;
        const revoked = live ? live.revoked : new DeviceAuth().revoke(name);
        if (revoked) console.log(`Revoked ${name}.`);
        else {
          console.error(`No device named ${name}`);
          process.exit(1);
        }
        return;
      }
      if (rest.length > 0) {
        console.error(USAGE);
        process.exit(1);
      }
      const list = ((await admin('/devices', 'GET')) as { name: string; createdAt: number }[] | null) ?? new DeviceAuth().list();
      if (list.length === 0) console.log('No paired devices.');
      for (const d of list) console.log(`${d.name}\t${new Date(d.createdAt).toISOString()}`);
      return;
    }
    default:
      console.error(USAGE);
      process.exit(1);
  }
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : String(err));
  process.exit(1);
});
