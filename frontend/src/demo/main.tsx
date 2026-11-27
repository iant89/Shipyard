import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@enterprise-ui/react/styles.css';
import '../styles/platform.css';
import { platformMode } from '../app/platformMode';
import { api } from '../services/api';
import { setEventTransport } from '../services/events';
import { createDemoApi, demoTransport } from './demoBackend';
import { DemoBanner } from './DemoBanner';
import { App } from '../app/App';

// Swap the HTTP API for the in-browser simulation before anything renders.
platformMode.demo = true;
platformMode.Banner = DemoBanner;
Object.assign(api, createDemoApi());
setEventTransport(demoTransport);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
