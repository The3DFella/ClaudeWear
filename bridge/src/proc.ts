import { execFile, type ChildProcess } from 'node:child_process';

/**
 * Stops a child and everything it started. On Windows `taskkill /T /F` kills the whole tree
 * (claude may run under cmd.exe and spawn its own children); elsewhere SIGINT, then SIGKILL
 * after `graceMs` if it is still running.
 */
export function killTree(child: ChildProcess, graceMs = 10_000): void {
  if (child.exitCode !== null || child.pid === undefined) return;
  if (process.platform === 'win32') {
    execFile('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true }, () => {});
    return;
  }
  child.kill('SIGINT');
  const t = setTimeout(() => {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
  }, graceMs);
  t.unref();
  child.once('close', () => clearTimeout(t));
}
