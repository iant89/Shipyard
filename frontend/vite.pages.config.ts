import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const frontendRoot = fileURLToPath(new URL('.', import.meta.url));

/**
 * GitHub Pages build: documentation at / and the interactive demo (the real
 * UI against a simulated in-browser backend) at /demo/. Relative asset paths
 * make the output work under any repository path.
 */
export default defineConfig({
  root: resolve(frontendRoot, 'site'),
  base: './',
  publicDir: resolve(frontendRoot, 'public'),
  plugins: [react()],
  resolve: {
    alias: [
      { find: '/src/site/', replacement: `${frontendRoot}/src/site/` },
      { find: '/src/demo/', replacement: `${frontendRoot}/src/demo/` },
    ],
  },
  server: {
    host: '0.0.0.0',
    allowedHosts: true,
    fs: { allow: [frontendRoot] },
  },
  preview: {
    host: '0.0.0.0',
    allowedHosts: true,
  },
  build: {
    outDir: resolve(frontendRoot, 'dist-pages'),
    emptyOutDir: true,
    chunkSizeWarningLimit: 2000,
    rollupOptions: {
      input: {
        docs: resolve(frontendRoot, 'site/index.html'),
        demo: resolve(frontendRoot, 'site/demo/index.html'),
      },
    },
  },
});
