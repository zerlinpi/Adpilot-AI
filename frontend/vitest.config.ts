/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import path from 'path'
import react from '@vitejs/plugin-react'

// Dedicated Vitest config. Kept separate from vite.config.ts so the app build
// pipeline (tailwind plugin, dev proxy, figma asset resolver) does not affect
// the test runtime. Only what the component tests need is configured here.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
})
