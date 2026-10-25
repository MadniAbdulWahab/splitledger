import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyTheme, readTheme } from './group/AccountSheet';
import { registerServiceWorker } from './install';
import './styles/tokens.css';
import './styles/base.css';
import './styles/components.css';

applyTheme(readTheme());
registerServiceWorker();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
