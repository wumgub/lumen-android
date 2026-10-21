import type {
  AppInfo,
  AppSettings,
  LibraryQuery,
  Playlist,
  PlaylistWithCount,
  ScanProgress,
  ScanResult,
  Track,
  UpdateCheck
} from '@shared/types'
import { MEDIA_EXTENSIONS } from '@shared/types'
import type { LumenApi } from './types'
import {
  STORES,
  idbDelete,
  idbGetAll,
  idbPlaylistRows,
  idbPut,
  idbPutMany,
  metaGet,
  metaSet
} from './db'
import { parseFile } from './metadata'

const APP_VERSION = '0.1.4'
const REPO = 'wumgub/lumen-android'

const DEFAULT_SETTINGS: AppSettings = {
  discordEnabled: false,
  discordClientId: '',
  discordClientSecret: '',
  discordLargeImageKey: '',
  discordCoverArt: false
}

interface PlaylistRow {
  rowId?: number
  playlistId: number
  trackId: number
  pos: number
}

// ---- in-memory mirror of the on-device store, so the sync bits of the API
// (mediaUrl/artUrl) can answer immediately and queries stay snappy. ----
const tracks = new Map<number, Track>()
const mediaBlobs = new Map<number, Blob>()
const artBlobs = new Map<number, Blob>()
const mediaUrls = new Map<number, string>()
const artUrls = new Map<number, string>()

interface AlbumCoverRow {
  album: string
  source: 'track' | 'custom'
  trackId?: number
  blob?: Blob
}
const albumCovers = new Map<string, AlbumCoverRow>()
const albumUrls = new Map<string, string>() // album -> object URL (cache)

let folders: string[] = []
let playlistOrder: number[] = []
let settings: AppSettings = { ...DEFAULT_SETTINGS }
let nextTrackId = 1
let nextPlaylistId = 1

// Files chosen by pickFolder(), consumed by the next scan() — mirrors the
// desktop "add folder, then scan it" flow, except the WebView can only read
// what the user just handed us through the file picker.
let pendingImport: File[] = []
let pendingLabel = ''

const scanListeners = new Set<(p: ScanProgress) => void>()
function emitScan(p: ScanProgress): void {
  for (const l of scanListeners) l(p)
}

let ready: Promise<void> | null = null
function init(): Promise<void> {
  if (ready) return ready
  ready = (async () => {
    const [trackRows, mediaRows, artRows] = await Promise.all([
      idbGetAll<Track>(STORES.tracks),
      idbGetAll<{ trackId: number; blob: Blob }>(STORES.media),
      idbGetAll<{ trackId: number; blob: Blob }>(STORES.art)
    ])
    for (const t of trackRows) tracks.set(t.id, t)
    for (const m of mediaRows) mediaBlobs.set(m.trackId, m.blob)
    for (const a of artRows) artBlobs.set(a.trackId, a.blob)

    const coverRows = await idbGetAll<AlbumCoverRow>(STORES.albumCovers)
    for (const c of coverRows) albumCovers.set(c.album, c)

    folders = await metaGet<string[]>('folders', [])
    playlistOrder = await metaGet<number[]>('playlistOrder', [])
    settings = { ...DEFAULT_SETTINGS, ...(await metaGet<Partial<AppSettings>>('settings', {})) }
    nextTrackId = Math.max(0, ...trackRows.map((t) => t.id)) + 1
    const pls = await idbGetAll<Playlist>(STORES.playlists)
    nextPlaylistId = Math.max(0, ...pls.map((p) => p.id)) + 1
    // Any playlists missing from the saved order get appended.
    for (const p of pls) if (!playlistOrder.includes(p.id)) playlistOrder.push(p.id)
  })()
  return ready
}

function ext(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot >= 0 ? name.slice(dot).toLowerCase() : ''
}

function isMedia(name: string): boolean {
  return (MEDIA_EXTENSIONS as readonly string[]).includes(ext(name))
}

