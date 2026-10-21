import { installLumen } from './lumen-web'

// Put the web implementation of the desktop preload API on window.lumen before
// any React code runs, so the renderer finds it exactly where it expects.
installLumen()
