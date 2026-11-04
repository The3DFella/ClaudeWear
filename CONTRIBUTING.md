# Contributing to ClaudeWear

Thanks for helping out. Issues and pull requests are welcome.

## Dev setup

**Bridge** (Node 20 or newer), from `bridge/`:

```
npm ci
npm run dev          # run from source
npm run build        # compile to dist/
```

**Watch app** (JDK 17 and the Android SDK, via `ANDROID_HOME` or `sdk.dir=` in `watch/local.properties`), from `watch/`:

```
./gradlew :app:assembleDebug     # gradlew.bat on Windows
./gradlew :app:installDebug      # sideload to a connected watch or emulator
```

See `docs/setup.md` for pairing and running the whole thing.

## Tests

macOS / Linux, from the repository root:

```sh
(cd bridge && npx tsc --noEmit && npx vitest run)
(cd watch && ./gradlew :app:testDebugUnitTest)
```

Windows (PowerShell), from the repository root:

```powershell
cd bridge; npx tsc --noEmit; npx vitest run; cd ..
cd watch; .\gradlew.bat :app:testDebugUnitTest; cd ..
```

CI runs the same commands on every pull request.

## Code style

- Keep files focused: one clear responsibility each, small enough to read in one sitting.
- Tests first: write the failing test, then the code. Bug fixes come with a regression test.
- Keep UI logic in plain Kotlin/TypeScript functions that can be unit tested; keep Compose and HTTP glue thin.
- Never read, log or copy Claude credentials or API keys.
- The product name is a display string only; see `docs/RENAMING.md` before hard-coding it.

## Commit messages

Conventional style: `type(scope): summary`, for example `fix(bridge): ...` or `feat(watch): ...`. Types in use: `feat`, `fix`, `docs`, `test`, `chore`, `ci`. Keep the summary short and imperative.

## Pull request checklist

- [ ] Tests added or updated, and they pass locally
- [ ] `npx tsc --noEmit` is clean (bridge)
- [ ] No personal data (usernames, absolute paths, hostnames, tokens) in code, fixtures or docs
- [ ] Docs updated if behaviour changed
- [ ] Screenshots updated if the UI changed

## Screenshots

Screenshots in the docs are produced by the docs image generator in `docs/tools/`; use it rather than committing hand-made images, so they stay consistent.
