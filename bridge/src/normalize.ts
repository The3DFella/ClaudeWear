import type { WatchEventInput } from './types.js';

const NOT_SIGNED_IN = 'Not signed in — run claude, then /login, on your PC';

function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

function basename(p: unknown): string {
  if (typeof p !== 'string' || !p) return '';
  const parts = p.split(/[\\/]/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : p;
}

export function describeTool(name: string, input: Record<string, unknown>): string {
  const inp = input ?? {};
  switch (name) {
    case 'Bash':
    case 'PowerShell':
      return `Running \`${String(inp.command ?? '').slice(0, 60)}\``;
    case 'Edit':
    case 'Write':
    case 'MultiEdit':
    case 'NotebookEdit': {
      const b = basename(inp.file_path ?? inp.notebook_path);
      return b ? `Editing ${b}` : 'Editing';
    }
    case 'Read': {
      const b = basename(inp.file_path);
      return b ? `Reading ${b}` : 'Reading';
    }
    case 'Grep':
    case 'Glob':
      return 'Searching';
    case 'WebFetch':
    case 'WebSearch':
      return 'Browsing the web';
    case 'Agent':
    case 'Task':
      return 'Started subagent';
    default:
      return name.startsWith('mcp__') ? `Using ${name}` : name;
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function friendlyError(result: any): string {
  const text = typeof result?.result === 'string' ? result.result : '';
  const err = typeof result?.error === 'string' ? result.error : '';
  const hay = `${text}\n${err}`.toLowerCase();
  if (['not logged in', 'login', 'authentication', 'unauthorized', '401', 'invalid api key'].some((k) => hay.includes(k))) {
    return NOT_SIGNED_IN;
  }
  if (['usage limit', 'rate limit', 'limit reached', '429'].some((k) => hay.includes(k))) {
    return 'Usage limit reached';
  }
  return text.trim() ? text.slice(0, 200) : 'claude reported an error';
}

export function normalizeLine(line: string, agentId: string): WatchEventInput[] {
  try {
    const trimmed = line.replace(/\r$/, '').trim();
    if (!trimmed.startsWith('{')) return [];
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const o: any = JSON.parse(trimmed);
    if (!o || typeof o !== 'object') return [];

    if (o.type === 'system') {
      if (o.subtype === 'api_retry') {
        const e = typeof o.error === 'string' && o.error ? o.error : '';
        return [{ type: 'step', text: e ? `Retrying (${e})…` : 'Retrying…', depth: 0, agentId }];
      }
      return [];
    }

    if (o.type === 'assistant') {
      const depth = o.parent_tool_use_id ? 1 : 0;
      const content = o.message?.content;
      if (!Array.isArray(content)) return [];
      const out: WatchEventInput[] = [];
      for (const b of content) {
        if (b?.type === 'text' && typeof b.text === 'string' && b.text.trim()) {
          out.push({ type: 'message', text: truncate(b.text, 500), agentId });
        } else if (b?.type === 'tool_use' && typeof b.name === 'string') {
          const input = b.input && typeof b.input === 'object' ? b.input : {};
          out.push({ type: 'step', text: describeTool(b.name, input), depth, agentId });
        }
      }
      return out;
    }

    if (o.type === 'result') {
      if (o.is_error === true || o.subtype !== 'success') {
        return [{ type: 'status', status: 'failed', reason: friendlyError(o), agentId }];
      }
      const text = truncate(typeof o.result === 'string' ? o.result : '', 1500);
      const summary: WatchEventInput = { type: 'summary', text, agentId };
      if (typeof o.total_cost_usd === 'number') summary.costUsd = o.total_cost_usd;
      return [summary, { type: 'status', status: 'done', agentId }];
    }
    return [];
  } catch {
    return [];
  }
}
