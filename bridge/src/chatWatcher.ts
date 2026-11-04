import { closeSync, openSync, readSync, statSync } from 'node:fs';
import type { ChatIndex } from './chatIndex.js';
import type { EventBus } from './events.js';
import { parseTranscriptLines } from './transcript.js';

type Opts = {
  index: ChatIndex;
  bus: EventBus;
  intervalMs?: number;
  now?: () => number;
  /** No-growth timeout before a working chat is reported idle (not ended). */
  idleMs?: number;
  /** Longer no-growth timeout used while the last assistant line stopped for a tool (tool running or permission prompt). */
  toolIdleMs?: number;
  /** Creation time of a transcript (ms). Injectable for tests. */
  birthOf?: (path: string) => number;
  /** Opens a transcript for reading. Injectable for tests. */
  openFile?: (path: string) => number;
};

const NEW_FILE_SLACK_MS = 5000;

function defaultBirth(path: string): number {
  const st = statSync(path);
  return st.birthtimeMs > 0 ? st.birthtimeMs : st.ctimeMs;
}

type Status = 'working' | 'idle';
type Tracked = {
  offset: number;
  partial: Buffer;
  status: Status;
  lastGrowth: number;
  skipToNewline: boolean;
  /** stop_reason of the last parsed main-chain assistant line. */
  lastStop?: string | null;
};
type ChatRef = { id: string; title: string; projectName: string; status: Status; archived: boolean };

const MAX_READ = 8 * 1024 * 1024;
const CATCH_UP = 1024 * 1024;

/** Tails visible PC chat transcripts and publishes appended content on the event bus. */
export class ChatWatcher {
  private readonly index: ChatIndex;
  private readonly bus: EventBus;
  private readonly intervalMs: number;
  private readonly now: () => number;
  private readonly idleMs: number;
  private readonly toolIdleMs: number;
  private readonly files = new Map<string, Tracked>();
  /** Last title/archived pushed (or first seen) per chat, to detect changes while idle. */
  private readonly sent = new Map<string, { title: string; archived: boolean }>();
  private timer: NodeJS.Timeout | undefined;
  private initialized = false;
  private startedAt = 0;
  private readonly birthOf: (path: string) => number;
  private readonly openFile: (path: string) => number;

