import type {
  AppInfo,
  AppSettings,
  LibraryQuery,
  Playlist,
  PlaylistWithCount,
  PresencePayload,
  ScanProgress,
  ScanResult,
  Track,
  UpdateCheck
} from '@shared/types'

/**
 * The surface the React renderer calls through `window.lumen`. On desktop this
 * is backed by Electron IPC into Node; here it's reimplemented on web APIs
 * (IndexedDB, the Web Audio graph, imported File blobs). The shape must match
 * the desktop preload exactly so the UI needs no changes.
 */
export interface LumenApi {
  platform: string
  appInfo(): Promise<AppInfo>
  checkForUpdate(): Promise<UpdateCheck>

  pickFolder(): Promise<string[]>
  folders(): Promise<string[]>
  addFolder(p: string): Promise<void>
  removeFolder(p: string): Promise<void>
  scan(): Promise<ScanResult>
  onScanProgress(cb: (p: ScanProgress) => void): () => void

  query(q: LibraryQuery): Promise<Track[]>
  artists(): Promise<{ artist: string; count: number }[]>
  albums(): Promise<{ album: string; artist: string; count: number }[]>
  stats(): Promise<{ tracks: number; artists: number; albums: number }>
  getTrack(id: number): Promise<Track | null>
  revealTrack(id: number): Promise<void>

  renameTrack(id: number, title: string): Promise<void>
  renameArtist(oldName: string, newName: string): Promise<void>
  renameAlbum(oldName: string, newName: string): Promise<void>
  setTrackAlbum(trackIds: number[], album: string): Promise<void>
  setTrackNote(id: number, note: string): Promise<void>
  reorderAlbum(album: string, orderedTrackIds: number[]): Promise<void>

  playlists(): Promise<PlaylistWithCount[]>
  createPlaylist(name: string): Promise<Playlist>
  deletePlaylist(id: number): Promise<void>
  renamePlaylist(id: number, name: string): Promise<void>
  reorderPlaylists(orderedIds: number[]): Promise<void>
  addToPlaylist(id: number, trackIds: number[]): Promise<void>
  removeFromPlaylist(id: number, trackId: number): Promise<void>
  playlistTracks(id: number): Promise<Track[]>

  getSettings(): Promise<AppSettings>
  setSettings(patch: Partial<AppSettings>): Promise<AppSettings>
  setPresence(payload: PresencePayload | null): Promise<void>

  mediaUrl(trackId: number): string
  artUrl(trackId: number): string
}

declare global {
  interface Window {
    lumen: LumenApi
  }
}
