import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { isAbsolute, join, relative, resolve } from 'node:path';
import {
  homeDir,
  loadConfig,
  saveConfig,
  addProject,
  type Config,
} from '../src/config.js';

let home: string;
let work: string;

beforeEach(() => {
  home = mkdtempSync(join(tmpdir(), 'wc-home-'));
  work = mkdtempSync(join(tmpdir(), 'wc-work-'));
  process.env.WEARCLAUDE_HOME = home;
});

afterEach(() => {
  delete process.env.WEARCLAUDE_HOME;
  rmSync(home, { recursive: true, force: true });
  rmSync(work, { recursive: true, force: true });
});

describe('homeDir', () => {
  it('honours WEARCLAUDE_HOME', () => {
    expect(homeDir()).toBe(home);
  });
});

describe('loadConfig', () => {
  it('returns defaults when no file exists', () => {
    expect(loadConfig()).toEqual({
      port: 8787,
      bindHost: '0.0.0.0',
      projects: [],
      defaultPermissionMode: 'default',
      approvalTimeoutSec: 300,
      showPcChats: true,
      pcChatsMaxAgeDays: 30,
      useDesktopMetadata: true,
    });
  });

  it('coerces bad pcChats settings to safe values', () => {
    for (const bad of ['abc', -5, 0, null, Infinity]) {
      writeFileSync(join(home, 'config.json'), JSON.stringify({ pcChatsMaxAgeDays: bad, showPcChats: 'no', useDesktopMetadata: 'x' }), 'utf8');
      const c = loadConfig();
      expect(c.pcChatsMaxAgeDays).toBe(30);
      expect(c.showPcChats).toBe(true);
      expect(c.useDesktopMetadata).toBe(true);
    }
    writeFileSync(join(home, 'config.json'), JSON.stringify({ pcChatsMaxAgeDays: 7, showPcChats: false }), 'utf8');
    expect(loadConfig()).toMatchObject({ pcChatsMaxAgeDays: 7, showPcChats: false });
  });

  it('round-trips through saveConfig', () => {
    const c: Config = {
      port: 9000,
      bindHost: '127.0.0.1',
      projects: [{ id: 'x', name: 'X', path: '/tmp/x' }],
      defaultPermissionMode: 'acceptEdits',
      approvalTimeoutSec: 60,
      showPcChats: false,
      pcChatsMaxAgeDays: 7,
      useDesktopMetadata: false,
      claudeBin: '/usr/bin/claude',
    };
    saveConfig(c);
    expect(loadConfig()).toEqual(c);
  });

  it('wraps a corrupt config.json in a descriptive error', () => {
    const file = join(home, 'config.json');
    writeFileSync(file, '{ not json', 'utf8');
    expect(() => loadConfig()).toThrow(
      new RegExp(`^Invalid config at ${escapeRe(file)}: .+`),
    );
  });
});

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

describe('addProject', () => {
  it('slugifies the folder basename into the id', () => {
    const dir = join(work, 'My App');
    mkdirSync(dir);
    const p = addProject(loadConfig(), dir);
    expect(p.id).toBe('my-app');
    expect(p.name).toBe('My App');
  });

  it('suffixes duplicate ids with -2, -3', () => {
    const dir = join(work, 'My App');
    mkdirSync(dir);
    const c = loadConfig();
    const first = addProject(c, dir);
    c.projects.push(first);
    const second = addProject(c, dir);
    expect(second.id).toBe('my-app-2');
    c.projects.push(second);
    expect(addProject(c, dir).id).toBe('my-app-3');
  });

  it('stores the path resolved and absolute', () => {
    const dir = join(work, 'rel');
    mkdirSync(dir);
    const p = addProject(loadConfig(), dir, 'Custom');
    expect(p.path).toBe(join(work, 'rel'));
    expect(p.name).toBe('Custom');
  });

  it('resolves a relative path to an absolute stored path', () => {
    // Must live on the same drive as cwd, or relative() would return an absolute path on Windows.
    const base = mkdtempSync(join(process.cwd(), '.tmp-cfg-'));
    try {
      const dir = join(base, 'relative-app');
      mkdirSync(dir);
      const rel = relative(process.cwd(), dir);
      expect(isAbsolute(rel)).toBe(false);
      const p = addProject(loadConfig(), rel);
      expect(isAbsolute(p.path)).toBe(true);
      expect(p.path).toBe(resolve(dir));
      expect(p.name).toBe('relative-app');
    } finally {
      rmSync(base, { recursive: true, force: true });
    }
  });

  it('throws when the folder is missing', () => {
    const missing = join(work, 'nope');
    expect(() => addProject(loadConfig(), missing)).toThrow(
      `Not a directory: ${missing}`,
    );
  });
});