  constructor(o: Opts) {
    this.index = o.index;
    this.bus = o.bus;
    this.intervalMs = o.intervalMs ?? 2000;
    this.now = o.now ?? Date.now;
    this.idleMs = o.idleMs ?? 60_000;
    this.toolIdleMs = o.toolIdleMs ?? 10 * 60_000;
    this.birthOf = o.birthOf ?? defaultBirth;
    this.openFile = o.openFile ?? ((p) => openSync(p, 'r'));
  }

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => {
      try {
        this.tick();
      } catch {
        // never let a tick crash the bridge
      }
    }, this.intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
  }

  tick(): void {
    if (!this.initialized) this.startedAt = this.now();
    const chats = this.index.listWithFiles();
    const live = new Set<string>();
    for (const { chat, file, size } of chats) {
      live.add(chat.id);
      try {
        this.tickOne(chat, file, size);
      } catch {
        // skip this file for this tick
      }
      this.syncMeta(chat);
    }
    for (const id of [...this.files.keys()]) if (!live.has(id)) this.files.delete(id);
    for (const id of [...this.sent.keys()]) if (!live.has(id)) this.sent.delete(id);
    this.initialized = true;
  }

  /** Pushes a chat_status when the title or archived flag changed since last sent (not on first sight). */
  private syncMeta(chat: ChatRef): void {
    const prev = this.sent.get(chat.id);
    if (prev && (prev.title !== chat.title || prev.archived !== chat.archived)) {
      const t = this.files.get(chat.id);
      this.bus.emit({
        type: 'chat_status', agentId: `pc:${chat.id}`, status: t?.status ?? chat.status, ended: false,
        title: chat.title, projectName: chat.projectName, archived: chat.archived,
      });
    }
    this.sent.set(chat.id, { title: chat.title, archived: chat.archived });
  }

  /** `size` comes from the index's directory scan; the file is opened only when it differs from what was read. */
  private tickOne(chat: ChatRef, path: string, size: number): void {
    const agentId = `pc:${chat.id}`;
    const status = (s: Status, ended = false) =>
      this.bus.emit({ type: 'chat_status', agentId, status: s, ended, title: chat.title, projectName: chat.projectName, archived: chat.archived });
    let t = this.files.get(chat.id);
    if (!t) {
      let genuinelyNew = false;
      if (this.initialized) {
        try {
          genuinelyNew = this.birthOf(path) >= this.startedAt - NEW_FILE_SLACK_MS;
        } catch {
          genuinelyNew = false;
        }
      }
      if (!genuinelyNew) {
        // Present at startup, or re-entered the list later: no backlog. If the last line is still being written, skip its remainder.
        let open = false;
        if (size > 0) {
          const fd = this.openFile(path);
          try {
            const last = Buffer.alloc(1);
            const n = readSync(fd, last, 0, 1, size - 1);
            open = n === 1 && last[0] !== 0x0a;
          } finally {
            closeSync(fd);
          }
        }
        this.files.set(chat.id, { offset: size, partial: Buffer.alloc(0), status: chat.status, lastGrowth: this.now(), skipToNewline: open });
        return;
      }
      // Brand-new chat: read from the start so its first prompt and status are emitted.
      t = { offset: 0, partial: Buffer.alloc(0), status: 'idle', lastGrowth: this.now(), skipToNewline: false };
      this.files.set(chat.id, t);
    }
    if (size < t.offset) {
      t.offset = size;
      t.partial = Buffer.alloc(0);
      t.skipToNewline = false;
      return;
    }
    if (size === t.offset) {
      const timeout = t.lastStop === 'tool_use' ? this.toolIdleMs : this.idleMs;
      if (t.status === 'working' && this.now() - t.lastGrowth >= timeout) {
        t.status = 'idle';
        status('idle', false);
      }
      return;
    }
    const fd = this.openFile(path);
    try {
      this.readGrowth(t, fd, size, status, agentId);
    } finally {
      closeSync(fd);
    }
  }

  private readGrowth(t: Tracked, fd: number, size: number, status: (s: Status, ended?: boolean) => void, agentId: string): void {
    const huge = size - t.offset > MAX_READ;
    let start = t.offset;
    let discardFirst = t.skipToNewline;
    if (huge) {
      start = size - CATCH_UP;
      discardFirst = true;
      t.partial = Buffer.alloc(0);
    }
    const buf = Buffer.alloc(size - start);
    const n = readSync(fd, buf, 0, buf.length, start);
    t.offset = start + n;
    t.lastGrowth = this.now();
    let data = Buffer.concat([t.partial, buf.subarray(0, n)]);
    if (discardFirst) {
      const i = data.indexOf(0x0a);
      t.skipToNewline = i === -1 && !huge;
      data = i === -1 ? Buffer.alloc(0) : data.subarray(i + 1);
    }
    if (huge && t.status === 'idle') {
      t.status = 'working';
      status('working');
    }
    const nl = data.lastIndexOf(0x0a);
    if (nl === -1) {
      t.partial = Buffer.from(data);
      return;
    }
    t.partial = Buffer.from(data.subarray(nl + 1));
    const parsed = parseTranscriptLines(data.subarray(0, nl).toString('utf8').split('\n'));
    if (parsed.lastStop !== undefined) t.lastStop = parsed.lastStop;
    if (parsed.items.length > 0 && t.status === 'idle') {
      t.status = 'working';
      status('working');
    }
    for (const it of parsed.items) this.bus.emit({ type: 'chat_item', agentId, kind: it.kind, text: it.text });
    if (parsed.turnEnded && t.status === 'working') {
      t.status = 'idle';
      status('idle', true);
    }
  }
}
