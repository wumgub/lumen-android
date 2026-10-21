# Lumen for Android

An Android build of [Lumen](https://github.com/wumgub/lumen-player) — the music
player with reactive audio visualizers. Same React UI as the desktop app, wrapped
in a WebView with [Capacitor](https://capacitorjs.com/) so it builds to a real,
sideloadable `.apk`.

> **Targets Android 12+.** This is tuned for a modern WebView. It is *not* aimed
> at ancient devices — the desktop app is the one for everything else.

## Screenshots

<p>
  <img src="docs/screenshots/library.png" width="210" alt="Song library with bottom tab bar and mini-player" />
  <img src="docs/screenshots/nowplaying.png" width="210" alt="Full-screen now-playing sheet with visualizer" />
  <img src="docs/screenshots/albums.png" width="210" alt="Albums grid" />
  <img src="docs/screenshots/library-menu.png" width="210" alt="Library drawer with playlists and folders" />
</p>

## Requirements & recommended specs

The player itself is light. The audio **visualizer** is the demanding part — it
runs a continuous Web Audio analyser plus a canvas animation — so that's what the
specs below are really about.

| | Minimum | Recommended |
| --- | --- | --- |
| Android | 12 (API 31) | 13 or newer |
| RAM | 3 GB | 4 GB+ |
| Chipset | any ~2019+ mid-range | recent mid-range or better |
| Free storage | ≈ the size of the music you import, + ~200 MB | comfortable headroom |
| WebView | stock, up to date | — |

**Storage note:** importing **copies** each track into the app's own on-device
storage (IndexedDB), rather than streaming it from where it sits. So budget free
space roughly equal to the music you add. (A future MediaStore-backed library
will read files in place and remove this cost.)

**On a weaker or older phone:** turn the visualizer **off** (the toggle on the
now-playing sheet) or cap it to **30 or 15 FPS** in Settings. Everything else —
browsing, playback, playlists — stays light either way.

## Install

1. Grab the latest `Lumen-*.apk` from the [Releases page](https://github.com/wumgub/lumen-android/releases/latest).
2. Copy it to the phone (or download it directly on the phone).
3. Open it. Android will ask you to allow installing from this source — say yes.
4. Launch Lumen, tap to add music, and pick your audio files.

The APK isn't on the Play Store and isn't signed by a paid developer cert, so
Android shows the usual "unknown app" warning. That's expected for a sideloaded
build.

## How it differs from desktop

The desktop app runs a Node process (SQLite, filesystem scan, Discord Rich
Presence). None of that exists on Android, so the backend is reimplemented on
web APIs:

| Desktop | Android build |
| --- | --- |
| SQLite library file | IndexedDB, on-device in the WebView |
| Filesystem folder scan | Pick audio files through Android's file picker |
| `music-metadata` (Node) | `music-metadata` browser build — tags + art read in-app |
| `lumen-media://` / `lumen-art://` | `blob:` object URLs built from the imported files |
| Discord Rich Presence | Not available — it's a no-op |
| Tag editing writes to files | Stored as in-app overrides (your files aren't modified) |

### Known limits (v1)

- **Importing is file-picker based**, not a live folder watch. Pick your tracks;
  a full MediaStore-backed library (browse by folder, auto-pick-up new music) is
  the next step.
- **Battery / performance:** the visualizer runs the Web Audio analyser + canvas
  continuously. Fine on a modern phone; it is not a low-power design.
- **Old WebViews:** if the UI looks broken on a non-stock ROM, update *Android
  System WebView* from the Play Store / F-Droid.

## Building

You don't need a local Android SDK — the APK is built in GitHub Actions. Push a
version tag and the [workflow](.github/workflows/release.yml) builds the APK and
attaches it to the matching Release:

```bash
git tag v0.1.0
git push --follow-tags
```

Or trigger it by hand from the **Actions** tab to get an APK artifact without
publishing a release.

### Running the UI locally (no Android)

```bash
npm install
npm run dev      # opens the player in a desktop browser
```

The same web backend runs in the browser, so you can develop the UI without a
device. `npm run build` produces the `dist/` bundle Capacitor ships.

## License

GPL-3.0-or-later, same as the desktop app.
