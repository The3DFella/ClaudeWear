import { createHash, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { homeDir } from './config.js';

const CODE_TTL_MS = 5 * 60_000;
const LOCK_MS = 5 * 60_000;
const MAX_FAILURES = 5;
/** Failed attempts older than this no longer count toward the lock. */
const FAILURE_WINDOW_MS = 15 * 60_000;

type Device = { name: string; tokenHash: string; createdAt: number };

const sha256 = (s: string): Buffer => createHash('sha256').update(s).digest();

export class DeviceAuth {
  private devices: Device[] = [];
  private pairing: { code: string; expiresAt: number } | null = null;
  private failures: number[] = []; // timestamps of recent failed attempts
  private lockedUntil = 0;
  private readonly revokeListeners = new Set<(name: string) => void>();

  constructor(private readonly now: () => number = Date.now) {
    this.load();
  }

  startPairing(): { code: string; expiresAt: number } {
    const code = randomInt(0, 1_000_000).toString().padStart(6, '0');
    this.pairing = { code, expiresAt: this.now() + CODE_TTL_MS };
    return { ...this.pairing };
  }

  pair(code: string, deviceName: string): { token: string } | { error: 'invalid' | 'expired' | 'locked' } {
    const t = this.now();
    if (t < this.lockedUntil) return { error: 'locked' };
    if (this.lockedUntil !== 0) {
      this.lockedUntil = 0;
      this.failures = [];
    }

    const p = this.pairing;
    const matches = p !== null && timingSafeEqual(sha256(code), sha256(p.code));
    if (matches && t <= p.expiresAt) {
      this.pairing = null; // single-use
      this.failures = [];
      const token = randomBytes(32).toString('base64url');
      this.devices.push({ name: this.uniqueName(deviceName), tokenHash: sha256(token).toString('hex'), createdAt: t });
      this.save();
      return { token };
    }

    this.failures = this.failures.filter((f) => t - f < FAILURE_WINDOW_MS);
    this.failures.push(t);
    if (this.failures.length >= MAX_FAILURES) this.lockedUntil = t + LOCK_MS;
    if (matches) {
      this.pairing = null;
      return { error: 'expired' };
    }
    return { error: 'invalid' };
  }

  /** `name`, or `name (2)`, `name (3)`, ... if taken, so re-pairing never silently replaces a device. */
  private uniqueName(name: string): string {
    const taken = new Set(this.devices.map((d) => d.name));
    if (!taken.has(name)) return name;
    let n = 2;
    while (taken.has(`${name} (${n})`)) n++;
    return `${name} (${n})`;
  }

  verify(token: string): { name: string } | null {
    if (typeof token !== 'string' || token === '') return null;
    const digest = sha256(token);
    let found: Device | null = null;
    for (const d of this.devices) {
      // Compare against every device so timing does not depend on which matched.
      if (timingSafeEqual(digest, Buffer.from(d.tokenHash, 'hex'))) found = d;
    }
    return found ? { name: found.name } : null;
  }

  list(): { name: string; createdAt: number }[] {
    return this.devices.map((d) => ({ name: d.name, createdAt: d.createdAt }));
  }

  revoke(name: string): boolean {
    const before = this.devices.length;
    this.devices = this.devices.filter((d) => d.name !== name);
    if (this.devices.length === before) return false;
    this.save();
    for (const fn of [...this.revokeListeners]) {
      try {
        fn(name);
      } catch {
        // a failing listener must not break revoke
      }
    }
    return true;
  }

  /** Called after a device is revoked (e.g. to close its live sockets). Returns an unsubscribe function. */
  onRevoke(fn: (name: string) => void): () => void {
    this.revokeListeners.add(fn);
    return () => {
      this.revokeListeners.delete(fn);
    };
  }

  private file(): string {
    return join(homeDir(), 'devices.json');
  }

  private load(): void {
    const f = this.file();
    if (!existsSync(f)) return;
    try {
      const raw = JSON.parse(readFileSync(f, 'utf8')) as unknown;
      if (Array.isArray(raw)) {
        this.devices = raw.filter(
          (d): d is Device =>
            !!d && typeof d.name === 'string' && typeof d.tokenHash === 'string' && typeof d.createdAt === 'number',
        );
      }
    } catch {
      this.devices = [];
    }
  }

  private save(): void {
    mkdirSync(homeDir(), { recursive: true });
    const f = this.file();
    const tmp = `${f}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.devices, null, 2), { mode: 0o600 });
    renameSync(tmp, f);
  }
}
