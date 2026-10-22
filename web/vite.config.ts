import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In production Spring Boot serves the built files, so page and API share one origin (no CORS).
// During development `npm run dev` serves the page and forwards API calls to the local API.
const api = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/users': api,
      '/me': api,
      '/groups': api,
      '/invitations': api,
    },
  },
  build: {
    outDir: 'dist',
  },
});
