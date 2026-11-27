import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@enterprise-ui/react/styles.css';
import '../styles/platform.css';
import { DocsSite } from './DocsSite';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <DocsSite />
  </StrictMode>,
);
