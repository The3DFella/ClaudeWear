// Plain strings shared by relay.ts (sending) and transcript.ts (display); no process imports here.

export const WATCH_PREFIX = 'From my watch (dictated by me):';
export const WATCH_INSTRUCTIONS =
  "Reply here in this chat as usual — I'm reading this chat on my watch. Don't message the sender back; it has already closed.";
/** Sender-name prefix the relay job's session gets (its cwd is the `wearclaude-relay` temp dir). */
export const RELAY_SENDER_PREFIX = 'wearclaude-relay';

export function wrapWatchMessage(text: string): string {
  return `${WATCH_PREFIX} ${text}\n\n${WATCH_INSTRUCTIONS}`;
}
