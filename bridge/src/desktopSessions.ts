import { existsSync, lstatSync, openSync, closeSync, readSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

export type DesktopMeta = { title?: string; archived: boolean; lastActivityAt?: number };

export interface DesktopLookup {
  lookup(): Map<string, DesktopMeta>;
}

type Opts = { roots?: string[]; now?: () => number };

const FILE_RE = /^local_[0-9a-f-]+\.json$/;
const MAX_FILE = 256 * 1024;
const MAX_DEPTH = 3;
const RESCAN_MS = 10_000;
const MAX_TITLE = 500;

/** Directories where the Claude desktop app keeps per-chat metadata (only those that exist are used). */
export function defaultDesktopRoots(): string[] {
  const roots: string[] = [];
  const local = process.env.LOCALAPPDATA;
  if (local) {
    const data = join(local, 'Claude-Data');
    try {
      for (const d of readdirSync(data, { withFileTypes: true })) {
        if (d.isDirectory()) roots.push(join(data, d.name, 'claude-code-sessions'));
      }
    } catch {
      // absent
    }
  }
  if (process.env.APPDATA) roots.push(join(process.env.APPDATA, 'Claude', 'claude-code-sessions'));
  roots.push(join(homedir(), 'Library', 'Application Support', 'Claude', 'claude-code-sessions'));
  roots.push(join(homedir(), '.config', 'Claude', 'claude-code-sessions'));
  return roots.filter((r) => existsSync(r));
}

type Cached = { mtimeMs: number; size: number; sid?: string; meta?: DesktopMeta };

function readSmall(path: string, size: number): string {
  const fd = openSync(path, 'r');
  try {
    const buf = Buffer.alloc(size);
    const n = readSync(fd, buf, 0, size, 0);
    return buf.subarray(0, n).toString('utf8');
  } finally {
    closeSync(fd);
  }
}

/** Reads ONLY id/title/archived/last-activity from the desktop app's per-chat metadata. Read-only. */
export class DesktopSessions implements DesktopLookup {
  private readonly roots: string[] | undefined;
  private readonly now: () => number;
  private readonly cache = new Map<string, Cached>();
  private result = new Map<string, DesktopMeta>();
  private lastScan = -Infinity;

  constructor(o: Opts = {}) {
    this.roots = o.roots;
    this.now = o.now ?? Date.now;
  }

  lookup(): Map<string, DesktopMeta> {
    const t = this.now();
    if (t - this.lastScan < RESCAN_MS && t >= this.lastScan) return this.result;
    this.lastScan = t;
    const seen = new Set<string>();
    const out = new Map<string, DesktopMeta>();
    for (const root of this.roots ?? defaultDesktopRoots()) this.walk(root, 0, seen, out);
    for (const k of this.cache.keys()) if (!seen.has(k)) this.cache.delete(k);
    this.result = out;
    return out;
  }

  private walk(dir: string, depth: number, seen: Set<string>, out: Map<string, DesktopMeta>): void {
    let ents;
    try {
      ents = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of ents) {
      if (e.isSymbolicLink()) continue;
      const p = join(dir, e.name);
      if (e.isDirectory()) {
        if (depth + 1 <= MAX_DEPTH) this.walk(p, depth + 1, seen, out);
      } else if (e.isFile() && FILE_RE.test(e.name)) {
        seen.add(p);
        const c = this.load(p);
        if (!c?.sid || !c.meta) continue;
        const prev = out.get(c.sid);
        if (!prev || (c.meta.lastActivityAt ?? 0) > (prev.lastActivityAt ?? 0)) out.set(c.sid, c.meta);
      }
    }
  }

  private load(p: string): Cached | undefined {
    try {
      const st = lstatSync(p);
      if (!st.isFile() || st.size > MAX_FILE) return undefined;
      const hit = this.cache.get(p);
      if (hit && hit.mtimeMs === st.mtimeMs && hit.size === st.size) return hit;
      const c: Cached = { mtimeMs: st.mtimeMs, size: st.size };
      try {
        const raw = JSON.parse(readSmall(p, st.size)) as unknown;
        if (raw && typeof raw === 'object') {
          const r = raw as Record<string, unknown>;
          if (typeof r.cliSessionId === 'string' && /^[0-9a-f-]{36}$/.test(r.cliSessionId)) {
            c.sid = r.cliSessionId;
            const title = typeof r.title === 'string' ? r.title.trim().slice(0, MAX_TITLE) : '';
            const la = r.lastActivityAt;
            c.meta = {
              ...(title ? { title } : {}),
              archived: r.isArchived === true,
              ...(typeof la === 'number' && Number.isFinite(la) ? { lastActivityAt: la } : {}),
            };
          }
        }
      } catch {
        // malformed: cached as unusable until the file changes
      }
      this.cache.set(p, c);
      return c;
    } catch {
      return undefined;
    }
  }
}