// ---- open a file picker and stash what the user chose ----
function openPicker(): Promise<File[]> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.multiple = true
    input.accept = 'audio/*,video/*'
    // Progressive enhancement: on WebViews that honour it, let the user grab a
    // whole folder at once. Android's picker falls back to multi-file select.
    input.setAttribute('webkitdirectory', '')
    input.setAttribute('directory', '')
    let settled = false
    const done = (files: File[]): void => {
      if (settled) return
      settled = true
      resolve(files)
    }
    input.onchange = () => done(input.files ? Array.from(input.files) : [])
    // If the picker is dismissed, resolve empty so the UI doesn't hang.
    window.addEventListener('focus', () => setTimeout(() => done([]), 500), { once: true })
    input.click()
  })
}

function folderLabelFor(files: File[]): string {
  const rel = files.find((f) => f.webkitRelativePath)?.webkitRelativePath
  if (rel) return rel.split('/')[0]
  return `Imported ${new Date().toLocaleDateString()}`
}

function urlFor(map: Map<number, string>, blobs: Map<number, Blob>, id: number): string {
  const existing = map.get(id)
  if (existing) return existing
  const blob = blobs.get(id)
  if (!blob) return ''
  const url = URL.createObjectURL(blob)
  map.set(id, url)
  return url
}

/** The art blob representing an album: an override if set, else its first
 * art-bearing track (by disc/track number). */
function resolveAlbumBlob(album: string): Blob | null {
  const ov = albumCovers.get(album)
  if (ov) {
    if (ov.source === 'custom' && ov.blob) return ov.blob
    if (ov.source === 'track' && ov.trackId != null) return artBlobs.get(ov.trackId) ?? null
  }
  let best: Track | null = null
  for (const t of tracks.values()) {
    if (t.album !== album || !t.hasArt) continue
    if (
      !best ||
      (t.discNo ?? 0) - (best.discNo ?? 0) < 0 ||
      ((t.discNo ?? 0) === (best.discNo ?? 0) &&
        ((t.trackNo ?? 0) - (best.trackNo ?? 0) < 0 ||
          ((t.trackNo ?? 0) === (best.trackNo ?? 0) && t.id < best.id)))
    ) {
      best = t
    }
  }
  return best ? artBlobs.get(best.id) ?? null : null
}

/** Drop a cached album cover URL so the next render rebuilds it from current data. */
function invalidateAlbum(album: string): void {
  const u = albumUrls.get(album)
  if (u) URL.revokeObjectURL(u)
  albumUrls.delete(album)
}

/** Drop all cached album cover URLs (after an import that may shift defaults). */
function invalidateAllAlbums(): void {
  for (const u of albumUrls.values()) URL.revokeObjectURL(u)
  albumUrls.clear()
}

/** Open a single-image file picker; resolves the chosen file or null if cancelled. */
function pickImage(): Promise<Blob | null> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'image/*'
    let settled = false
    const done = (b: Blob | null): void => {
      if (settled) return
      settled = true
      resolve(b)
    }
    input.onchange = () => done(input.files && input.files[0] ? input.files[0] : null)
    window.addEventListener('focus', () => setTimeout(() => done(null), 500), { once: true })
    input.click()
  })
}

// ---- querying / aggregation over the in-memory track mirror ----
function matches(t: Track, q: LibraryQuery): boolean {
  if (q.artist && t.artist !== q.artist && t.albumArtist !== q.artist) return false
  if (q.album && t.album !== q.album) return false
  if (q.genre && t.genre !== q.genre) return false
  if (q.folder && !t.path.startsWith(q.folder)) return false
  if (q.search) {
    const s = q.search.toLowerCase()
    const hay = `${t.title} ${t.artist} ${t.albumArtist} ${t.album} ${t.genre} ${t.note}`.toLowerCase()
    if (!hay.includes(s)) return false
  }
  return true
}

function compare(a: Track, b: Track, sort: LibraryQuery['sort']): number {
  switch (sort) {
    case 'artist':
      return a.artist.localeCompare(b.artist) || a.album.localeCompare(b.album) || (a.trackNo ?? 0) - (b.trackNo ?? 0)
    case 'album':
      return a.album.localeCompare(b.album) || (a.discNo ?? 0) - (b.discNo ?? 0) || (a.trackNo ?? 0) - (b.trackNo ?? 0)
    case 'year':
      return (a.year ?? 0) - (b.year ?? 0)
    case 'duration':
      return a.duration - b.duration
    case 'addedAt':
      return a.addedAt - b.addedAt
    case 'title':
    default:
      return a.title.localeCompare(b.title)
  }
}

