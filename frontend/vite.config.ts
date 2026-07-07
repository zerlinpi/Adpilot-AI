import { defineConfig } from 'vite'
import path from 'path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'


function figmaAssetResolver() {
  return {
    name: 'figma-asset-resolver',
    resolveId(id) {
      if (id.startsWith('figma:asset/')) {
        const filename = id.replace('figma:asset/', '')
        return path.resolve(__dirname, 'src/assets', filename)
      }
    },
  }
}

export default defineConfig({
  plugins: [
    figmaAssetResolver(),
    // The React and Tailwind plugins are both required for Make, even if
    // Tailwind is not being actively used – do not remove them
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      // Alias @ to the src directory
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // Local dev proxies /api to the backend running on localhost:8090.
        // Override with the VITE_DEV_API_TARGET env var to point at a remote backend.
        target: process.env.VITE_DEV_API_TARGET || 'http://localhost:8090',
        changeOrigin: true,
        secure: false,
      },
    },
  },
  // File types to support raw imports. Never add .css, .tsx, or .ts files to this.
  assetsInclude: ['**/*.svg', '**/*.csv'],
  build: {
    // App code is split per major dependency below; raise the per-chunk warning
    // limit so the remaining warnings flag genuinely oversized chunks.
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // Split heavy, rarely-changing third-party libraries into their own
        // long-cacheable vendor chunks so the initial app bundle is much smaller
        // and a dependency bump only invalidates its own chunk (not all of app).
        // Chunking strategy — correctness first.
        //
        // React must NEVER be split into a different chunk from the libraries
        // that consume it. Splitting React (react / react-dom / scheduler) into
        // its own chunk while radix / mui / react-router live in separate
        // chunks creates cross-chunk initialization-order and circular-
        // dependency hazards: a consumer chunk can evaluate and read a React
        // API (`Children`, `useLayoutEffect`, ...) before the React chunk has
        // finished initializing, throwing "Cannot read properties of undefined"
        // at runtime in production (dev is unaffected because Vite serves
        // unbundled ESM).
        //
        // Therefore the entire React runtime AND every React-consuming UI
        // library go into ONE `vendor` chunk. Inside a single chunk Rollup
        // topologically orders React before its consumers, so there are no
        // cross-chunk React references to break.
        //
        // `charts` (recharts/d3) is the only split-out chunk: it is heavy, only
        // used on a few pages, and the edge is strictly one-way (charts imports
        // vendor for React; nothing in vendor imports charts), so it cannot form
        // the cycle described above.
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          if (id.includes('/recharts') || id.includes('/d3-') || id.includes('/victory-')) {
            return 'charts';
          }
          return 'vendor';
        },
      },
    },
  },
})
