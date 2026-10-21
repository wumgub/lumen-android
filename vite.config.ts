import { resolve } from 'path'
import { execSync } from 'child_process'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Stamp the build with the commit it came from (package.json's semver isn't
// bumped per change), mirroring how the desktop app reports its version.
const APP_COMMIT = (() => {
  try {
    return execSync('git rev-parse --short HEAD').toString().trim()
  } catch {
    return 'unknown'
  }
})()
const APP_BUILD_DATE = new Date().toISOString()

export default defineConfig({
  // Relative asset paths so the bundle loads under Capacitor's local server
  // (https://localhost) as well as a plain file:// preview.
  base: '',
  resolve: {
    alias: {
      '@renderer': resolve(__dirname, 'src/renderer'),
      '@shared': resolve(__dirname, 'src/shared'),
      '@platform': resolve(__dirname, 'src/platform')
    }
  },
  define: {
    __APP_COMMIT__: JSON.stringify(APP_COMMIT),
    __APP_BUILD_DATE__: JSON.stringify(APP_BUILD_DATE)
  },
  build: {
    outDir: 'dist',
    // Android 13's WebView is modern Chromium, so a current target is fine.
    target: 'es2020',
    emptyOutDir: true
  },
  plugins: [react()]
})