async function persistTrack(t: Track): Promise<void> {
  tracks.set(t.id, t)
  await idbPut(STORES.tracks, t)
}

const api: LumenApi = {
  platform: 'android',

  async appInfo(): Promise<AppInfo> {
    return { version: APP_VERSION, commit: __APP_COMMIT__, buildDate: __APP_BUILD_DATE__ }
  },

  async checkForUpdate(): Promise<UpdateCheck> {
    const url = `https://github.com/${REPO}/releases/latest`
    try {
      const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = (await res.json()) as { tag_name?: string; published_at?: string }
      const latest = (data.tag_name || '').replace(/^v/, '')
      const current = __APP_COMMIT__
      return {
        ok: true,
        current,
        latest: latest || 'unknown',
        date: data.published_at ?? null,
        message: latest ? `Latest release: ${latest}` : 'No releases yet',
        upToDate: true,
        url
      }
    } catch (e) {
      return { ok: false, error: e instanceof Error ? e.message : String(e), url }
    }
  },

  async pickFolder(): Promise<string[]> {
    await init()
    const files = (await openPicker()).filter((f) => isMedia(f.name))
    if (files.length === 0) return []
    pendingImport = files
    pendingLabel = folderLabelFor(files)
    return [pendingLabel]
  },

  async folders(): Promise<string[]> {
    await init()
    return [...folders]
  },

  async addFolder(p: string): Promise<void> {
    await init()
    if (!folders.includes(p)) {
      folders.push(p)
      await metaSet('folders', folders)
    }
  },

  async removeFolder(p: string): Promise<void> {
    await init()
    folders = folders.filter((f) => f !== p)
    await metaSet('folders', folders)
    // Drop tracks that came from that folder.
    const doomed = [...tracks.values()].filter((t) => t.path.startsWith(p))
    for (const t of doomed) {
      tracks.delete(t.id)
      mediaBlobs.delete(t.id)
      artBlobs.delete(t.id)
      await idbDelete(STORES.tracks, t.id)
      await idbDelete(STORES.media, t.id)
      await idbDelete(STORES.art, t.id)
    }
    invalidateAllAlbums()
  },

  async scan(): Promise<ScanResult> {
    await init()
    const files = pendingImport
    pendingImport = []
    const label = pendingLabel || folderLabelFor(files)
    emitScan({ phase: 'discovering', processed: 0, total: files.length })

    const newTracks: Track[] = []
    const newMedia: { trackId: number; blob: Blob }[] = []
    const newArt: { trackId: number; blob: Blob }[] = []
    let added = 0

    for (let i = 0; i < files.length; i++) {
      const file = files[i]
      emitScan({ phase: 'parsing', processed: i, total: files.length, current: file.name })
      const meta = await parseFile(file)
      const id = nextTrackId++
      const path = `${label}/${file.webkitRelativePath || file.name}`
      const track: Track = {
        id,
        path,
        title: meta.title,
        artist: meta.artist,
        albumArtist: meta.albumArtist,
        album: meta.album,
        genre: meta.genre,
        year: meta.year,
        trackNo: meta.trackNo,
        discNo: meta.discNo,
        duration: meta.duration,
        hasArt: meta.art ? 1 : 0,
        addedAt: Date.now(),
        note: '',
        isVideo: meta.isVideo
      }
      newTracks.push(track)
      newMedia.push({ trackId: id, blob: file })
      if (meta.art) newArt.push({ trackId: id, blob: meta.art })
      tracks.set(id, track)
      mediaBlobs.set(id, file)
      if (meta.art) artBlobs.set(id, meta.art)
      added++
    }

    await idbPutMany(STORES.tracks, newTracks)
    await idbPutMany(STORES.media, newMedia)
    await idbPutMany(STORES.art, newArt)
    if (label && !folders.includes(label)) {
      folders.push(label)
      await metaSet('folders', folders)
    }
    invalidateAllAlbums() // new tracks may change an album's default cover
    emitScan({ phase: 'done', processed: files.length, total: files.length })
    return { added, updated: 0, total: tracks.size }
  },

  onScanProgress(cb: (p: ScanProgress) => void): () => void {
    scanListeners.add(cb)
    return () => scanListeners.delete(cb)
  },

  async query(q: LibraryQuery): Promise<Track[]> {
    await init()
    let rows = [...tracks.values()].filter((t) => matches(t, q))
    if (q.sort === 'custom' && q.album) {
      const order = await metaGet<number[]>(`albumOrder:${q.album}`, [])
      const rank = new Map(order.map((id, i) => [id, i]))
      rows.sort((a, b) => (rank.get(a.id) ?? 1e9) - (rank.get(b.id) ?? 1e9) || (a.trackNo ?? 0) - (b.trackNo ?? 0))
    } else {
      rows.sort((a, b) => compare(a, b, q.sort))
      if (q.dir === 'desc') rows.reverse()
    }
    const offset = q.offset ?? 0
    const limit = q.limit ?? rows.length
    return rows.slice(offset, offset + limit)
  },

  async artists(): Promise<{ artist: string; count: number }[]> {
    await init()
    const counts = new Map<string, number>()
    for (const t of tracks.values()) {
      const key = t.albumArtist || t.artist
      counts.set(key, (counts.get(key) ?? 0) + 1)
    }
    return [...counts.entries()]
      .map(([artist, count]) => ({ artist, count }))
      .sort((a, b) => a.artist.localeCompare(b.artist))
  },

  async albums(): Promise<{ album: string; artist: string; count: number }[]> {
    await init()
    const map = new Map<string, { album: string; artist: string; count: number }>()
    for (const t of tracks.values()) {
      const key = `${t.album}\u0000${t.albumArtist || t.artist}`
      const cur = map.get(key)
      if (cur) cur.count++
      else map.set(key, { album: t.album, artist: t.albumArtist || t.artist, count: 1 })
    }
    return [...map.values()].sort((a, b) => a.album.localeCompare(b.album))
  },

  async stats(): Promise<{ tracks: number; artists: number; albums: number }> {
    await init()
    const artists = new Set<string>()
    const albums = new Set<string>()
    for (const t of tracks.values()) {
      artists.add(t.albumArtist || t.artist)
      albums.add(t.album)
    }
    return { tracks: tracks.size, artists: artists.size, albums: albums.size }
  },

  async getTrack(id: number): Promise<Track | null> {
    await init()
    return tracks.get(id) ?? null
  },

  async revealTrack(): Promise<void> {
    // No file manager to reveal into on Android; nothing to do.
  },

  async renameTrack(id: number, title: string): Promise<void> {
    await init()
    const t = tracks.get(id)
    if (t) await persistTrack({ ...t, title })
  },

  async renameArtist(oldName: string, newName: string): Promise<void> {
    await init()
    for (const t of [...tracks.values()]) {
      if (t.artist === oldName || t.albumArtist === oldName) {
        await persistTrack({
          ...t,
          artist: t.artist === oldName ? newName : t.artist,
          albumArtist: t.albumArtist === oldName ? newName : t.albumArtist
        })
      }
    }
  },

  async renameAlbum(oldName: string, newName: string): Promise<void> {
    await init()
    for (const t of [...tracks.values()]) {
      if (t.album === oldName) await persistTrack({ ...t, album: newName })
    }
  },

  async setTrackAlbum(trackIds: number[], album: string): Promise<void> {
    await init()
    for (const id of trackIds) {
      const t = tracks.get(id)
      if (t) await persistTrack({ ...t, album })
    }
  },

  async setTrackNote(id: number, note: string): Promise<void> {
    await init()
    const t = tracks.get(id)
    if (t) await persistTrack({ ...t, note })
  },

  async reorderAlbum(album: string, orderedTrackIds: number[]): Promise<void> {
    await init()
    await metaSet(`albumOrder:${album}`, orderedTrackIds)
  },

  async playlists(): Promise<PlaylistWithCount[]> {
    await init()
    const pls = await idbGetAll<Playlist>(STORES.playlists)
    const byId = new Map(pls.map((p) => [p.id, p]))
    const rows = await idbGetAll<PlaylistRow>(STORES.playlistTracks)
    const counts = new Map<number, number>()
    for (const r of rows) counts.set(r.playlistId, (counts.get(r.playlistId) ?? 0) + 1)
    return playlistOrder
      .map((id) => byId.get(id))
      .filter((p): p is Playlist => !!p)
      .map((p) => ({ ...p, count: counts.get(p.id) ?? 0 }))
  },

  async createPlaylist(name: string): Promise<Playlist> {
    await init()
    const pl: Playlist = { id: nextPlaylistId++, name, createdAt: Date.now() }
    await idbPut(STORES.playlists, pl)
    playlistOrder.push(pl.id)
    await metaSet('playlistOrder', playlistOrder)
    return pl
  },

  async deletePlaylist(id: number): Promise<void> {
    await init()
    await idbDelete(STORES.playlists, id)
    const rows = await idbPlaylistRows<PlaylistRow>(id)
    for (const r of rows) if (r.rowId != null) await idbDelete(STORES.playlistTracks, r.rowId)
    playlistOrder = playlistOrder.filter((x) => x !== id)
    await metaSet('playlistOrder', playlistOrder)
  },

  async renamePlaylist(id: number, name: string): Promise<void> {
    await init()
    const pls = await idbGetAll<Playlist>(STORES.playlists)
    const pl = pls.find((p) => p.id === id)
    if (pl) await idbPut(STORES.playlists, { ...pl, name })
  },

  async reorderPlaylists(orderedIds: number[]): Promise<void> {
    await init()
    playlistOrder = orderedIds
    await metaSet('playlistOrder', playlistOrder)
  },

  async addToPlaylist(id: number, trackIds: number[]): Promise<void> {
    await init()
    const rows = await idbPlaylistRows<PlaylistRow>(id)
    let pos = rows.reduce((m, r) => Math.max(m, r.pos), -1) + 1
    for (const trackId of trackIds) {
      await idbPut(STORES.playlistTracks, { playlistId: id, trackId, pos: pos++ })
    }
  },

  async removeFromPlaylist(id: number, trackId: number): Promise<void> {
    await init()
    const rows = await idbPlaylistRows<PlaylistRow>(id)
    for (const r of rows) {
      if (r.trackId === trackId && r.rowId != null) await idbDelete(STORES.playlistTracks, r.rowId)
    }
  },

  async playlistTracks(id: number): Promise<Track[]> {
    await init()
    const rows = (await idbPlaylistRows<PlaylistRow>(id)).sort((a, b) => a.pos - b.pos)
    return rows.map((r) => tracks.get(r.trackId)).filter((t): t is Track => !!t)
  },

  async getSettings(): Promise<AppSettings> {
    await init()
    return { ...settings }
  },

  async setSettings(patch: Partial<AppSettings>): Promise<AppSettings> {
    await init()
    settings = { ...settings, ...patch }
    await metaSet('settings', settings)
    return { ...settings }
  },

  async setPresence(): Promise<void> {
    // Discord Rich Presence is desktop-only; no-op on Android.
  },

  mediaUrl(trackId: number): string {
    return urlFor(mediaUrls, mediaBlobs, trackId)
  },

  artUrl(trackId: number): string {
    return urlFor(artUrls, artBlobs, trackId)
  },

  albumArtUrl(album: string): string {
    const cached = albumUrls.get(album)
    if (cached) return cached
    const blob = resolveAlbumBlob(album)
    if (!blob) return ''
    const url = URL.createObjectURL(blob)
    albumUrls.set(album, url)
    return url
  },

  async setAlbumCoverFromTrack(album: string, trackId: number): Promise<void> {
    await init()
    const row: AlbumCoverRow = { album, source: 'track', trackId }
    albumCovers.set(album, row)
    await idbPut(STORES.albumCovers, row)
    invalidateAlbum(album)
  },

  async setAlbumCoverCustom(album: string): Promise<boolean> {
    await init()
    const blob = await pickImage()
    if (!blob) return false
    const row: AlbumCoverRow = { album, source: 'custom', blob }
    albumCovers.set(album, row)
    await idbPut(STORES.albumCovers, row)
    invalidateAlbum(album)
    return true
  },

  async clearAlbumCover(album: string): Promise<void> {
    await init()
    albumCovers.delete(album)
    await idbDelete(STORES.albumCovers, album)
    invalidateAlbum(album)
  },

  async albumHasCover(album: string): Promise<boolean> {
    await init()
    return albumCovers.has(album)
  }
}

export function installLumen(): void {
  window.lumen = api
  void init()
}
