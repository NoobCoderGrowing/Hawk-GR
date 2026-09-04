import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev: proxy /api → Spring Boot on :8080
// Build: emit directly into Spring's static/ so one server serves the page.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
})
