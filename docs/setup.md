# Setup guide

This guide takes you from nothing to a paired watch, then shows how to start the bridge automatically and how to update. If you just want the short version, see the [Quickstart in the README](../README.md#quickstart).

> **Names.** The app is called **ClaudeWear**. Some internal identifiers keep the older spelling: the `wearclaude-bridge` command, the `~/.wearclaude` folder and the Android package `dev.wearclaude.watch`. They're kept stable on purpose (see [RENAMING.md](RENAMING.md)).

- [What you need](#what-you-need)
- [1. Install and sign in to Claude Code](#1-install-and-sign-in-to-claude-code)
- [2. Install the bridge](#2-install-the-bridge)
- [3. Start the bridge](#3-start-the-bridge)
- [4. Build the watch app](#4-build-the-watch-app)
- [5. Install it on the watch](#5-install-it-on-the-watch)
- [6. Pair](#6-pair)
- [Start the bridge at login](#start-the-bridge-at-login)
- [Updating](#updating)
- [Uninstalling](#uninstalling)

## What you need

- **A Wear OS watch.** Wear OS 3+ (tested on Wear OS 5–6, Galaxy Watch 7). The watch and PC should be on the same Wi-Fi, or you can use [Tailscale Funnel](remote-access-tailscale.md).
- **A PC** running Windows, macOS or Linux with **Node.js 20 or newer** (`node --version`).
- **Claude Code 2.1.316 or newer**, signed in with any Claude plan that includes Claude Code (Pro, Max, Team, Enterprise) or an API key.
- **The Claude desktop app** (optional). It provides chat titles and the archived state, and it's required for replying by voice. Without it you still see your chats, live progress and finish buzzes.
- **To build the watch app:** JDK 17 and the Android SDK (Android Studio includes both), plus `adb` from the SDK's `platform-tools`.

## 1. Install and sign in to Claude Code

Install Claude Code by following the [official setup guide](https://code.claude.com/docs/en/setup), then sign in:

```sh
claude          # opens Claude Code
/login          # type this inside Claude Code and follow the prompts
```

Check the version is at least 2.1.316:

```sh
claude --version
```

You sign in yourself; the bridge only ever runs the unmodified `claude` program and never reads, stores or forwards your Claude credentials. Your use of Claude Code stays subject to [Anthropic's terms](https://code.claude.com/docs/en/legal-and-compliance).

## 2. Install the bridge

In a terminal, from the folder where you want the code to live:

```sh
git clone https://github.com/The3DFella/ClaudeWear.git
cd ClaudeWear/bridge
npm ci
npm run build
npm link        # optional: puts `wearclaude-bridge` on your PATH
```

If you skip `npm link`, run every `wearclaude-bridge …` command as `node dist/cli.js …` from the `bridge/` folder, for example `node dist/cli.js pair`.

On macOS/Linux, `npm link` may need `sudo` depending on how Node was installed. On Windows, run it from a normal (not administrator) terminal if Node was installed for your user.

### Optional: add a project

The watch shows your existing Claude Code chats without any setup. Projects are only needed for agents that the **bridge itself** runs (the ones whose tool calls you approve on the watch). Add only folders you trust: `claude -p` runs a project's hooks and `.mcp.json` without asking first.

From any folder (if you skipped `npm link`, use `node dist/cli.js` from `ClaudeWear/bridge`):

```sh
wearclaude-bridge add-project ~/code/my-app --name "My app"
wearclaude-bridge projects
```

## 3. Start the bridge

In the same terminal (after `npm link` this works from any folder; otherwise stay in `ClaudeWear/bridge` and run `node dist/cli.js`):

```sh
wearclaude-bridge
```

It first checks Claude Code. The check runs `claude --version` and then a tiny one-turn prompt to confirm you're signed in (this uses a very small amount of your plan). Skip the prompt with `--skip-probe`:

```sh
wearclaude-bridge start --skip-probe
```

When it's ready you'll see something like:

```
claude 2.1.320 OK
ClaudeWear bridge listening on 0.0.0.0:8787
  LAN: http://192.168.1.23:8787

Pairing code: 482913  (valid 5 minutes)
Run `wearclaude-bridge pair` for a new one.
```

If Tailscale Funnel is set up, a `Funnel:` line shows your public URL too.

Keep this window open while you use the watch. Stop the bridge with Ctrl+C.

**Firewall.** The watch connects to port 8787 on your PC.

- **Windows:** when Windows asks whether Node.js may use the network, allow it on **Private** networks. If you missed the prompt, run this in an administrator PowerShell:
  ```powershell
  New-NetFirewallRule -DisplayName "ClaudeWear bridge" -Direction Inbound -Protocol TCP -LocalPort 8787 -Action Allow -Profile Private
  ```
  Also make sure your Wi-Fi is set to a *Private* network in Windows Settings.
- **macOS:** if the application firewall is on, allow incoming connections for `node` when asked.
- **Linux:** with `ufw`, run `sudo ufw allow 8787/tcp`.

You can check reachability from another device on the same Wi-Fi by opening `http://<your-pc-ip>:8787/health` in a browser. It should show `{"ok":true,"version":"0.1.0"}`.

## 4. Get the watch app

### Option A: download the APK (easiest)

1. Download `ClaudeWear-<version>.apk` and `ClaudeWear-<version>.apk.sha256` from <https://github.com/The3DFella/ClaudeWear/releases/latest>.
2. Verify the checksum. The `.sha256` file holds the expected value; the command's output must match it:

   | OS | Command |
   | --- | --- |
   | Windows (PowerShell) | `Get-FileHash ClaudeWear-<version>.apk -Algorithm SHA256` |
   | macOS | `shasum -a 256 ClaudeWear-<version>.apk` |
   | Linux | `sha256sum ClaudeWear-<version>.apk` |

3. Install it on the watch using one of the methods in [step 5](#5-install-it-on-the-watch):
   - **From a PC** with Android platform-tools: enable Developer options and Wireless debugging on the watch, then `adb pair <ip:pairport>`, `adb connect <ip:port>` and `adb install ClaudeWear-<version>.apk`.
   - **From an Android phone** with an installer app that uses the watch's wireless debugging. Third-party apps exist for this (for example "Bugjaeger" or "Wear Installer 2"); they are not affiliated with ClaudeWear, so follow their own instructions: pair with the watch over wireless debugging, then pick the downloaded APK.

Updates install over the old version because every release is signed with the same key. If you previously installed a self-built **debug** version, uninstall it first (`adb uninstall dev.wearclaude.watch`), because its signature differs and Android will refuse the update.

### Option B: build from source

#### Point the build at Java 17 and the Android SDK

The simplest way is to install [Android Studio](https://developer.android.com/studio), open the `watch/` folder once and let it sync. It installs the SDK and writes `watch/local.properties` for you. Otherwise:

1. **Java 17.** Use any JDK 17, or the one bundled with Android Studio, and set `JAVA_HOME`:

   | OS | Typical Android Studio JDK |
   | --- | --- |
   | Windows | `C:\Program Files\Android\Android Studio\jbr` |
   | macOS | `/Applications/Android Studio.app/Contents/jbr/Contents/Home` |
   | Linux | `<android-studio folder>/jbr` |

2. **Android SDK.** Either set `ANDROID_HOME`, or create `watch/local.properties` containing `sdk.dir=…`:

   | OS | Default SDK location | `local.properties` line |
   | --- | --- | --- |
   | Windows | `%LOCALAPPDATA%\Android\Sdk` | `sdk.dir=C:/Users/you/AppData/Local/Android/Sdk` |
   | macOS | `~/Library/Android/sdk` | `sdk.dir=/Users/you/Library/Android/sdk` |
   | Linux | `~/Android/Sdk` | `sdk.dir=/home/you/Android/Sdk` |

   On Windows use forward slashes (or doubled backslashes) in `local.properties`. The file is git-ignored.

3. **adb.** It lives in `<sdk>/platform-tools`. Add that folder to your `PATH`, or call it by full path.

#### Build

In a second terminal (leave the bridge running in the first), from the `ClaudeWear` folder you cloned:

macOS / Linux:

```sh
cd ClaudeWear/watch
./gradlew :app:assembleDebug
```

Windows (PowerShell):

```powershell
cd ClaudeWear\watch
.\gradlew.bat :app:assembleDebug
```

The APK ends up at `watch/app/build/outputs/apk/debug/app-debug.apk`. The first build downloads Gradle and dependencies and takes a few minutes.

## 5. Install it on the watch

If you chose Option A, use `adb install ClaudeWear-<version>.apk` (from the folder holding the downloaded file) in place of the install command in step 6 below.

Wear OS watches are sideloaded over Wi-Fi with wireless debugging. These steps are for a Galaxy Watch; other Wear OS watches are similar.

1. **Join the same Wi-Fi as your PC** on the watch (Settings > Connections > Wi-Fi).
2. **Enable Developer options:** Settings > About watch > Software information, then tap **Software version** five times until Developer options are enabled.
3. **Turn on debugging:** Settings > Developer options, turn on **ADB debugging**, then **Wireless debugging**. Accept the prompt.
4. Open **Wireless debugging** and tap **Pair new device**. The watch shows an IP address and port and a six-digit pairing code.
5. On the PC, in a terminal (any folder), pair and connect:
   ```sh
   adb pair <watch-ip>:<pairing-port>     # enter the six-digit code when asked
   adb connect <watch-ip>:<port>          # the port shown on the main Wireless debugging screen (not the pairing port)
   adb devices                            # the watch should be listed as "device"
   ```
6. Install, from the `ClaudeWear/watch` folder (`cd ClaudeWear/watch`; on Windows `cd ClaudeWear\watch`):
   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   or build and install in one go from `watch/`: `./gradlew :app:installDebug` (`.\gradlew.bat :app:installDebug` on Windows).

You only need to `adb pair` once per PC. After that, `adb connect` is enough, but the port changes whenever wireless debugging restarts. Turn wireless debugging off when you're done; it uses battery.

## 6. Pair

1. Open **ClaudeWear** on the watch. Allow notifications when asked; without them you get no buzzes.
2. Tap **Enter address** and type the bridge address:
   - On your home Wi-Fi: the LAN address the bridge printed, for example `192.168.1.23:8787`. A bare IP address (or `localhost`, or a `.local` name) gets `http://` added automatically.
   - Over Tailscale Funnel: your `https://<machine>.<tailnet>.ts.net` URL. Any other bare hostname gets `https://` added, so type `http://` yourself if you really mean plain http to a hostname.
3. Tap **Enter 6-digit code** and type the pairing code from the bridge. Codes last 5 minutes and work once. Need a new one? Run `wearclaude-bridge pair` on the PC.
4. Tap **Pair**.

The watch is saved under its model name. List and remove paired watches on the PC with:

```sh
wearclaude-bridge devices
wearclaude-bridge devices revoke "<name>"
```

Revoking takes effect immediately and closes that watch's connection; the watch shows "This watch was removed — pair again". Pairing the same watch again adds `<name> (2)` rather than replacing the old entry, so revoke the stale one.

You're done. See the [usage guide](usage.md) for what each screen does.

## Start the bridge at login

The bridge is an ordinary foreground program, so any service manager can run it. A few things to keep in mind:

- Pass `start --skip-probe` so a slow network at login doesn't fail the sign-in probe.
- The service's `PATH` is often minimal. If the bridge says it can't run `claude --version`, set `claudeBin` in `config.json` to the full path of `claude` (find it with `where claude` on Windows or `which claude` elsewhere). See [configuration.md](configuration.md).
- The pairing code is printed to the log, which you may not see. Run `wearclaude-bridge pair` in a terminal whenever you need a code.

Replace the paths below with your own (`where node` / `which node` shows Node's path).

### Windows: Task Scheduler

From a normal PowerShell:

```powershell
$node = (Get-Command node).Source
$cli  = "$HOME\ClaudeWear\bridge\dist\cli.js"
$action  = New-ScheduledTaskAction -Execute $node -Argument "`"$cli`" start --skip-probe"
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit 0 -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName "ClaudeWear bridge" -Action $action -Trigger $trigger -Settings $settings
```

It starts at your next sign-in (or now with `Start-ScheduledTask "ClaudeWear bridge"`). A console window stays open while it runs. Remove it with `Unregister-ScheduledTask "ClaudeWear bridge"`.

### macOS: launchd

Save as `~/Library/LaunchAgents/dev.wearclaude.bridge.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>dev.wearclaude.bridge</string>
  <key>ProgramArguments</key>
  <array>
    <string>/opt/homebrew/bin/node</string>
    <string>/Users/you/ClaudeWear/bridge/dist/cli.js</string>
    <string>start</string>
    <string>--skip-probe</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>/Users/you/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/tmp/claudewear-bridge.log</string>
  <key>StandardErrorPath</key><string>/tmp/claudewear-bridge.log</string>
</dict>
</plist>
```

Load it:

```sh
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/dev.wearclaude.bridge.plist
tail -f /tmp/claudewear-bridge.log
```

Unload with `launchctl bootout gui/$(id -u)/dev.wearclaude.bridge`.

### Linux: systemd user unit

Save as `~/.config/systemd/user/claudewear-bridge.service`:

```ini
[Unit]
Description=ClaudeWear bridge
After=network-online.target

[Service]
ExecStart=/usr/bin/node %h/ClaudeWear/bridge/dist/cli.js start --skip-probe
Environment=PATH=%h/.local/bin:/usr/local/bin:/usr/bin:/bin
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

Then:

```sh
systemctl --user daemon-reload
systemctl --user enable --now claudewear-bridge
journalctl --user -u claudewear-bridge -f
```

To keep it running when you're logged out, run `loginctl enable-linger $USER` once.

## Updating

**Bridge:**

```sh
cd ClaudeWear
git pull
cd bridge
npm ci
npm run build
```

Then restart the bridge (Ctrl+C and start it again, or restart your login service). Paired watches reconnect on their own.

**Watch app:** reconnect adb (`adb connect <watch-ip>:<port>`) and run `./gradlew :app:installDebug` from `watch/` again. Your pairing and settings are kept.

Android only lets you update an app with an APK signed by the same key. Debug builds are signed with a key that's unique to the machine that built them, so if you switch between your own build and a CI artifact (or another PC), install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall first (`adb uninstall dev.wearclaude.watch`) and pair again.

**Claude Code:** update it as usual. The bridge checks the version at every start.

## Uninstalling

- Watch: long-press the ClaudeWear icon and uninstall, or `adb uninstall dev.wearclaude.watch`.
- PC: stop the bridge, remove any login service you set up, run `npm unlink -g wearclaude-bridge` if you used `npm link`, then delete the `ClaudeWear` folder and `~/.wearclaude`.
- Tailscale: `tailscale funnel reset` if you enabled Funnel.
