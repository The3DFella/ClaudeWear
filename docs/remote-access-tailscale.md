# Remote access with Tailscale

At home, the watch talks to the bridge over your Wi-Fi. To use ClaudeWear anywhere else (mobile data, the office, a café), make the bridge's main port reachable with [Tailscale](https://tailscale.com).

There are two options:

| | Funnel | Serve |
| --- | --- | --- |
| Reachable from | The whole internet | Only devices on your tailnet |
| Needs Tailscale on the watch | No | Yes |
| Encryption | HTTPS with a real certificate | HTTPS with a real certificate |
| Protected by | The ClaudeWear device token | Your tailnet, plus the device token |
| Practical for a Galaxy Watch | **Yes** | Usually not (the watch can't run Tailscale) |

Only ever expose the main port (default **8787**). The approval MCP port (8788) and admin port (8789) are local-only by design; never serve or funnel them.

## Funnel (recommended for watches)

1. **Install Tailscale on the PC** and sign in. See [tailscale.com/download](https://tailscale.com/download).
2. **Turn on Funnel for port 8787:**
   ```sh
   tailscale funnel --bg 8787
   ```
   `--bg` keeps it running in the background, including after a reboot. Without it, Funnel stops when you close the terminal. The first time, Tailscale may ask you to enable HTTPS certificates and Funnel for your tailnet in the admin console; follow the link it prints.
3. **Find your public URL:**
   ```sh
   tailscale funnel status
   ```
   It looks like `https://<machine>.<tailnet>.ts.net`. The bridge also prints it at startup as a `Funnel:` line.
4. **Pair the watch** (or pair again) with that URL and a fresh code from `wearclaude-bridge pair`. You can type it without `https://`; a `*.ts.net` name is always reached over HTTPS.

The same URL also works at home, so many people just pair once with the Funnel URL and never use the LAN address.

Turn Funnel off with:

```sh
tailscale funnel reset
```

### What Funnel exposes

- Anyone on the internet who learns the URL can reach the bridge's main port.
- Every request except `/health` and `/pair` needs a valid device token, sent as an `Authorization: Bearer` header (including on the WebSocket). Tokens are 32 random bytes, never appear in URLs, and only their hash is stored on the PC.
- `/health` answers with the bridge version. `/pair` accepts pairing codes, which expire after 5 minutes and lock for 5 minutes after 5 wrong tries.
- Someone who knows the URL could keep pairing locked by sending wrong codes. That blocks *new* pairings only; watches that are already paired keep working.
- Lose a watch? Run `wearclaude-bridge devices revoke "<name>"`. Its token stops working and its open connection is closed immediately.

If you only use Funnel (never the LAN), you can also set `"bindHost": "127.0.0.1"` in `~/.wearclaude/config.json` so the bridge isn't reachable on your Wi-Fi at all. Funnel forwards to localhost, so it keeps working. See [configuration.md](configuration.md).

## Serve (tailnet only)

```sh
tailscale serve --bg 8787
```

Turn it off with `tailscale serve reset`.

This makes the bridge reachable at the same `https://<machine>.<tailnet>.ts.net` address, but only from devices signed in to your tailnet. It's the more private option, but it only works if the watch itself can reach your tailnet. Galaxy Watches generally can't run the Tailscale app, so Funnel is usually the practical choice.

## Troubleshooting

- **The bridge doesn't print a `Funnel:` line.** It runs `tailscale funnel status --json` at startup and prints the first `*.ts.net` name it finds. If `tailscale` isn't on the bridge's `PATH`, or Funnel isn't on, nothing is printed. Funnel still works if `tailscale funnel status` shows it.
- **"Can't reach your PC" over Funnel.** Check `tailscale funnel status` shows port 8787, the bridge is running, and the PC is awake. Open `https://<machine>.<tailnet>.ts.net/health` in a phone browser; it should show `{"ok":true,...}`.
- **It worked, then stopped after a reboot.** You probably ran `tailscale funnel 8787` without `--bg`. Run it again with `--bg`.

More fixes are in [troubleshooting.md](troubleshooting.md).
