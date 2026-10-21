import { parseBlob } from 'music-metadata'
import { isVideoPath } from '@shared/types'

export interface ParsedMeta {
  title: string
  artist: string
  albumArtist: string
  album: string
  genre: string
  year: number | null
  trackNo: number | null
  discNo: number | null
  duration: number
  isVideo: number
  art: Blob | null
}

/** Strip the extension from a filename, for use as a fallback title. */
function baseName(name: string): string {
  const slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'))
  const file = slash >= 0 ? name.slice(slash + 1) : name
  const dot = file.lastIndexOf('.')
  return dot > 0 ? file.slice(0, dot) : file
}

/**
 * Read tags + duration from an imported file. Falls back to the filename for the
 * title and leaves other fields blank if the file has no usable tags, so a track
 * always imports even when parsing is partial.
 */
export async function parseFile(file: File): Promise<ParsedMeta> {
  const fallbackTitle = baseName(file.name)
  const base: ParsedMeta = {
    title: fallbackTitle,
    artist: '',
    albumArtist: '',
    album: '',
    genre: '',
    year: null,
    trackNo: null,
    discNo: null,
    duration: 0,
    isVideo: isVideoPath(file.name) ? 1 : 0,
    art: null
  }

  try {
    const { common, format } = await parseBlob(file, { duration: true })
    const pic = common.picture && common.picture[0]
    return {
      title: common.title?.trim() || fallbackTitle,
      artist: common.artist?.trim() || '',
      albumArtist: common.albumartist?.trim() || common.artist?.trim() || '',
      album: common.album?.trim() || '',
      genre: common.genre?.length ? common.genre.join(', ') : '',
      year: typeof common.year === 'number' ? common.year : null,
      trackNo: common.track?.no ?? null,
      discNo: common.disk?.no ?? null,
      duration: format.duration ? Math.round(format.duration) : 0,
      isVideo: base.isVideo,
      art: pic ? new Blob([pic.data as BlobPart], { type: pic.format || 'image/jpeg' }) : null
    }
  } catch {
    return base
  }
}
