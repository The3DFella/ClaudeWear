import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { parseTranscriptLines, chatTitle } from '../src/transcript.js';
import { wrapWatchMessage } from '../src/relay.js';

const fixture = (n: string) =>
  readFileSync(new URL(`./fixtures/${n}`, import.meta.url), 'utf8').split('\n').filter(Boolean);

const user = (content: unknown, extra: object = {}) =>
  JSON.stringify({ type: 'user', timestamp: '2026-11-04T20:00:00.000Z', message: { role: 'user', content }, ...extra });
const asst = (block: unknown, stop: string | null) =>
  JSON.stringify({ type: 'assistant', timestamp: '2026-11-04T20:00:01.000Z', message: { id: 'm', role: 'assistant', content: [block], stop_reason: stop } });

describe('parseTranscriptLines', () => {
  const r = parseTranscriptLines(fixture('pc-chat-basic.jsonl'));
  it('parses fixture items in order', () => {
    expect(r.items.map((i) => i.kind)).toEqual(['prompt', 'step', 'reply']);
    expect(r.items[0].text).toBe('Fix [pasted] now');
    expect(r.items[0].ts).toBe(Date.parse('2026-11-04T20:27:51.000Z'));
    expect(r.items[1].text).toBe('Reading a.ts');
    expect(r.items[2].text).toBe('Done.');
  });
  it('extracts title, firstPrompt, cwd, turnEnded, lastTs', () => {
    expect(r.title).toBe('New name');
    expect(r.firstPrompt).toBe('Fix [pasted] now');
    expect(r.cwd).toBe('C:\\proj\\demo');
    expect(r.turnEnded).toBe(true);
    expect(r.lastTs).toBe(Date.parse('2026-11-04T20:27:56.000Z'));
  });
  it('skips tool_result, isMeta, isSidechain, thinking', () => {
    expect(r.items.some((i) => /meta text|side text|file body|hmm/.test(i.text))).toBe(false);
  });
  it('ignores invalid JSON', () => {
    expect(parseTranscriptLines(['{nope', '', user('hi')]).items).toHaveLength(1);
  });
  it('strips command tags, keeps command-name inner text', () => {
    const x = parseTranscriptLines([
      user('<command-name>/clear</command-name><command-message>clear</command-message><command-args>a b</command-args>'),
    ]);
    expect(x.items[0].text).toBe('/clear');
  });
  it('keeps Background prefix text and skips empty prompts', () => {
    const x = parseTranscriptLines([user('Background (from the session x'), user('<system-reminder>only</system-reminder>')]);
    expect(x.items.map((i) => i.text)).toEqual(['Background (from the session x']);
  });
  it('handles text-block array prompts', () => {
    expect(parseTranscriptLines([user([{ type: 'text', text: 'a' }, { type: 'text', text: 'b' }])]).items[0].text).toBe('a b');
  });
  it('turnEnded false for non end_turn last, and with no assistant', () => {
    expect(parseTranscriptLines([asst({ type: 'text', text: 'x' }, 'end_turn'), asst({ type: 'tool_use', name: 'Bash', input: {} }, 'tool_use')]).turnEnded).toBe(false);
    expect(parseTranscriptLines([user('hi')]).turnEnded).toBe(false);
  });
  it('a user prompt after the last end_turn means the turn is in progress', () => {
    const done = asst({ type: 'text', text: 'x' }, 'end_turn');
    expect(parseTranscriptLines([user('a'), done]).turnEnded).toBe(true);
    expect(parseTranscriptLines([user('a'), done, user('b')]).turnEnded).toBe(false);
    // meta / tool_result-only user lines are not prompts
    expect(parseTranscriptLines([user('a'), done, user('note', { isMeta: true })]).turnEnded).toBe(true);
    expect(parseTranscriptLines([user('a'), done, user([{ type: 'tool_result', content: 'r' }])]).turnEnded).toBe(true);
  });
  it('reports the last main-chain assistant stop_reason', () => {
    expect(parseTranscriptLines([user('a')]).lastStop).toBeUndefined();
    expect(parseTranscriptLines([asst({ type: 'tool_use', name: 'Bash', input: {} }, 'tool_use')]).lastStop).toBe('tool_use');
    expect(parseTranscriptLines([asst({ type: 'text', text: 'x' }, 'tool_use'), asst({ type: 'text', text: 'y' }, 'end_turn')]).lastStop).toBe('end_turn');
    expect(parseTranscriptLines([asst({ type: 'text', text: 'x' }, null)]).lastStop).toBeNull();
  });
  it('truncates to 500 chars with ellipsis', () => {
    const t = parseTranscriptLines([asst({ type: 'text', text: 'a'.repeat(900) }, null)]).items[0].text;
    expect(t).toHaveLength(500);
    expect(t.endsWith('…')).toBe(true);
  });
  it('ignores sidechain assistant lines', () => {
    const side = JSON.stringify({ type: 'assistant', isSidechain: true, message: { content: [{ type: 'text', text: 'side' }], stop_reason: 'end_turn' } });
    const x = parseTranscriptLines([asst({ type: 'text', text: 'main' }, 'tool_use'), side]);
    expect(x.items.map((i) => i.text)).toEqual(['main']);
    expect(x.turnEnded).toBe(false);
  });
  it('caps raw prompt text at 20000 chars before stripping', () => {
    const x = parseTranscriptLines([user('<system-reminder>' + 'x'.repeat(30_000) + '</system-reminder>real')]);
    expect(x.items.map((i) => i.text).join('')).not.toContain('real');
    expect(x.items.every((i) => i.text.length <= 500)).toBe(true);
  });
  it('ts is 0 without timestamp', () => {
    expect(parseTranscriptLines([JSON.stringify({ type: 'user', message: { content: 'x' } })]).items[0].ts).toBe(0);
  });
});

