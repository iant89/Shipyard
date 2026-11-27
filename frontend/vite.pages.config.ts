import { resolve } from 'node:path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * GitHub Pages build: documentation at / and the interactive demo (the real
 * UI against a simulated in-browser backend) at /demo/. Relative asset paths
 * make the output work under any repository path.
 */
export default defineConfig({
  root: resolve(__dirname, 'site'),
  base: './',
  publicDir: resolve(__dirname, 'public'),
  plugins: [react()],
  build: {
    outDir: resolve(__dirname, 'dist-pages'),
    emptyOutDir: true,
    chunkSizeWarningLimit: 2000,
    rollupOptions: {
      input: {
        docs: resolve(__dirname, 'site/index.html'),
        demo: resolve(__dirname, 'site/demo/index.html'),
      },
    },
  },
});
