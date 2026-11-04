export function buildClaudeArgs(o: {
  sessionId: string;
  resume: boolean;
  mcpConfig: string;
  permissionMode: string;
}): string[] {
  return [
    '-p',
    '--output-format', 'stream-json',
    '--verbose',
    o.resume ? '--resume' : '--session-id', o.sessionId,
    '--mcp-config', o.mcpConfig,
    '--permission-prompt-tool', 'mcp__wearclaude__approve',
    '--permission-mode', o.permissionMode,
  ];
}
