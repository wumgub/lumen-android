import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.lumen.android',
  appName: 'Lumen',
  webDir: 'dist',
  android: {
    // Keep the UI painted behind the status bar for the dark, full-bleed look.
    backgroundColor: '#0a0a12'
  }
}

export default config
