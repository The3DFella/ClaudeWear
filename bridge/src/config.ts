import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { basename, join, resolve } from 'node:path';

export type Project = { id: string; name: string; path: string };

export type Config = {
  port: number;
  bindHost: string;
  projects: Project[];
  defaultPermissionMode: 'default' | 'acceptEdits';
  approvalTimeoutSec: number;
  claudeBin?: string;
  showPcChats: boolean;
  pcChatsMaxAgeDays: number;
  /** Read sidebar titles and archived state from the Claude desktop app's chat metadata. */
  useDesktopMetadata: boolean;
};

export function homeDir(): string {
  return process.env.WEARCLAUDE_HOME || join(homedir(), '.wearclaude');
}

function configPath(): string {
  return join(homeDir(), 'config.json');
}

function defaults(): Config {
  return {
    port: 8787,
    bindHost: '0.0.0.0',
    projects: [],
    defaultPermissionMode: 'default',
    approvalTimeoutSec: 300,
    showPcChats: true,
    pcChatsMaxAgeDays: 30,
    useDesktopMetadata: true,
  };
}

export function loadConfig(): Config {
  const file = configPath();
  if (!existsSync(file)) return defaults();
  let parsed: Partial<Config>;
  try {
    parsed = JSON.parse(readFileSync(file, 'utf8')) as Partial<Config>;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    throw new Error(`Invalid config at ${file}: ${msg}`);
  }
  const c = { ...defaults(), ...parsed };
  const d = defaults();
  if (typeof c.pcChatsMaxAgeDays !== 'number' || !Number.isFinite(c.pcChatsMaxAgeDays) || c.pcChatsMaxAgeDays <= 0) {
    c.pcChatsMaxAgeDays = d.pcChatsMaxAgeDays;
  }
  if (typeof c.showPcChats !== 'boolean') c.showPcChats = d.showPcChats;
  if (typeof c.useDesktopMetadata !== 'boolean') c.useDesktopMetadata = d.useDesktopMetadata;
  return c;
}

export function saveConfig(c: Config): void {
  mkdirSync(homeDir(), { recursive: true });
  writeFileSync(configPath(), JSON.stringify(c, null, 2) + '\n', 'utf8');
}

function slugify(name: string): string {
  const slug = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug || 'project';
}

export function addProject(c: Config, path: string, name?: string): Project {
  const abs = resolve(path);
  const st = statSync(abs, { throwIfNoEntry: false });
  if (!st || !st.isDirectory()) {
    throw new Error(`Not a directory: ${path}`);
  }
  const projectName = name ?? basename(abs);
  const base = slugify(projectName);
  const taken = new Set(c.projects.map((p) => p.id));
  let id = base;
  for (let n = 2; taken.has(id); n++) id = `${base}-${n}`;
  return { id, name: projectName, path: abs };
}
