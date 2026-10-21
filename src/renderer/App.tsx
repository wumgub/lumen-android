import React, { useEffect, useState } from 'react'
import { PlayerProvider, usePlayer } from './context/PlayerContext'
import { ThemeProvider, useTheme } from './context/ThemeContext'
import { Sidebar } from './components/Sidebar'
import { LibraryView } from './components/LibraryView'
import { NowPlaying } from './components/NowPlaying'
import { TransportBar } from './components/TransportBar'
import { ThemeSelector } from './components/ThemeSelector'
import { SettingsModal } from './components/SettingsModal'
import { VisualizerHost } from './components/VisualizerHost'
import { VizToggle } from './components/VizToggle'
import {
  Collapse,
  Gear,
  Pause,
  Play,
  Next,
  Prev,
  MusicNote,
  Users,
  Disc,
  ListIcon
} from './components/icons'
import { formatTime } from './lib/format'
import { SearchBox } from './components/SearchBox'
import { VISUALIZER_LIST, isVisualizerId, type VisualizerId } from './visualizers'
import type { View } from './types'

export default function App(): React.ReactElement {
  return (
    <ThemeProvider>
      <PlayerProvider>
        <Shell />
      </PlayerProvider>
    </ThemeProvider>
  )
}

function Shell(): React.ReactElement {
  const [view, setView] = useState<View>({ kind: 'songs' })
  const [search, setSearch] = useState('')
  const [vizId, setVizId] = useState<VisualizerId>(() => {
    const saved = localStorage.getItem('lumen.viz')
    return saved && isVisualizerId(saved) ? saved : 'bars'
  })
  const [vizOn, setVizOn] = useState<boolean>(() => localStorage.getItem('lumen.viz-off') !== '1')
  const [vizFps, setVizFps] = useState<number>(() => {
    const n = Number(localStorage.getItem('lumen.viz-fps'))
    return n === 30 || n === 15 ? n : 0 // 0 = uncapped (native refresh rate)
  })
  const [immersive, setImmersive] = useState(false)
  const [showSettings, setShowSettings] = useState(false)
  const [libraryVersion, setLibraryVersion] = useState(0)
  // Phone layout only: the sidebar slides over as a drawer instead of being a
  // permanent column. No effect on desktop, where the drawer CSS never applies.
  const [drawerOpen, setDrawerOpen] = useState(false)

  const player = usePlayer()
  const { current } = player
  const { setArtwork } = useTheme()

  // Expose the host OS so CSS can reserve traffic-light space only on macOS.
  useEffect(() => {
    document.documentElement.dataset.os = window.lumen.platform
  }, [])

  const chooseViz = (v: VisualizerId): void => {
    setVizId(v)
    localStorage.setItem('lumen.viz', v)
  }
  const toggleViz = (on: boolean): void => {
    setVizOn(on)
    localStorage.setItem('lumen.viz-off', on ? '0' : '1')
  }
  const chooseVizFps = (n: number): void => {
    setVizFps(n)
    localStorage.setItem('lumen.viz-fps', String(n))
  }

  // Feed artwork to the theme engine for adaptive tinting.
  useEffect(() => {
    setArtwork(current && current.hasArt ? window.lumen.artUrl(current.id) : null)
  }, [current, setArtwork])

  // Mirror playback to Discord presence. Only fires on track / play-state /
  // duration changes (not the per-second tick) to stay under Discord's rate limit;
  // the progress bar animates itself from the start/end timestamps.
  useEffect(() => {
    if (!current) {
      void window.lumen.setPresence(null)
      return
    }
    void window.lumen.setPresence({
      title: current.title,
      artist: current.artist,
      album: current.album,
      isPlaying: player.isPlaying,
      position: player.engine.currentTime,
      duration: player.engine.duration
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current?.id, player.isPlaying, player.duration])

  // Keyboard shortcuts.
  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      const tag = (e.target as HTMLElement)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA') return
      if (e.code === 'Space') {
        e.preventDefault()
        player.togglePlay()
      } else if (e.code === 'ArrowRight' && e.metaKey) player.next()
      else if (e.code === 'ArrowLeft' && e.metaKey) player.prev()
      else if (e.key === 'Escape') setImmersive(false)
      else if (e.key === 'f' && !e.metaKey && !e.ctrlKey) setImmersive((v) => !v)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [player])

  // On mobile, navigating from the drawer should close it.
  const navigate = (v: View): void => {
    setView(v)
    setDrawerOpen(false)
  }

  return (
    <div className={`app${drawerOpen ? ' drawer-open' : ''}`}>
      <div className="titlebar">
        <div className="titlebar-drag" />
        <div className="titlebar-brand">
          <span className="brand-dot" />
          Lumen
        </div>
        <SearchBox
          value={search}
          onChange={(v) => {
            setSearch(v)
            if (v && view.kind !== 'songs') setView({ kind: 'songs' })
          }}
        />
        <ThemeSelector />
        <button
          className="titlebar-btn"
          onClick={() => setShowSettings(true)}
          title="Settings"
        >
          <Gear size={18} />
        </button>
      </div>

      <div className="drawer-scrim" onClick={() => setDrawerOpen(false)} />

      <div className="body">
        <Sidebar
          view={view}
          setView={navigate}
          onLibraryChanged={() => setLibraryVersion((v) => v + 1)}
          libraryVersion={libraryVersion}
        />
        <main className="content">
          <LibraryView
            view={view}
            setView={setView}
            search={search}
            libraryVersion={libraryVersion}
            onLibraryChanged={() => setLibraryVersion((v) => v + 1)}
          />
        </main>
        <NowPlaying
          vizId={vizId}
          setVizId={chooseViz}
          vizOn={vizOn}
          setVizOn={toggleViz}
          vizFps={vizFps}
          onImmersive={() => setImmersive(true)}
        />
      </div>

      <TransportBar onExpandNowPlaying={() => setImmersive(true)} />

      <MobileNav view={view} setView={setView} onOpenMenu={() => setDrawerOpen(true)} />

      {immersive && (
        <ImmersiveMode
          vizId={vizId}
          setVizId={chooseViz}
          vizOn={vizOn}
          setVizOn={toggleViz}
          vizFps={vizFps}
          onExit={() => setImmersive(false)}
        />
      )}

      {showSettings && (
        <SettingsModal
          vizFps={vizFps}
          setVizFps={chooseVizFps}
          onClose={() => setShowSettings(false)}
        />
      )}
    </div>
  )
}

// Phone-only bottom tab bar (hidden by CSS on wider screens). The three library
// destinations plus a Menu button that opens the sidebar drawer for playlists,
// folders and rescanning.
function MobileNav({
  view,
  setView,
  onOpenMenu
}: {
  view: View
  setView: (v: View) => void
  onOpenMenu: () => void
}): React.ReactElement {
  const active = (kind: View['kind']): string => (view.kind === kind ? 'active' : '')
  return (
    <nav className="mobile-nav">
      <button className={`mnav-item ${active('songs')}`} onClick={() => setView({ kind: 'songs' })}>
        <MusicNote size={20} />
        <span>Songs</span>
      </button>
      <button
        className={`mnav-item ${active('artists')}`}
        onClick={() => setView({ kind: 'artists' })}
      >
        <Users size={20} />
        <span>Artists</span>
      </button>
      <button className={`mnav-item ${active('albums')}`} onClick={() => setView({ kind: 'albums' })}>
        <Disc size={20} />
        <span>Albums</span>
      </button>
      <button className="mnav-item" onClick={onOpenMenu}>
        <ListIcon size={20} />
        <span>Library</span>
      </button>
    </nav>
  )
}

function ImmersiveMode({
  vizId,
  setVizId,
  vizOn,
  setVizOn,
  vizFps,
  onExit
}: {
  vizId: VisualizerId
  setVizId: (v: VisualizerId) => void
  vizOn: boolean
  setVizOn: (on: boolean) => void
  vizFps: number
  onExit: () => void
}): React.ReactElement {
  const p = usePlayer()
  return (
    <div className="immersive">
      {vizOn ? (
        <VisualizerHost vizId={vizId} fps={vizFps} className="immersive-canvas" />
      ) : (
        <div className="immersive-canvas viz-off">Visualizer off</div>
      )}
      <VizToggle on={vizOn} onChange={setVizOn} className="immersive-toggle" />
      <button className="immersive-exit" onClick={onExit} title="Exit (Esc)">
        <Collapse size={20} />
      </button>
      <div className="immersive-overlay">
        <div className="immersive-meta">
          <div className="im-title">{p.current?.title ?? 'Nothing playing'}</div>
          <div className="im-artist">{p.current?.artist ?? ''}</div>
        </div>
        <div className="im-seek">
          <span className="time">{formatTime(p.currentTime)}</span>
          <input
            className="seek"
            type="range"
            min={0}
            max={p.duration || 0}
            step="0.1"
            value={p.currentTime}
            style={{
              ['--pct' as string]: `${p.duration > 0 ? (p.currentTime / p.duration) * 100 : 0}%`
            }}
            onChange={(e) => p.seek(Number(e.target.value))}
          />
          <span className="time">{formatTime(p.duration)}</span>
        </div>
        <div className="immersive-controls">
          <button className="icon-btn" onClick={p.prev}>
            <Prev size={26} />
          </button>
          <button className="play-btn" onClick={p.togglePlay}>
            {p.isPlaying ? <Pause size={28} /> : <Play size={28} />}
          </button>
          <button className="icon-btn" onClick={p.next}>
            <Next size={26} />
          </button>
        </div>
        {vizOn && (
          <div className="immersive-viz-tabs">
            {VISUALIZER_LIST.map((v) => (
              <button
                key={v.id}
                className={`viz-tab ${v.id === vizId ? 'active' : ''}`}
                onClick={() => setVizId(v.id)}
              >
                {v.label}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
