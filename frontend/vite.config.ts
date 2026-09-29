import react from '@vitejs/plugin-react'
import { configDefaults, defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        // The telephone metadata (libphonenumber-js/max) is large and changes
        // rarely; a stable vendor chunk keeps it out of the application chunk
        // and cacheable across releases. It is still loaded eagerly.
        manualChunks(id) {
          if (id.includes('node_modules/libphonenumber-js')) return 'vendor-libphonenumber'
          return undefined
        },
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
    exclude: [...configDefaults.exclude, 'e2e/**'],
    globals: true,
  },
})
