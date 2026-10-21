/**
 * Tiny promise wrapper over IndexedDB — the Android build's persistent store,
 * standing in for the desktop app's SQLite file. Holds imported track metadata,
 * their audio/art blobs, playlists and settings, all on-device in the WebView.
 */

const DB_NAME = 'lumen'
const DB_VERSION = 1

export const STORES = {
  tracks: 'tracks', // key: id (number)         -> Track row
  media: 'media', // key: trackId (number)     -> { trackId, blob }
  art: 'art', // key: trackId (number)         -> { trackId, blob }
  playlists: 'playlists', // key: id (number)  -> Playlist
  playlistTracks: 'playlistTracks', // key: auto -> { playlistId, trackId, pos }
  meta: 'meta' // key: string                  -> arbitrary value (settings, counters, order)
} as const

type StoreName = (typeof STORES)[keyof typeof STORES]

let dbPromise: Promise<IDBDatabase> | null = null

function openDB(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORES.tracks)) {
        db.createObjectStore(STORES.tracks, { keyPath: 'id' })
      }
      if (!db.objectStoreNames.contains(STORES.media)) {
        db.createObjectStore(STORES.media, { keyPath: 'trackId' })
      }
      if (!db.objectStoreNames.contains(STORES.art)) {
        db.createObjectStore(STORES.art, { keyPath: 'trackId' })
      }
      if (!db.objectStoreNames.contains(STORES.playlists)) {
        db.createObjectStore(STORES.playlists, { keyPath: 'id' })
      }
      if (!db.objectStoreNames.contains(STORES.playlistTracks)) {
        const s = db.createObjectStore(STORES.playlistTracks, {
          keyPath: 'rowId',
          autoIncrement: true
        })
        s.createIndex('byPlaylist', 'playlistId', { unique: false })
      }
      if (!db.objectStoreNames.contains(STORES.meta)) {
        db.createObjectStore(STORES.meta, { keyPath: 'key' })
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
  return dbPromise
}

function tx<T>(
  store: StoreName,
  mode: IDBTransactionMode,
  run: (s: IDBObjectStore) => IDBRequest<T>
): Promise<T> {
  return openDB().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const t = db.transaction(store, mode)
        const req = run(t.objectStore(store))
        req.onsuccess = () => resolve(req.result)
        req.onerror = () => reject(req.error)
      })
  )
}

export function idbGet<T>(store: StoreName, key: IDBValidKey): Promise<T | undefined> {
  return tx<T | undefined>(store, 'readonly', (s) => s.get(key) as IDBRequest<T | undefined>)
}

export function idbGetAll<T>(store: StoreName): Promise<T[]> {
  return tx<T[]>(store, 'readonly', (s) => s.getAll() as IDBRequest<T[]>)
}

export function idbPut<T>(store: StoreName, value: T): Promise<IDBValidKey> {
  return tx<IDBValidKey>(store, 'readwrite', (s) => s.put(value as unknown as object))
}

export function idbDelete(store: StoreName, key: IDBValidKey): Promise<void> {
  return tx<undefined>(store, 'readwrite', (s) => s.delete(key) as IDBRequest<undefined>).then(
    () => undefined
  )
}

export function idbClear(store: StoreName): Promise<void> {
  return tx<undefined>(store, 'readwrite', (s) => s.clear() as IDBRequest<undefined>).then(
    () => undefined
  )
}

/** Put many values in one transaction (used when importing a batch of tracks). */
export function idbPutMany<T>(store: StoreName, values: T[]): Promise<void> {
  return openDB().then(
    (db) =>
      new Promise<void>((resolve, reject) => {
        const t = db.transaction(store, 'readwrite')
        const os = t.objectStore(store)
        for (const v of values) os.put(v as unknown as object)
        t.oncomplete = () => resolve()
        t.onerror = () => reject(t.error)
        t.onabort = () => reject(t.error)
      })
  )
}

/** Rows of playlistTracks for one playlist (via the byPlaylist index). */
export function idbPlaylistRows<T>(playlistId: number): Promise<T[]> {
  return openDB().then(
    (db) =>
      new Promise<T[]>((resolve, reject) => {
        const t = db.transaction(STORES.playlistTracks, 'readonly')
        const idx = t.objectStore(STORES.playlistTracks).index('byPlaylist')
        const req = idx.getAll(IDBKeyRange.only(playlistId)) as IDBRequest<T[]>
        req.onsuccess = () => resolve(req.result)
        req.onerror = () => reject(req.error)
      })
  )
}

/** Meta helpers — single-row key/value for settings, counters and ordering. */
export async function metaGet<T>(key: string, fallback: T): Promise<T> {
  const row = await idbGet<{ key: string; value: T }>(STORES.meta, key)
  return row ? row.value : fallback
}

export function metaSet<T>(key: string, value: T): Promise<IDBValidKey> {
  return idbPut(STORES.meta, { key, value })
}
