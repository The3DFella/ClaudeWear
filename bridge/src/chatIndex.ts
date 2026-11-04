import { closeSync, openSync, readFileSync, readSync, readdirSync, statSync, lstatSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { DesktopLookup, DesktopMeta } from './desktopSessions.js';
import { chatTitle, parseTranscriptLines, type ChatItem } from './transcript.js';

export type ChatInfo = {
  id: string;
  title: string;
  projectName: string;
  lastActivity: number;
  status: 'working' | 'idle';
  /** True when the title is a custom title (transcript or custom-title.json), not the first prompt. */
  titled: boolean;
  /** True when the Claude desktop app has this chat archived. */
  archived: boolean;
};

type Opts = {
  rootDir: string;
  excludeSessionIds: () => Set<string>;
  maxAgeDays: number;
  /** Claude desktop app per-chat metadata (sidebar title, archived). Optional. */
  desktop?: DesktopLookup;
  now?: () => number;
  tmpDir?: string;
  /** Reads `len` bytes at `pos` as utf8. Injectable for tests. */
  readRange?: (path: string, pos: number, len: number) => string;
};

const HEAD = 64 * 1024;
const TAIL = 512 * 1024;
/** Same window as ChatWatcher's default idle timeout, so list and live status agree. */
const WORKING_MS = 60_000;
const MAX_ITEMS = 60;
const MAX_ACTIVE = 100;
const MAX_ARCHIVED = 50;
const MAX_TITLE_FILE = 8 * 1024;
const ID_RE = /^[0-9a-f-]{36}$/;
const FILE_RE = /^([0-9a-f-]{36})\.jsonl$/;

type Entry = { id: string; path: string; dir: string; mtimeMs: number; size: number; activity: number };
type Meta = Map<string, DesktopMeta>;
type Parsed = { cwd?: string; title?: string; customTitle?: string; items: ChatItem[]; turnEnded: boolean };

function defaultRead(path: string, pos: number, len: number): string {
  const fd = openSync(path, 'r');
  try {
    const buf = Buffer.alloc(len);
    const n = readSync(fd, buf, 0, len, pos);
    return buf.subarray(0, n).toString('utf8');
  } finally {
    closeSync(fd);
  }
}

const norm = (p: string) => {
  const s = p.replace(/\\/g, '/').replace(/\/+$/, '');
  return process.platform === 'win32' ? s.toLowerCase() : s;
};

export class ChatIndex {
  private readonly o: Opts;
  private readonly read: (path: string, pos: number, len: number) => string;
  /** Number of directory scans performed (for tests/diagnostics). */
  scanCount = 0;
  private readonly cache = new Map<string, { mtimeMs: number; size: number; parsed: Parsed; headTitle?: string }>();

  constructor(o: Opts) {
    this.o = o;
    this.read = o.readRange ?? defaultRead;
  }

  private now(): number {
    return (this.o.now ?? Date.now)();
  }

  private scan(): Entry[] {
    this.scanCount++;
    const out: Entry[] = [];
    let dirs: string[];
    try {
      dirs = readdirSync(this.o.rootDir, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name);
    } catch {
      return out;
    }
    for (const dir of dirs) {
      let files: string[];
      try {
        files = readdirSync(join(this.o.rootDir, dir), { withFileTypes: true }).filter((f) => f.isFile()).map((f) => f.name);
      } catch {
        continue;
      }
      for (const name of files) {
        const m = FILE_RE.exec(name);
        if (!m) continue;
        const path = join(this.o.rootDir, dir, name);
        try {
          const st = statSync(path);
          out.push({ id: m[1]!, path, dir, mtimeMs: st.mtimeMs, size: st.size, activity: st.mtimeMs });
        } catch {
          // vanished
        }
      }
    }
    return out;
  }

  private parse(e: Entry): Parsed {
    const hit = this.cache.get(e.path);
    if (hit && hit.mtimeMs === e.mtimeMs && hit.size === e.size) return hit.parsed;
    let parsed: Parsed;
    let cacheable = true;
    try {
      if (e.size <= HEAD + TAIL) {
        const lines = this.read(e.path, 0, e.size).split('\n');
        const p = parseTranscriptLines(lines);
        parsed = { cwd: p.cwd, items: p.items.slice(-MAX_ITEMS), turnEnded: p.turnEnded, ...this.titleOf(e, p.title, p.firstPrompt) };
      } else {
        const headText = this.read(e.path, 0, HEAD);
        const headLines = headText.split('\n');
        headLines.pop(); // possibly partial last line
        const head = parseTranscriptLines(headLines);
        const tailLines = this.read(e.path, e.size - TAIL, TAIL).split('\n');
        tailLines.shift(); // possibly partial first line
        const tail = parseTranscriptLines(tailLines);
        parsed = {
          cwd: head.cwd ?? tail.cwd,
          items: tail.items.slice(-MAX_ITEMS),
          turnEnded: tail.turnEnded,
          ...this.titleOf(e, tail.title, head.firstPrompt),
        };
      }
    } catch (err) {
      parsed = { items: [], turnEnded: false, title: 'Untitled chat' };
      // A transient failure (EBUSY, EPERM, ...) must be retried; only a vanished file is final.
      if ((err as NodeJS.ErrnoException | null)?.code !== 'ENOENT') cacheable = false;
    }
    if (cacheable) this.cache.set(e.path, { mtimeMs: e.mtimeMs, size: e.size, parsed });
    return parsed;
  }

  /** Display title, plus the full custom title when there is one. */
  private titleOf(e: Entry, custom: string | undefined, firstPrompt: string | undefined): { title: string; customTitle?: string } {
    if (custom) return { title: chatTitle(custom), customTitle: custom };
    const fallback = { title: chatTitle(undefined, firstPrompt) };
    try {
      const tf = join(this.o.rootDir, e.dir, e.id, 'custom-title.json');
      const st = lstatSync(tf);
      if (!st.isFile() || st.size > MAX_TITLE_FILE) return fallback;
      const raw = JSON.parse(readFileSync(tf, 'utf8')) as unknown;
      if (raw && typeof raw === 'object') {
        const r = raw as { customTitle?: unknown; title?: unknown };
        const t = typeof r.customTitle === 'string' && r.customTitle ? r.customTitle : typeof r.title === 'string' ? r.title : '';
        if (t.trim()) return { title: chatTitle(t.trim()), customTitle: t.trim() };
      }
    } catch {
      // absent or malformed
    }
    return fallback;
  }

  private projectName(cwd: string | undefined, dir: string): string {
    if (cwd) {
      const seg = cwd.replace(/[\/\\]+$/, '').split(/[\/\\]/).pop();
      if (seg) return seg;
    }
    const seg = dir.split('-').pop();
    return seg || 'Unknown';
  }

  private meta(): Meta {
    try {
      return this.o.desktop?.lookup() ?? new Map();
    } catch {
      return new Map();
    }
  }

  private withActivity(e: Entry, meta: Meta): Entry {
    const la = meta.get(e.id)?.lastActivityAt;
    if (la === undefined) return e;
    const eff = Math.min(la, this.now());
    return eff > e.mtimeMs ? { ...e, activity: eff } : e;
  }

  private info(e: Entry, p: Parsed, meta: Meta): ChatInfo {
    const working = this.now() - e.mtimeMs < WORKING_MS && !p.turnEnded;
    const d = meta.get(e.id);
    const dt = d?.title;
    return {
      id: e.id,
      title: dt ? chatTitle(dt) : (p.title ?? 'Untitled chat'),
      projectName: this.projectName(p.cwd, e.dir),
      lastActivity: Math.round(Math.max(e.mtimeMs, Math.min(d?.lastActivityAt ?? 0, this.now()))),
      status: working ? 'working' : 'idle',
      titled: dt ? true : p.customTitle !== undefined,
      archived: d?.archived === true,
    };
  }

  private visible(e: Entry, excluded: Set<string>): Parsed | undefined {
    if (excluded.has(e.id)) return undefined;
    if (this.now() - e.activity > this.o.maxAgeDays * 86_400_000) return undefined;
    const p = this.parse(e);
    if (p.cwd) {
      const t = norm(this.o.tmpDir ?? tmpdir());
      const c = norm(p.cwd);
      if (c === t || c.startsWith(t + '/')) return undefined;
    }
    return p;
  }

  list(): ChatInfo[] {
    return this.listWithFiles().map((x) => x.chat);
  }

  /** Same as list() but with each transcript's path, size and mtime, from a single directory scan. */
  listWithFiles(): { chat: ChatInfo; file: string; size: number; mtimeMs: number }[] {
    const excluded = this.o.excludeSessionIds();
    const out: { chat: ChatInfo; file: string; size: number; mtimeMs: number }[] = [];
    const seen = new Set<string>();
    const meta = this.meta();
    const entries = this.scan().map((e) => this.withActivity(e, meta)).sort((x, y) => y.activity - x.activity);
    for (const e of entries) seen.add(e.path);
    let active = 0;
    let archived = 0;
    for (const e of entries) {
      if (active >= MAX_ACTIVE && archived >= MAX_ARCHIVED) break;
      const arch = meta.get(e.id)?.archived === true;
      if (arch ? archived >= MAX_ARCHIVED : active >= MAX_ACTIVE) continue;
      const p = this.visible(e, excluded);
      if (!p) continue;
      if (arch) archived++;
      else active++;
      out.push({ chat: this.info(e, p, meta), file: e.path, size: e.size, mtimeMs: e.mtimeMs });
    }
    for (const k of this.cache.keys()) if (!seen.has(k)) this.cache.delete(k);
    return out.sort((a, b) => b.chat.lastActivity - a.chat.lastActivity);
  }

  private find(id: string): Entry | undefined {
    if (!ID_RE.test(id)) return undefined;
    return this.scan().find((e) => e.id === id);
  }

  file(id: string): string | undefined {
    return this.find(id)?.path;
  }

  /** A visible chat plus its full (untruncated) custom title, if any, from one scan; undefined when unknown or hidden. */
  relayTarget(id: string): { chat: ChatInfo; relayTitle?: string } | undefined {
    const meta = this.meta();
    const found = this.find(id);
    if (!found) return undefined;
    const e = this.withActivity(found, meta);
    const p = this.visible(e, this.o.excludeSessionIds());
    if (!p) return undefined;
    return { chat: this.info(e, p, meta), relayTitle: meta.get(id)?.title ?? p.customTitle };
  }

  get(id: string): { chat: ChatInfo; items: ChatItem[] } | undefined {
    const meta = this.meta();
    const found = this.find(id);
    if (!found) return undefined;
    const e = this.withActivity(found, meta);
    const p = this.visible(e, this.o.excludeSessionIds());
    if (!p) return undefined;
    return { chat: this.info(e, p, meta), items: p.items.slice(-MAX_ITEMS) };
  }
}
