import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@enterprise-ui/react/styles.css';
import './styles/platform.css';
import { App } from './app/App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
