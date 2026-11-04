# README image tools

Everything under `docs/images/` is generated from the real watch UI with made-up demo data — no device and no real chats.

## 1. Raw screenshots (`docs/images/raw/`)

`watch/app/src/test/java/dev/wearclaude/watch/screenshots/DemoScreenshots.kt` renders the real composables
(Home, Archive, Chat, Dictate in chat-reply mode, Approval, Pair) with Robolectric + [Roborazzi](https://github.com/takahirom/roborazzi)
on a round Wear OS device (`RobolectricDeviceQualifiers.WearOSLargeRound`, 454×454 px). The demo content lives in `DemoData` in the same file.

```sh
cd watch
# Windows (Git Bash): export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew :app:recordRoborazziDebug --tests "*DemoScreenshots*"
```

PNGs are written straight to `docs/images/raw/`. A normal `./gradlew :app:testDebugUnitTest` only renders the screens
(it catches crashes) and does not touch the files.

Notes:
- The tests run on SDK 33: on SDK 34 Robolectric's native graphics can't draw Wear's curved `TimeText`.
- The demo render adds a 14 dp side inset, so text stays inside the round mask, and turns the clock off on the chat screens,
  which scroll to the end the way they do on the watch.

## 2. Framed images, hero and GIF

```sh
pip install pillow          # once
python docs/tools/make_images.py
```

Reads `docs/images/raw/*.png` and writes:

| File | What |
|---|---|
| `home.png`, `archive.png`, `chat.png`, `reply.png`, `sending.png`, `approval.png`, `pair.png` | 600×600 framed watch, transparent background |
| `hero.png` | 1600×800 banner (Segoe UI, falling back to DejaVu or Arial) |
| `reply-flow.gif` | home → chat → dictate → sending → answer, 1.6 s per step, looping |

## 3. Architecture diagram

`docs/images/architecture.svg` is hand-written SVG (1200×620). Edit it directly. All text sits on opaque light boxes,
so it reads on both GitHub's light and dark themes.
