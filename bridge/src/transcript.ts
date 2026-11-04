import { describeTool } from './normalize.js';
import { RELAY_SENDER_PREFIX, WATCH_INSTRUCTIONS, WATCH_PREFIX } from './relayText.js';

export type ChatItem = { kind: 'prompt' | 'reply' | 'step'; text: string; ts: number };

export type ParsedTranscript = {
  items: ChatItem[];
  title?: string;
  firstPrompt?: string;
  cwd?: string;
  /** True when the last main-chain assistant line ended the turn and no user prompt followed it. */
  turnEnded: boolean;
  /** stop_reason of the last main-chain assistant line; undefined when there was none. */
  lastStop?: string | null;
  lastTs?: number;
};

function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

function cleanPrompt(raw: string): string {
  return raw
    .replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g, '')
    .replace(/<pasted_content\b[^>]*>[\s\S]*?<\/pasted_content>/g, '[pasted]')
    .replace(/<command-message>[\s\S]*?<\/command-message>/g, '')
    .replace(/<command-args>[\s\S]*?<\/command-args>/g, '')
    .replace(/<\/?command-name>/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

const CROSS_RE = /<cross-session-message\b([^>]*)>([\s\S]*?)<\/cross-session-message>/;

/**
 * A message delivered by cross-session messaging arrives as
 * `Another Claude session sent a message: <cross-session-message from-name="…">…</cross-session-message> <boilerplate>`.
 * Shows it as `You (watch): …` (our own relay, by sender name and prefix) or `Message from <name>: …`, dropping the wrapper and boilerplate.
 * Returns undefined for ordinary prompts (no tag, or the tag in the middle of other text).
 */
function unwrapCrossSession(raw: string): string | undefined {
  const m = CROSS_RE.exec(raw);
  if (!m) return undefined;
  const before = raw.slice(0, m.index).replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g, '').trim();
  if (before !== '' && !(before.length <= 200 && /sent a message:$/i.test(before))) return undefined;
  const inner = m[2]!.trim();
  const name = /\bfrom-name="([^"]*)"/.exec(m[1]!)?.[1]?.trim() || 'another session';
  // Only our own relay job (sender `wearclaude-relay…`) may be shown as the user's watch dictation.
  if (name.startsWith(RELAY_SENDER_PREFIX) && inner.startsWith(WATCH_PREFIX)) {
    let body = inner.slice(WATCH_PREFIX.length);
    const i = body.lastIndexOf(WATCH_INSTRUCTIONS);
    if (i >= 0) body = body.slice(0, i);
    return `You (watch): ${body.trim()}`;
  }
  return `Message from ${name}: ${inner}`;
}

export function parseTranscriptLines(lines: string[]): ParsedTranscript {
  const items: ChatItem[] = [];
  let title: string | undefined;
  let firstPrompt: string | undefined;
  let cwd: string | undefined;
  let lastTs: number | undefined;
  let lastStop: unknown;
  let sawAssistant = false;
  let promptAfterAssistant = false;

  for (const line of lines) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    let o: any;
    try {
      o = JSON.parse(line);
    } catch {
      continue;
    }
    if (!o || typeof o !== 'object') continue;
    const parsed = typeof o.timestamp === 'string' ? Date.parse(o.timestamp) : NaN;
    const ts = Number.isNaN(parsed) ? 0 : parsed;

    if (o.type === 'custom-title') {
      if (typeof o.customTitle === 'string' && o.customTitle) title = o.customTitle;
      continue;
    }
    if (o.type !== 'user' && o.type !== 'assistant') continue;
    if (!cwd && typeof o.cwd === 'string' && o.cwd) cwd = o.cwd;
    if (ts && (lastTs === undefined || ts > lastTs)) lastTs = ts;
    const content = o.message?.content;

    if (o.type === 'user') {
      if (o.isSidechain) continue;
      let text: string;
      if (typeof content === 'string') text = content;
      else if (Array.isArray(content)) {
        text = content
          .filter((b: { type?: string }) => b?.type === 'text' && typeof (b as { text?: unknown }).text === 'string')
          .map((b: { text: string }) => b.text)
          .join(' ');
      } else continue;
      const raw = text.slice(0, 20_000);
      const peer = unwrapCrossSession(raw);
      // Cross-session deliveries are written as isMeta lines; other meta lines stay hidden.
      if (o.isMeta && peer === undefined) continue;
      const cleaned = cleanPrompt(peer ?? raw);
      if (!cleaned) continue;
      const t = truncate(cleaned, 500);
      if (firstPrompt === undefined) firstPrompt = t;
      items.push({ kind: 'prompt', text: t, ts });
      promptAfterAssistant = true;
    } else {
      if (o.isSidechain) continue;
      sawAssistant = true;
      promptAfterAssistant = false;
      lastStop = o.message?.stop_reason;
      if (!Array.isArray(content)) continue;
      for (const b of content) {
        if (b?.type === 'text' && typeof b.text === 'string') {
          const t = b.text.replace(/\s+/g, ' ').trim();
          if (t) items.push({ kind: 'reply', text: truncate(t, 500), ts });
        } else if (b?.type === 'tool_use' && typeof b.name === 'string') {
          items.push({ kind: 'step', text: truncate(describeTool(b.name, b.input ?? {}), 500), ts });
        }
      }
    }
  }

  return {
    items,
    title,
    firstPrompt,
    cwd,
    turnEnded: sawAssistant && lastStop === 'end_turn' && !promptAfterAssistant,
    lastStop: sawAssistant ? (typeof lastStop === 'string' ? lastStop : null) : undefined,
    lastTs,
  };
}

export function chatTitle(customTitle?: string, firstPrompt?: string): string {
  if (customTitle) return truncate(customTitle, 60);
  if (firstPrompt) return truncate(firstPrompt, 60);
  return 'Untitled chat';
}
