import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { randomBytes } from 'node:crypto';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { z } from 'zod';
import type { ApprovalBroker } from './approvals.js';
import { PRODUCT_NAME } from './product.js';

export interface PermissionServer {
  port: number;
  mcpConfigFor(agentId: string): string;
  forgetAgent(agentId: string): void;
  close(): Promise<void>;
}

function buildServer(broker: ApprovalBroker, agentId: string): McpServer {
  const server = new McpServer({ name: 'wearclaude', version: '0.1.0' });
  server.registerTool(
    'approve',
    {
      description: `Ask the ${PRODUCT_NAME} smartwatch whether a tool call may run.`,
      inputSchema: {
        tool_name: z.string(),
        input: z.record(z.string(), z.unknown()),
        tool_use_id: z.string().optional(),
      },
    },
    async ({ tool_name, input }) => {
      // NOTE: if the MCP client disconnects/aborts while we wait, the approval
      // intentionally stays pending until it is answered or times out.
      const decision = await broker.request(agentId, tool_name, input);
      const result =
        decision === 'allow'
          ? { behavior: 'allow', updatedInput: input }
          : { behavior: 'deny', message: decision === 'timeout' ? 'Timed out — denied' : 'Denied from watch' };
      return { content: [{ type: 'text' as const, text: JSON.stringify(result) }] };
    },
  );
  return server;
}

export async function startPermissionServer(broker: ApprovalBroker, port: number): Promise<PermissionServer> {
  const secrets = new Map<string, string>();

  const handle = async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    const m = /^\/mcp\/([^/]+)\/([^/?]+)/.exec(req.url ?? '');
    const agentId = m ? decodeURIComponent(m[1]!) : undefined;
    const secret = m?.[2];
    if (!agentId || !secret || secrets.get(agentId) !== secret) {
      res.writeHead(403, { 'content-type': 'text/plain' }).end('Forbidden');
      return;
    }
    if (req.method !== 'POST') {
      res.writeHead(405, { 'content-type': 'application/json', allow: 'POST' }).end(
        JSON.stringify({ jsonrpc: '2.0', error: { code: -32000, message: 'Method not allowed' }, id: null }),
      );
      return;
    }
    const server = buildServer(broker, agentId);
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
    res.on('close', () => {
      void transport.close();
      void server.close();
    });
    await server.connect(transport);
    await transport.handleRequest(req, res);
  };

  const http = createServer((req, res) => {
    handle(req, res).catch(() => {
      if (!res.headersSent) res.writeHead(500).end();
      else res.end();
    });
  });
  await new Promise<void>((resolve, reject) => {
    http.once('error', reject);
    http.listen(port, '127.0.0.1', resolve);
  });
  const boundPort = (http.address() as AddressInfo).port;

  return {
    port: boundPort,
    mcpConfigFor(agentId) {
      let secret = secrets.get(agentId);
      if (!secret) {
        secret = randomBytes(16).toString('hex');
        secrets.set(agentId, secret);
      }
      return JSON.stringify({
        mcpServers: {
          wearclaude: { type: 'http', url: `http://127.0.0.1:${boundPort}/mcp/${encodeURIComponent(agentId)}/${secret}` },
        },
      });
    },
    forgetAgent(agentId) {
      secrets.delete(agentId);
    },
    close() {
      return new Promise<void>((resolve) => {
        http.closeAllConnections();
        http.close(() => resolve());
      });
    },
  };
}
