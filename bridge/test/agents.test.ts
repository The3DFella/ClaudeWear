import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, writeFileSync, readFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { EventBus } from '../src/events.js';
import { ApprovalBroker } from '../src/approvals.js';
import { buildClaudeArgs } from '../src/claudeArgs.js';
import { AgentManager, Busy, NotFound, UnknownProject } from '../src/agents.js';
import type { WatchEvent } from '../src/types.js';

let home: string;
let prevHome: string | undefined;
beforeEach(() => {
  home = mkdtempSync(join(tmpdir(), 'wc-agents-'));
  prevHome = process.env.WEARCLAUDE_HOME;
  process.env.WEARCLAUDE_HOME = home;
});
afterEach(() => {
  if (prevHome === undefined) delete process.env.WEARCLAUDE_HOME;
  else process.env.WEARCLAUDE_HOME = prevHome;
  rmSync(home, { recursive: true, force: true });
});

const fixture = (n: string) => resolve('test/fixtures', n);

function setup(env: NodeJS.ProcessEnv, mcpConfigFor: (id: string) => string = () => '{"mcpServers":{}}') {
  const bus = new EventBus();
  const holder: { mgr?: AgentManager } = {};
  const broker = new ApprovalBroker(bus, 30, (id, w) => holder.mgr?.setWaiting(id, w));
  const mgr = new AgentManager({
    config: {
      port: 0, bindHost: '127.0.0.1',
      projects: [{ id: 'p1', name: 'P', path: process.cwd() }],
      defaultPermissionMode: 'default', approvalTimeoutSec: 30, showPcChats: true, pcChatsMaxAgeDays: 30, useDesktopMetadata: true,
    },
    bus, broker,
    mcpConfigFor,
    claudeCmd: { bin: process.execPath, preArgs: ['test/fake-claude.mjs'] },
    env,
  });
  holder.mgr = mgr;
  const events: WatchEvent[] = [];
  bus.subscribe((e) => events.push(e));
  const waitFor = (pred: (e: WatchEvent) => boolean, ms = 10_000) =>
    new Promise<WatchEvent>((res, rej) => {
      const hit = events.find(pred);
      if (hit) return res(hit);
      const t = setTimeout(() => { off(); rej(new Error('timeout waiting for event')); }, ms);
      const off = bus.subscribe((e) => { if (pred(e)) { clearTimeout(t); off(); res(e); } });
    });
  return { bus, broker, mgr, events, waitFor };
}

const isStatus = (s: string) => (e: WatchEvent) => e.type === 'status' && e.status === s;

describe('buildClaudeArgs', () => {
  const base = { sessionId: 's1', mcpConfig: '{}', permissionMode: 'default' };
  it('uses --session-id for new runs and --resume for follow-ups, never --bare', () => {
    const a = buildClaudeArgs({ ...base, resume: false });
    expect(a).toEqual(['-p', '--output-format', 'stream-json', '--verbose', '--session-id', 's1', '--mcp-config', '{}', '--permission-prompt-tool', 'mcp__wearclaude__approve', '--permission-mode', 'default']);
    const b = buildClaudeArgs({ ...base, resume: true });
    expect(b).toContain('--resume');
    expect(b).not.toContain('--session-id');
    expect([...a, ...b]).not.toContain('--bare');
  });
});

