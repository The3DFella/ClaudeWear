import { createHash, timingSafeEqual } from 'node:crypto';
import Fastify from 'fastify';
import type { DeviceAuth } from './auth.js';

const LOOPBACK = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1']);
const sha = (s: string) => createHash('sha256').update(s).digest();

// Local-only admin listener. Never expose it via tailscale serve/funnel.
export async function startAdminServer(d: {
  auth: DeviceAuth;
  port: number;
  secret: string;
}): Promise<{ port: number; close(): Promise<void> }> {
  const app = Fastify({ logger: false, bodyLimit: 4 * 1024 });
  const forbid = (reply: { code(n: number): { send(b: unknown): unknown } }) => reply.code(403).send({ error: 'forbidden' });

  app.addHook('onRequest', async (req, reply) => {
    const h = req.headers;
    const proxied = 'x-forwarded-for' in h || 'forwarded' in h || Object.keys(h).some((k) => k.startsWith('tailscale-'));
    const given = h['x-wearclaude-admin'];
    const okSecret = typeof given === 'string' && timingSafeEqual(sha(given), sha(d.secret));
    if (!LOOPBACK.has(req.socket.remoteAddress ?? '') || proxied || !okSecret) return forbid(reply);
  });
  app.setErrorHandler((_e, _req, reply) => reply.code(400).send({ error: 'bad_request' }));

  app.post('/pairing-code', async () => d.auth.startPairing());
  app.get('/devices', async () => d.auth.list());
  app.post('/devices/revoke', async (req) => {
    const b = req.body as { name?: unknown } | null;
    if (!b || typeof b.name !== 'string' || b.name === '') throw new Error('bad');
    return { revoked: d.auth.revoke(b.name) };
  });

  await app.listen({ port: d.port, host: '127.0.0.1' });
  const addr = app.server.address();
  return { port: typeof addr === 'object' && addr ? addr.port : d.port, close: () => app.close() };
}
