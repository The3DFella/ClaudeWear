# Troubleshooting

Find the message or symptom you're seeing, then try the fixes in order.

- [Bridge won't start](#bridge-wont-start)
- [Pairing](#pairing)
- [Connection](#connection)
- [Chats](#chats)
- [Reply by voice](#reply-by-voice)
- [Notifications](#notifications)
- [Building and installing the watch app](#building-and-installing-the-watch-app)
- [Claude Code errors](#claude-code-errors)
- [Still stuck?](#still-stuck)

## Bridge won't start

**``Problem: Could not run `claude --version`. Is Claude Code installed? Set claudeBin in config.json if needed.``**
- Run `claude --version` in the same terminal. If that fails, install Claude Code or fix your `PATH`.
- If it works in your terminal but not when the bridge runs from a login service, set `claudeBin` to the full path from `where claude` (Windows) or `which claude` (macOS/Linux). See [configuration.md](configuration.md).

**`Problem: claude 2.x.y is too old. Update to 2.1.316 or newer.`**
- Update Claude Code, then start the bridge again.

**``Problem: Not signed in. Run `claude`, then type /login.``**
- Do exactly that: run `claude`, type `/login`, finish signing in, then start the bridge again.

**`Problem: The claude probe timed out after 90 seconds.`** or **`claude probe failed: …`**
- The startup check sends Claude Code a one-turn prompt. Check your internet connection and that `claude -p "hi"` works in a terminal.
- If you're sure Claude Code works, start with `wearclaude-bridge start --skip-probe`.

**`Invalid config at …/config.json: …`**
- `config.json` isn't valid JSON (a trailing comma, an unescaped Windows backslash). Fix it, or delete it to go back to the defaults. See [configuration.md](configuration.md).

**`listen EADDRINUSE … 8787`**
- Another bridge (or another program) already uses the port. Stop the other bridge, or change `port` in `config.json`. Note that ports `port+1` and `port+2` must be free too.

**`` Bridge is not running — start it with `wearclaude-bridge` ``** (from `wearclaude-bridge pair`)
- `pair` asks the running bridge for a code through its local admin port. Start the bridge first, on the same PC and with the same `WEARCLAUDE_HOME`.

**`Admin secret mismatch — restart the bridge`**
- The secret in `~/.wearclaude/admin.secret` doesn't match the running bridge, usually because two bridges were started. Stop all of them and start one.

## Pairing

**"Can't reach your PC"**

The watch couldn't open a connection to the address you typed.

1. Is the bridge running and does it show a `LAN:` line with that address?
2. Are the watch and PC on the **same Wi-Fi**? Guest networks and some mesh/office networks block devices from talking to each other.
3. **Firewall.** On Windows, allow Node.js on *Private* networks and make sure your Wi-Fi is marked Private (Settings > Network & internet > Wi-Fi > your network). Or add a rule in an administrator PowerShell:
   ```powershell
   New-NetFirewallRule -DisplayName "ClaudeWear bridge" -Direction Inbound -Protocol TCP -LocalPort 8787 -Action Allow -Profile Private
   ```
   On macOS, allow incoming connections for `node`. On Linux with ufw: `sudo ufw allow 8787/tcp`.
4. Open `http://<pc-ip>:8787/health` in a phone browser on the same Wi-Fi. If it shows `{"ok":true,...}`, the PC side is fine and the problem is the address on the watch.
5. Check the address: a bare IP gets `http://`, any other hostname gets `https://`. To reach a plain-http hostname on your LAN, type `http://` yourself.
6. The PC's IP can change. Look at the bridge's `LAN:` line again.

**"Wrong or expired code"**
- Codes are valid for **5 minutes** and work **once**. Get a fresh one with `wearclaude-bridge pair` and type it straight away.
- Make sure you're pairing with the bridge that printed the code (not another PC).

**"Too many tries — wait 5 minutes"**
- Five wrong codes within 15 minutes lock pairing for 5 minutes. Wait, then use a fresh code. Watches that are already paired are not affected.
- If this keeps happening on a Funnel URL you didn't share, someone may be guessing. Your paired watches are still safe; consider turning Funnel off for a while.

**"Pairing failed (…)"**
- Any other HTTP error. Check that the address points at the bridge's main port (default 8787), not 8788 or 8789, and not some other web server.

**"This watch was removed — pair again"**
- The watch's token was revoked (`wearclaude-bridge devices revoke`) or `devices.json` was deleted. Pair again with a new code.

## Connection

**"Reconnecting…" stays on Home**
- The bridge stopped, the PC went to sleep, or you left the Wi-Fi. The watch keeps retrying (up to every 30 seconds) and catches up when it's back.
- Away from home, a LAN address won't work. Use [Tailscale Funnel](remote-access-tailscale.md).
- Prevent your PC from sleeping while you rely on the watch.

**It worked at home but not on mobile data**
- That's expected with a LAN address. Pair with your Funnel URL instead; it works both at home and away.

## Chats

**"No active chats on your PC"**
- Only chats active in the last 30 days are shown (`pcChatsMaxAgeDays`).
- `showPcChats` must be `true` (the default).
- The chats must belong to the same user account the bridge runs as: they're read from `~/.claude/projects`.
- Chats started in a temp folder are hidden on purpose.

**Titles look wrong, or archived chats show on Home**
- Titles and the archived flag come from the Claude desktop app's metadata. Without the desktop app (or with `useDesktopMetadata: false`) the title falls back to the chat's custom title or first prompt, and every chat counts as active.
- Desktop metadata is re-read about every 10 seconds; give it a moment after renaming or archiving.

**A chat shows "working…" for a long time**
- A chat counts as working while its transcript is growing and the turn hasn't ended. While a tool or a permission prompt is pending on the PC it can stay "working" for up to 10 minutes. Check the PC: it may be waiting for you.

## Reply by voice

**There's no 🎤 Reply button, only "Read-only — reply from your PC or the Claude phone app"**
- The chat has no title yet. The relay finds chats by title. Give it a title in the desktop app (or wait for the automatic one), then reopen the chat on the watch.

**"Open this chat in the Claude desktop app, then try again"**
- No live session with this title was found. Messages can only be delivered to a chat that is open and running in the Claude desktop app. Open it there and send again.
- The title must match exactly. If you renamed it a moment ago, wait a few seconds for the watch to pick up the new title.

**"Several open chats have this title — rename one on your PC"**
- Two or more open chats share this exact title, and the bridge won't guess. Rename one of them in the desktop app.

**"This chat has no title yet"**
- As above: give the chat a title on the PC.

**"Still sending your last message"**
- One message per chat at a time (and three overall). Wait for the previous one to finish.

**"Couldn't deliver (502)"**
- The relay job failed or timed out (after 90 seconds). The message **may still have arrived**, so check the chat before sending again.
- Make sure your Claude Code version supports cross-session messaging (the bridge requires 2.1.316 or newer) and that you're signed in.
- Usage limits also make relay jobs fail. See [Claude Code errors](#claude-code-errors).

**"Can't reach your PC"** while sending
- See [Connection](#connection). If the connection dropped mid-send, the message may still have been delivered.

**Claude answers but treats my message as coming from someone else**
- That's how cross-session messaging works: Claude sees it as a message from another session. The relay wraps your words in "From my watch (dictated by me): …" so Claude knows it's you, but it still can't use it to approve permission prompts.

## Notifications

**No buzz when a chat finishes in the background**
1. Allow notifications for ClaudeWear (Settings > Apps > ClaudeWear > Notifications). The app asks once, the first time it opens.
2. Make sure the **Agent updates** and **Approvals** channels are on.
3. Check Do Not Disturb, Bedtime and Theatre modes.
4. The background service only runs while something is working, and stops about a minute after everything is idle. If the app was closed and nothing was working, a new run on the PC won't be noticed until you open the app. Open ClaudeWear once after starting a long task on the PC.
5. Some battery savers stop background apps. Exclude ClaudeWear from battery optimisation if your watch offers it.
6. A chat that simply stops producing output (no proper end of turn) doesn't buzz.

## Building and installing the watch app

**`SDK location not found`**
- Set `ANDROID_HOME`, or create `watch/local.properties` with `sdk.dir=<path to your SDK>`. On Windows use forward slashes. See [setup.md](setup.md#4-build-the-watch-app).

**Gradle complains about the Java version**
- Use JDK 17. Point `JAVA_HOME` at it, for example Android Studio's bundled `jbr` folder.

**`adb: command not found` / `'adb' is not recognized`**
- `adb` is in `<sdk>/platform-tools`. Add it to your `PATH` or call it by full path.

**Wireless debugging keeps turning off / `adb connect` fails**
- Wireless debugging turns itself off when the watch leaves Wi-Fi, reboots, or sometimes when the screen sleeps. Turn it back on and use the **new port** shown on the Wireless debugging screen. You don't need to `adb pair` again.
- The watch must be on Wi-Fi, not only Bluetooth to your phone. Turning Bluetooth off for a moment makes some watches switch to Wi-Fi.
- Keep the watch on its charger and the screen awake while installing.
- The pairing port (from "Pair new device") and the connect port (on the main Wireless debugging screen) are different.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**
- The installed app was signed by a different debug key (another PC, or a CI artifact). Run `adb uninstall dev.wearclaude.watch`, install again and pair again.

**`more than one device/emulator`**
- Add `-s <watch-ip>:<port>` to `adb` commands, or disconnect the others with `adb disconnect`.

## Claude Code errors

These come from Claude Code itself and show up on the watch as an agent's failure reason, or in the chat on the PC.

**"Not signed in — run claude, then /login, on your PC"**
- Claude Code's sign-in expired or was removed. On the PC run `claude`, type `/login`, and sign in again. No need to re-pair the watch.

**"Usage limit reached"**
- Your Claude plan's usage limit (or an API rate limit) was hit. Wait for it to reset, or check your plan. Each voice reply uses one small extra Claude call for the relay job, and the startup check uses one tiny call (skip it with `--skip-probe`).

## Still stuck?

Search the [issues](https://github.com/The3DFella/ClaudeWear/issues) or open a new one with:

- your OS, Node version (`node --version`), Claude Code version (`claude --version`) and watch model,
- what you did and the exact message you saw,
- the bridge's console output (remove IP addresses, paths and anything private).

Security problems: please report them privately, see [SECURITY.md](../SECURITY.md).