describe('AgentManager', () => {
  it('runs a fixture to done', async () => {
    const { mgr, events, waitFor } = setup({ FAKE_CLAUDE_FIXTURE: fixture('simple-text.ndjson') });
    const info = mgr.create('p1', 'hello');
    await waitFor(isStatus('done'));
    const types = events.filter((e) => e.agentId === info.id).map((e) => (e.type === 'status' ? `status:${e.status}` : e.type));
    expect(types[0]).toBe('agent_created');
    expect(types[1]).toBe('status:running');
    expect(types).toContain('summary');
    expect(types.at(-1)).toBe('status:done');
    const got = mgr.get(info.id)!;
    expect(got.status).toBe('done');
    expect(got.summary).toBe('Hi there, how are you?');
    const persisted = JSON.parse(readFileSync(join(home, 'agents.json'), 'utf8'));
    expect(persisted[0].status).toBe('done');
  });

  it('round-trips tricky prompts via stdin', async () => {
    const { mgr, waitFor } = setup({ FAKE_CLAUDE_ECHO: '1' });
    const prompt = 'He said "hi" & 100% done\nline2 \u{1F389}';
    mgr.create('p1', prompt);
    const s = (await waitFor((e) => e.type === 'summary')) as Extract<WatchEvent, { type: 'summary' }>;
    expect(s.text).toBe(prompt);
  });

  it('followUp throws Busy while running, NotFound for unknown', async () => {
    const { mgr, waitFor } = setup({ FAKE_CLAUDE_HANG: '1' });
    const info = mgr.create('p1', 'x');
    expect(() => mgr.followUp(info.id, 'y')).toThrow(Busy);
    expect(() => mgr.followUp('nope', 'y')).toThrow(NotFound);
    mgr.stop(info.id);
    await waitFor(isStatus('stopped'));
  });

  it('followUp after done resumes and runs again', async () => {
    const { mgr, events, waitFor } = setup({ FAKE_CLAUDE_ECHO: '1' });
    const info = mgr.create('p1', 'one');
    await waitFor(isStatus('done'));
    mgr.followUp(info.id, 'two');
    expect(mgr.get(info.id)!.status).toBe('running');
    await waitFor((e) => e.type === 'summary' && e.text === 'two');
    await new Promise((r) => setTimeout(r, 100));
    expect(events.filter(isStatus('done'))).toHaveLength(2);
    expect(mgr.get(info.id)!.status).toBe('done');
  });

  it('stop denies pending approvals then emits stopped, and does not mark failed', async () => {
    const { mgr, broker, events, waitFor } = setup({ FAKE_CLAUDE_HANG: '1' });
    const info = mgr.create('p1', 'x');
    const p = broker.request(info.id, 'Bash', { command: 'ls' });
    expect(mgr.get(info.id)!.status).toBe('waiting_approval');
    mgr.stop(info.id);
    expect(await p).toBe('deny');
    await waitFor(isStatus('stopped'));
    const seq = events.filter((e) => e.agentId === info.id).map((e) => (e.type === 'status' ? `status:${e.status}` : e.type));
    expect(seq.indexOf('approval_resolved')).toBeLessThan(seq.indexOf('status:stopped'));
    expect(seq.slice(seq.indexOf('approval_resolved'))).not.toContain('status:running');
    await new Promise((r) => setTimeout(r, 500));
    expect(mgr.get(info.id)!.status).toBe('stopped');
    expect(events.some(isStatus('failed'))).toBe(false);
  });

  it('marks failed when child exits without a result', async () => {
    const { mgr, waitFor } = setup({});
    mgr.create('p1', 'x');
    const e = (await waitFor(isStatus('failed'))) as Extract<WatchEvent, { type: 'status' }>;
    expect(e.reason).toContain('claude exited (code 0)');
  });

  it('loadOnStartup marks running agents interrupted', () => {
    const { mgr } = setup({});
    const mk = (id: string, status: string) => ({ id, title: id, projectId: 'p1', status, createdAt: 1, updatedAt: 1, sessionId: id });
    writeFileSync(join(home, 'agents.json'), JSON.stringify([mk('a', 'running'), mk('b', 'waiting_approval'), mk('c', 'done')]));
    mgr.loadOnStartup();
    expect(mgr.list().map((a) => [a.id, a.status])).toEqual([['a', 'interrupted'], ['b', 'interrupted'], ['c', 'done']]);
  });

  it('throws UnknownProject', () => {
    const { mgr } = setup({});
    expect(() => mgr.create('zzz', 'x')).toThrow(UnknownProject);
  });

  it('title is the first 60 chars of the prompt', () => {
    const { mgr } = setup({ FAKE_CLAUDE_HANG: '1' });
    const info = mgr.create('p1', 'a'.repeat(100));
    expect(info.title).toBe('a'.repeat(60));
    mgr.stop(info.id);
  });

  it('spawn failure on create and followUp marks failed, not stuck running', async () => {
    let boom = true;
    const { mgr, events } = setup({ FAKE_CLAUDE_ECHO: '1' }, () => {
      if (boom) throw new Error('nope');
      return '{}';
    });
    const info = mgr.create('p1', 'x');
    expect(info.status).toBe('failed');
    expect(mgr.get(info.id)!.status).toBe('failed');
    const f = events.find((e) => e.type === 'status' && e.status === 'failed') as any;
    expect(f.reason).toBe('Could not start claude: nope');
    boom = true;
    mgr.followUp(info.id, 'again');
    expect(mgr.get(info.id)!.status).toBe('failed');
    expect(events.filter(isStatus('failed'))).toHaveLength(2);
    expect(() => mgr.followUp(info.id, 'again')).not.toThrow(Busy);
  });

  it('stop after result but before exit keeps done', async () => {
    const { mgr, events, waitFor } = setup({ FAKE_CLAUDE_FIXTURE: fixture('simple-text.ndjson') });
    const info = mgr.create('p1', 'x');
    await waitFor(isStatus('done'));
    mgr.stop(info.id);
    await new Promise((r) => setTimeout(r, 200));
    expect(mgr.get(info.id)!.status).toBe('done');
    expect(events.some(isStatus('stopped'))).toBe(false);
  });

  it('failed-auth fixture with exit 1 stays failed with the sign-in reason', async () => {
    const { mgr, events, waitFor } = setup({ FAKE_CLAUDE_FIXTURE: fixture('failed-auth.ndjson'), FAKE_CLAUDE_EXIT_CODE: '1' });
    const info = mgr.create('p1', 'x');
    const e = (await waitFor(isStatus('failed'))) as any;
    expect(e.reason).toContain('Not signed in');
    await new Promise((r) => setTimeout(r, 300));
    expect(events.filter(isStatus('failed'))).toHaveLength(1);
    expect(mgr.get(info.id)!.status).toBe('failed');
  });

  it("an old run's exit does not cancel approvals of a newer run", async () => {
    const { mgr, broker, waitFor } = setup({ FAKE_CLAUDE_ECHO: '1', FAKE_CLAUDE_LINGER_MS: '400', FAKE_CLAUDE_HANG_ON_PROMPT: 'hang' });
    const info = mgr.create('p1', 'one');
    await waitFor(isStatus('done')); // first process is still alive (lingering)
    mgr.followUp(info.id, 'hang');
    const p = broker.request(info.id, 'Bash', { command: 'ls' });
    let settled = false;
    void p.then(() => (settled = true));
    await new Promise((r) => setTimeout(r, 900)); // first process has exited by now
    expect(settled).toBe(false);
    expect(broker.pending()).toHaveLength(1);
    mgr.stop(info.id);
    expect(await p).toBe('deny');
  });

  it('failure reasons have the project path and home dir replaced', async () => {
    const cwd = process.cwd();
    const home = homedir();
    const fwd = (s: string) => s.replace(/\\/g, '/');
    const stderr = `boom in ${cwd}\\src\\a.ts and ${fwd(cwd)}/b.ts; cfg ${home}\\.claude\\x and ${fwd(home)}/.config`;
    const { mgr, waitFor } = setup({ FAKE_CLAUDE_STDERR: stderr, FAKE_CLAUDE_EXIT_CODE: '2' });
    mgr.create('p1', 'x');
    const e = (await waitFor(isStatus('failed'))) as Extract<WatchEvent, { type: 'status' }>;
    expect(e.reason).toContain('<project>\\src\\a.ts');
    expect(e.reason).toContain('<project>/b.ts');
    expect(e.reason).toContain('~\\.claude\\x');
    expect(e.reason).toContain('~/.config');
    expect(e.reason!.toLowerCase()).not.toContain(home.toLowerCase());
    expect(e.reason!.toLowerCase()).not.toContain(fwd(home).toLowerCase());
  });

  it('processes a final line without trailing newline', async () => {
    const { mgr, waitFor } = setup({ FAKE_CLAUDE_FIXTURE: fixture('simple-text.ndjson'), FAKE_CLAUDE_NO_TRAILING_NEWLINE: '1' });
    mgr.create('p1', 'x');
    await waitFor(isStatus('done'));
    expect(mgr.list()[0].summary).toBe('Hi there, how are you?');
  });
});
