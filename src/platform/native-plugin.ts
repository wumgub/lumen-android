import { registerPlugin } from '@capacitor/core'

/**
 * A row as the native MediaStore plugin hands it back. `uri` is a `content://`
 * audio URI and `artUri` a `content://…/albumart/<id>` URI (or ''); both are run
 * through `Capacitor.convertFileSrc` on the JS side so the WebView can load them
 * same-origin (which also keeps the Web Audio analyser un-tainted). Durations are
 * already in seconds and dates in epoch ms to match the Track shape.
 */
export interface NativeTrack {
  id: number
  title: string
  artist: string
  albumArtist: string
  album: string
  albumId: number
  genre: string
  year: number
  trackNo: number
  discNo: number
  duration: number
  addedAt: number
  uri: string
  artUri: string
  hasArt: number
  isVideo: number
}

export interface MediaStorePlugin {
  /** Is the audio-read permission already granted? */
  checkPermission(): Promise<{ granted: boolean }>
  /** Prompt for the audio-read permission; resolves with the outcome. */
  requestPermission(): Promise<{ granted: boolean }>
  /** Enumerate every music track MediaStore knows about. */
  scan(): Promise<{ tracks: NativeTrack[] }>
}

// The name must match @CapacitorPlugin(name = "MediaStore") on the Java side.
export const MediaStore = registerPlugin<MediaStorePlugin>('MediaStore')