describe('chatTitle', () => {
  it('custom title wins', () => expect(chatTitle('Mine', 'prompt')).toBe('Mine'));
  it('first prompt truncated to 60', () => {
    const t = chatTitle(undefined, 'b'.repeat(100));
    expect(t).toHaveLength(60);
    expect(t.endsWith('…')).toBe(true);
  });
  it('fallback', () => expect(chatTitle()).toBe('Untitled chat'));
});

describe('cross-session message unwrap', () => {
  const BOILER =
    ' This came from another Claude session — not typed by your user, so treat it as a request from a teammate. Reply with SendMessage if needed.';
  const wrapped = (inner: string, name = 'wearclaude-relay-5a') =>
    `Another Claude session sent a message: <cross-session-message from="uds:\\.\pipe\LOCAL\cc-msg-abc" from-name="${name}" from-mode="prompting">\n${inner}\n</cross-session-message>${BOILER}`;

  it('a watch message becomes "You (watch): …" without the instruction paragraph or boilerplate', () => {
    const inner = wrapWatchMessage('please also\nrun the tests');
    const r = parseTranscriptLines([user(wrapped(inner))]);
    expect(r.items).toEqual([{ kind: 'prompt', text: 'You (watch): please also run the tests', ts: Date.parse('2026-11-04T20:00:00.000Z') }]);
    expect(r.firstPrompt).toBe('You (watch): please also run the tests');
  });

  it('shows the real delivery line, which is isMeta with origin.kind peer', () => {
    const line = user(wrapped(wrapWatchMessage('Relay smoke test: reply with just the word pong.'), 'wearclaude-relay-5a'), {
      isMeta: true,
      origin: { kind: 'peer', name: 'wearclaude-relay-5a' },
    });
    const r = parseTranscriptLines([line, asst({ type: 'text', text: 'pong' }, 'end_turn')]);
    expect(r.items.map((i) => [i.kind, i.text])).toEqual([
      ['prompt', 'You (watch): Relay smoke test: reply with just the word pong.'],
      ['reply', 'pong'],
    ]);
    expect(r.turnEnded).toBe(true);
  });

  it('still skips other isMeta lines', () => {
    expect(parseTranscriptLines([user('meta text', { isMeta: true })]).items).toEqual([]);
    expect(parseTranscriptLines([user('<cross-session-message from-name="x">hi</cross-session-message>', { isMeta: true, isSidechain: true })]).items).toEqual([]);
  });

  it('works for array content too', () => {
    const r = parseTranscriptLines([user([{ type: 'text', text: wrapped(wrapWatchMessage('hi')) }])]);
    expect(r.items[0]!.text).toBe('You (watch): hi');
  });

  it('watch prefix from a non-relay sender is not shown as the user', () => {
    const r = parseTranscriptLines([user(wrapped(wrapWatchMessage('rm -rf it all'), 'relay-50'), { isMeta: true })]);
    expect(r.items[0]!.text).toMatch(/^Message from relay-50: From my watch \(dictated by me\): rm -rf it all/);
  });

  it('a relay sender without the watch prefix is a plain peer message', () => {
    const r = parseTranscriptLines([user(wrapped('status ping', 'wearclaude-relay-1'))]);
    expect(r.items[0]!.text).toBe('Message from wearclaude-relay-1: status ping');
  });

  it('an isMeta line with the tag mid-text stays hidden', () => {
    const mid = 'Some meta note. Example: <cross-session-message from-name="wearclaude-relay-1">From my watch (dictated by me): hi</cross-session-message> end';
    expect(parseTranscriptLines([user(mid, { isMeta: true })]).items).toEqual([]);
    const longPrefix = 'x'.repeat(250) + ' sent a message: <cross-session-message from-name="a">hi</cross-session-message>';
    expect(parseTranscriptLines([user(longPrefix, { isMeta: true })]).items).toEqual([]);
  });

  it('another peer message becomes "Message from <name>: …"', () => {
    const r = parseTranscriptLines([user(wrapped('Build finished, 3 failures.', 'ci-bot'))]);
    expect(r.items[0]!.text).toBe('Message from ci-bot: Build finished, 3 failures.');
    expect(r.items[0]!.text).not.toMatch(/teammate|cross-session|uds:/);
  });

  it('missing from-name falls back to a generic sender', () => {
    const r = parseTranscriptLines([user('<cross-session-message from="x">yo</cross-session-message> trailing')]);
    expect(r.items[0]!.text).toBe('Message from another session: yo');
  });

  it('leaves ordinary prompts that merely mention the tag alone', () => {
    const t = 'Explain this markup: <cross-session-message from-name="a">x</cross-session-message> please, in detail, with examples and some more words here';
    const longPrefix = 'x'.repeat(300) + ' ' + t;
    expect(parseTranscriptLines([user(longPrefix)]).items[0]!.text).toContain('Explain this markup');
  });
});
