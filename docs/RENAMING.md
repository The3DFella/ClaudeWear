# Renaming the project

The display name is kept in as few places as possible. Internal identifiers are deliberately *not* tied to it.

## Display name (safe to change)

Current value: `ClaudeWear`.

| Where | What to edit |
| --- | --- |
| `bridge/src/product.ts` | The exported `PRODUCT_NAME` constant. Used by the startup banner, the CLI usage text and the approval MCP tool description. |
| `watch/app/src/main/res/values/strings.xml` | The `app_name` string. Used by the launcher label (`AndroidManifest.xml`) and the foreground-service notification title. Any new UI text that shows the product name must read `R.string.app_name`. |
| `bridge/package.json` | `description`, `repository`, `homepage`, `bugs`. |
| `README.md`, `bridge/README.md`, `docs/*.md`, `LICENSE` | Prose and the copyright line. Design docs in `docs/design/` are historical and may keep the old name. |

## Internal identifiers (leave alone unless you accept the trade-offs)

| Identifier | Where | Changing it means |
| --- | --- | --- |
| `wearclaude-bridge` (npm name and bin) | `bridge/package.json`, CLI help, docs | Users must reinstall and update scripts or shortcuts. Cheap if nothing is published yet. |
| `dev.wearclaude.watch` (applicationId and package) | `watch/app/build.gradle.kts`, Kotlin sources, manifest | Android treats it as a different app: uninstall the old one and re-pair the watch; the stored token (Keystore alias `wearclaude_token`) and settings are lost. |
| `~/.wearclaude` and `WEARCLAUDE_HOME` | `bridge/src/config.ts` | Existing `config.json`, `devices.json` and `agents.json` would be orphaned. A rename needs a one-time migration (move the directory or fall back to the old path), otherwise every device must re-pair. |
| `wearclaude-relay` (relay cwd and sender prefix) | `bridge/src/relay.ts`, `bridge/src/relayText.ts`, `bridge/src/transcript.ts` | The transcript reader recognises the watch's dictated replies by this prefix. The relay cwd and the prefix must change together, and older chats written under the old prefix would no longer show as watch dictation. |
| `x-wearclaude-admin` header, MCP server name `wearclaude`, tool `mcp__wearclaude__approve` | `bridge/src/adminServer.ts`, `claudeArgs.ts`, `permissionServer.ts` | Must change consistently in all places; nothing persistent depends on them. |
| `wearclaude_settings`, `wearclaude_input` | Watch `Settings.kt`, `PairScreen.kt` | Changing the DataStore name drops saved settings (re-pair). |
