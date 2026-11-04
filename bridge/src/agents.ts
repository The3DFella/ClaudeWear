import { spawn, type ChildProcess } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import type { Config } from './config.js';
import { homeDir } from './config.js';
import type { EventBus } from './events.js';
import type { ApprovalBroker } from './approvals.js';
import { normalizeLine } from './normalize.js';
import { buildClaudeArgs } from './claudeArgs.js';
import { killTree } from './proc.js';
import type { AgentInfo, WatchEventInput } from './types.js';

export class NotFound extends Error {
  constructor(msg = 'agent not found') {
    super(msg);
    this.name = 'NotFound';
  }
}
export class Busy extends Error {
  constructor(msg = 'agent is busy') {
    super(msg);
    this.name = 'Busy';
  }
}
export class UnknownProject extends Error {
  constructor(msg = 'unknown project') {
    super(msg);
    this.name = 'UnknownProject';
  }
}

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Replaces the project path with `<project>` and the home dir with `~` (both slash styles, and
 * JSON-escaped backslashes), so failure reasons sent to the watch carry no filesystem paths.
 */
export function scrubPaths(text: string, projectPath: string, home: string): string {
  const flags = process.platform === 'win32' ? 'gi' : 'g';
  const variants = (p: string): string[] => {
    const trimmed = p.replace(/[\\/]+$/, '');
    if (trimmed.length < 2) return [];
    const back = trimmed.replace(/\//g, '\\');
    const fwd = trimmed.replace(/\\/g, '/');
    // Longest first so the JSON-escaped form is replaced before its single-backslash prefix could match.
    return [...new Set([back.replace(/\\/g, '\\\\'), back, fwd])].sort((a, b) => b.length - a.length);
  };
  let out = text;
  for (const [path, repl] of [[projectPath, '<project>'], [home, '~']] as const) {
    for (const v of variants(path)) out = out.replace(new RegExp(escapeRe(v), flags), repl);
  }
  return out;
}

type Run = { child: ChildProcess; stopping: boolean; sawTerminal: boolean; stderrTail: string };

export type AgentManagerOptions = {
  config: Config;
  bus: EventBus;
  broker: ApprovalBroker;
  mcpConfigFor: (id: string) => string;
  claudeCmd: { bin: string; preArgs: string[] };
  env?: NodeJS.ProcessEnv;
  /** Home dir scrubbed from failure reasons (defaults to os.homedir()). */
  homeDir?: string;
};

export class AgentManager {
  private readonly agents = new Map<string, AgentInfo>();
  private readonly runs = new Map<string, Run>();

  constructor(private readonly o: AgentManagerOptions) {}

  create(projectId: string, prompt: string): AgentInfo {
    const project = this.o.config.projects.find((p) => p.id === projectId);
    if (!project) throw new UnknownProject(`unknown project: ${projectId}`);
    const now = Date.now();
    const oneLine = prompt.replace(/\s+/g, ' ').trim();
    const info: AgentInfo = {
      id: randomUUID(),
      title: oneLine.slice(0, 60) || 'Untitled',
      projectId,
      status: 'running',
      createdAt: now,
      updatedAt: now,
      sessionId: randomUUID(),
    };
    this.agents.set(info.id, info);
    this.emit(info.id, { type: 'agent_created', agentId: info.id, title: info.title, projectId });
    this.emit(info.id, { type: 'status', agentId: info.id, status: 'running' });
    this.persist();
    this.spawnTurn(info, prompt, false);
    return { ...info };
  }

  followUp(id: string, prompt: string): void {
    const info = this.agents.get(id);
    if (!info) throw new NotFound(`agent not found: ${id}`);
    if (!['done', 'failed', 'stopped', 'interrupted'].includes(info.status)) throw new Busy();
    this.emit(id, { type: 'status', agentId: id, status: 'running' });
    this.persist();
    this.spawnTurn(info, prompt, true);
  }

  stop(id: string): void {
    const info = this.agents.get(id);
    if (!info) throw new NotFound(`agent not found: ${id}`);
    const run = this.runs.get(id);
    if (!run || run.stopping) return;
    if (run.sawTerminal) {
      // Result already arrived; only reap the process, keep done/failed.
      this.kill(run.child);
      return;
    }
    run.stopping = true;
    this.o.broker.cancelForAgent(id);
    this.kill(run.child);
    this.emit(id, { type: 'status', agentId: id, status: 'stopped' });
    this.persist();
  }

  list(): AgentInfo[] {
    return [...this.agents.values()].map((a) => ({ ...a }));
  }

  get(id: string): AgentInfo | undefined {
    const a = this.agents.get(id);
    return a ? { ...a } : undefined;
  }

  setWaiting(id: string, waiting: boolean): void {
    const info = this.agents.get(id);
    const run = this.runs.get(id);
    if (!info || !run || run.stopping) return;
    if (waiting && info.status === 'running') {
      this.emit(id, { type: 'status', agentId: id, status: 'waiting_approval' });
      this.persist();
    } else if (!waiting && info.status === 'waiting_approval') {
      this.emit(id, { type: 'status', agentId: id, status: 'running' });
      this.persist();
    }
  }

  loadOnStartup(): void {
    const file = join(homeDir(), 'agents.json');
    if (!existsSync(file)) return;
    let arr: AgentInfo[];
    try {
      const parsed = JSON.parse(readFileSync(file, 'utf8'));
      if (!Array.isArray(parsed)) return;
      arr = parsed;
    } catch {
      return;
    }
    let changed = false;
    for (const a of arr) {
      if (a.status === 'running' || a.status === 'waiting_approval') {
        a.status = 'interrupted';
        changed = true;
      }
      this.agents.set(a.id, a);
    }
    if (changed) this.persist();
  }

  // ---- internals ----

  private emit(id: string, e: WatchEventInput): void {
    const info = this.agents.get(id);
    if (e.type === 'status' && e.reason) {
      const project = this.o.config.projects.find((p) => p.id === info?.projectId);
      e = { ...e, reason: scrubPaths(e.reason, project?.path ?? '', this.o.homeDir ?? homedir()) };
    }
    this.o.bus.emit(e);
    if (!info) return;
    info.updatedAt = Date.now();
    if (e.type === 'step') info.lastStep = e.text;
    else if (e.type === 'summary') info.summary = e.text;
    else if (e.type === 'status') info.status = e.status;
  }

  private persist(): void {
    try {
      const dir = homeDir();
      mkdirSync(dir, { recursive: true });
      const file = join(dir, 'agents.json');
      const tmp = file + '.tmp';
      writeFileSync(tmp, JSON.stringify([...this.agents.values()], null, 2));
      renameSync(tmp, file);
    } catch {
      // Persistence is best-effort.
    }
  }

  private spawnTurn(info: AgentInfo, prompt: string, resume: boolean): void {
    try {
      this.doSpawn(info, prompt, resume);
    } catch (err) {
      const id = info.id;
      this.runs.delete(id);
      this.o.broker.cancelForAgent(id);
      this.emit(id, {
        type: 'status',
        agentId: id,
        status: 'failed',
        reason: `Could not start claude: ${err instanceof Error ? err.message : String(err)}`,
      });
      this.persist();
    }
  }

  private doSpawn(info: AgentInfo, prompt: string, resume: boolean): void {
    const id = info.id;
    const project = this.o.config.projects.find((p) => p.id === info.projectId)!;
    const args = [
      ...this.o.claudeCmd.preArgs,
      ...buildClaudeArgs({
        sessionId: info.sessionId,
        resume,
        mcpConfig: this.o.mcpConfigFor(id),
        permissionMode: this.o.config.defaultPermissionMode,
      }),
    ];
    const child = spawn(this.o.claudeCmd.bin, args, {
      cwd: project.path,
      shell: false,
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, ...this.o.env },
    });
    const run: Run = { child, stopping: false, sawTerminal: false, stderrTail: '' };
    this.runs.set(id, run);

    let buf = '';
    const handleLine = (line: string) => {
      if (run.stopping || !line.trim()) return;
      for (const ev of normalizeLine(line, id)) {
        if (ev.type === 'status' && (ev.status === 'done' || ev.status === 'failed')) run.sawTerminal = true;
        this.emit(id, ev);
        if (ev.type === 'status') this.persist();
      }
    };
    child.stdout!.setEncoding('utf8');
    child.stdout!.on('data', (chunk: string) => {
      buf += chunk;
      let nl: number;
      while ((nl = buf.indexOf('\n')) >= 0) {
        handleLine(buf.slice(0, nl));
        buf = buf.slice(nl + 1);
      }
    });
    child.stderr!.setEncoding('utf8');
    child.stderr!.on('data', (chunk: string) => {
      run.stderrTail = (run.stderrTail + chunk).slice(-500);
    });
    child.stdin!.on('error', () => {});
    child.stdin!.end(prompt, 'utf8');

    let finished = false;
    const finish = (reason: string) => {
      if (finished) return;
      finished = true;
      if (buf.trim()) handleLine(buf);
      buf = '';
      const current = this.runs.get(id);
      if (current === run) this.runs.delete(id);
      // A newer run (follow-up started while this process was still exiting) owns the agent's approvals.
      if (current === undefined || current === run) this.o.broker.cancelForAgent(id);
      if (run.stopping || run.sawTerminal) return;
      this.emit(id, { type: 'status', agentId: id, status: 'failed', reason });
      this.persist();
    };
    child.on('error', (err) => finish(`failed to start claude: ${err.message}`));
    child.on('close', (code, signal) => {
      const tail = run.stderrTail.trim();
      finish(`claude exited (${code === null && signal ? `signal ${signal}` : `code ${code}`})` + (tail && code !== 0 ? `: ${tail}` : ''));
    });
  }

  private kill(child: ChildProcess): void {
    killTree(child);
  }
}
