# Lumen for Android

This is the Android version of [Lumen](https://github.com/wumgub/lumen-player), my
music player with the reactive visualizers. It's a native app written in Kotlin
with Jetpack Compose, and it plays your music through Media3, so it keeps going in
the background with the usual lockscreen and notification controls.

Up to v0.3.2 this was the desktop app's React interface running in a WebView. That
worked, but it never felt as quick as a real Android player, so from v0.4.0 it's
native. It installs over the old WebView app as a normal update, and your playlists,
stats and edits come across on first launch.

## What's in it

- Your phone's music library, read straight from the system (no importing or
  copying files)
- Songs, artists, albums and playlists, with search (it also searches your notes,
  and ignores accents, so "елка" finds "Ёлка" and "beyonce" finds "Beyoncé")
- A queue: play next, add to queue, jump around in it or drop songs, plus shuffle and
  repeat
- Background playback with lockscreen and notification controls, and volume up to 150%
- Edit song: fix a song's title, artist or album, or give it a note, just for that song.
  Renaming an artist or album from its own page changes every song on it
- Album covers: pick an image, borrow another song's art, or reset it
- Listening stats: top songs and artists, and how much you've listened each week
- The visualizers (bars, waveform, radial), small on the player or full screen
- All the Lumen themes (Neon, Flashbang, Minimal Dark, Retro, Glass and Blackout)
  plus the custom theme editor, using the same theme files as the desktop app

## Installing

Grab the latest `Lumen-*.apk` from the
[releases page](https://github.com/wumgub/lumen-android/releases/latest) and open it
on the phone. Android warns about installing apps from outside the Play Store, and
since it isn't signed with a paid developer certificate you might get an extra
"unknown app" prompt too. Both are normal for a sideloaded build.

It needs Android 8.0 or newer.

## Building it

No local Android SDK needed. Every push to `main` builds the APK in GitHub Actions
(grab it from the run's artifacts), and pushing a version tag attaches it to that
release:

```bash
git tag v0.4.0
git push --follow-tags
```

## License

GPL-3.0-or-later, same as the rest of Lumen.
