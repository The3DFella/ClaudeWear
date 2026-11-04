import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { normalizeLine, describeTool, friendlyError } from '../src/normalize.js';
import type { WatchEventInput } from '../src/types.js';

function run(name: string): WatchEventInput[] {
  const p = fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url));
  return readFileSync(p, 'utf8').split(/\r?\n/).flatMap((l) => normalizeLine(l, 'a'));
}
const NOT_SIGNED_IN = 'Not signed in — run claude, then /login, on your PC';

describe('normalizeLine fixtures', () => {
  it('simple-text ends with summary + status done', () => {
    const ev = run('simple-text.ndjson');
    expect(ev.at(-2)?.type).toBe('summary');
    expect(ev.at(-1)).toEqual({ type: 'status', status: 'done', agentId: 'a' });
    const s = ev.at(-2) as Extract<WatchEventInput, { type: 'summary' }>;
    expect(typeof s.costUsd).toBe('number');
    expect(ev.some((e) => e.type === 'message')).toBe(true);
  });
  it('approve-write has Editing hello.txt and a Running step', () => {
    const ev = run('approve-write.ndjson');
    expect(ev).toContainEqual({ type: 'step', text: 'Editing hello.txt', depth: 0, agentId: 'a' });
    expect(ev.some((e) => e.type === 'step' && e.text.startsWith('Running `Get-ChildItem'))).toBe(true);
  });
  it('approve-deny parses and ends done', () => {
    expect(run('approve-deny.ndjson').at(-1)).toMatchObject({ type: 'status', status: 'done' });
  });
  it('subagent has depth 1 step and depth 0 Started subagent', () => {
    const ev = run('subagent.ndjson');
    expect(ev.some((e) => e.type === 'step' && e.depth === 1)).toBe(true);
    expect(ev).toContainEqual({ type: 'step', text: 'Started subagent', depth: 0, agentId: 'a' });
  });
  it('failed-auth maps to failed with Not signed in', () => {
    const ev = run('failed-auth.ndjson');
    expect(ev.at(-1)).toEqual({ type: 'status', status: 'failed', reason: NOT_SIGNED_IN, agentId: 'a' });
    expect(ev.some((e) => e.type === 'summary')).toBe(false);
  });
});

describe('normalizeLine robustness', () => {
  it('returns [] for junk', () => {
    expect(normalizeLine('Warning: something', 'a')).toEqual([]);
    expect(normalizeLine('{"type":"brand_new"}', 'a')).toEqual([]);
    expect(normalizeLine('', 'a')).toEqual([]);
    expect(normalizeLine('   ', 'a')).toEqual([]);
    expect(normalizeLine('null', 'a')).toEqual([]);
    expect(normalizeLine('{"type":"rate_limit_event"}', 'a')).toEqual([]);
    expect(normalizeLine('{"type":"system","subtype":"init"}', 'a')).toEqual([]);
    expect(normalizeLine('{"type":"system","subtype":"task_summary","detail":"x"}', 'a')).toEqual([]);
  });
  it('tolerates trailing CR', () => {
    const l = JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: 'hi' }] } });
    expect(normalizeLine(l + '\r', 'a')).toEqual([{ type: 'message', text: 'hi', agentId: 'a' }]);
  });
  it('skips whitespace-only text and truncates to 500', () => {
    const mk = (t: string) => JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: t }] } });
    expect(normalizeLine(mk('  \n'), 'a')).toEqual([]);
    const ev = normalizeLine(mk('x'.repeat(900)), 'a');
    expect((ev[0] as { text: string }).text).toHaveLength(500);
    expect((ev[0] as { text: string }).text.endsWith('…')).toBe(true);
  });
  it('api_retry', () => {
    expect(normalizeLine('{"type":"system","subtype":"api_retry","error":"rate_limit"}', 'a')).toEqual([
      { type: 'step', text: 'Retrying (rate_limit)…', depth: 0, agentId: 'a' },
    ]);
    expect(normalizeLine('{"type":"system","subtype":"api_retry"}', 'a')).toEqual([
      { type: 'step', text: 'Retrying…', depth: 0, agentId: 'a' },
    ]);
  });
  it('result failure by subtype or is_error', () => {
    const r = normalizeLine('{"type":"result","subtype":"error_max_turns","is_error":false,"result":""}', 'a');
    expect(r).toEqual([{ type: 'status', status: 'failed', reason: 'claude reported an error', agentId: 'a' }]);
  });
  it('success result truncates to 1500', () => {
    const r = normalizeLine(JSON.stringify({ type: 'result', subtype: 'success', result: 'y'.repeat(3000) }), 'a');
    expect((r[0] as { text: string }).text).toHaveLength(1500);
    expect(r[0]).not.toHaveProperty('costUsd');
  });
});

describe('describeTool', () => {
  it('maps tools', () => {
    expect(describeTool('Bash', { command: 'npm test' })).toBe('Running `npm test`');
    expect(describeTool('PowerShell', { command: 'x'.repeat(100) })).toBe('Running `' + 'x'.repeat(60) + '`');
    expect(describeTool('Edit', { file_path: 'C:\\a\\b\\c.ts' })).toBe('Editing c.ts');
    expect(describeTool('Write', { file_path: '/a/b/d.ts' })).toBe('Editing d.ts');
    expect(describeTool('Read', { file_path: 'C:\\a\\e.md' })).toBe('Reading e.md');
    expect(describeTool('Grep', {})).toBe('Searching');
    expect(describeTool('Glob', {})).toBe('Searching');
    expect(describeTool('WebFetch', {})).toBe('Browsing the web');
    expect(describeTool('Agent', {})).toBe('Started subagent');
    expect(describeTool('Task', {})).toBe('Started subagent');
    expect(describeTool('mcp__x__y', {})).toBe('Using mcp__x__y');
    expect(describeTool('Foo', {})).toBe('Foo');
  });
});

describe('friendlyError', () => {
  it('classifies', () => {
    expect(friendlyError({ result: 'Not logged in · Please run /login' })).toBe(NOT_SIGNED_IN);
    expect(friendlyError({ result: 'x', error: 'authentication_failed' })).toBe(NOT_SIGNED_IN);
    expect(friendlyError({ result: '5-hour usage limit reached' })).toBe('Usage limit reached');
    expect(friendlyError({ result: 'boom' })).toBe('boom');
    expect(friendlyError({ result: 'z'.repeat(500) })).toHaveLength(200);
    expect(friendlyError({})).toBe('claude reported an error');
    expect(friendlyError(null)).toBe('claude reported an error');
  });
});
