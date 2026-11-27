import { BrowserRouter } from 'react-router-dom';
import { BusyIndicator, ThemeProvider, ToastProvider } from '@enterprise-ui/react';
import { PreferencesProvider, usePreferences } from '../state/preferences';
import { SessionProvider, useSession } from '../state/session';
import { LoginPage } from '../pages/LoginPage';
import { PlatformShell } from '../layouts/PlatformShell';
import { AppRoutes } from './routes';

function Gate() {
  const { status } = useSession();
  if (!status) return <BusyIndicator active text="Loading Shipyard…" style={{ minHeight: '100vh' }} />;
  if (!status.authenticated) return <LoginPage setup={status.setupRequired} />;
  return (
    <PlatformShell>
      <AppRoutes />
    </PlatformShell>
  );
}

function Themed() {
  const { theme, density } = usePreferences();
  return (
    <ThemeProvider theme={theme} density={density} style={{ minHeight: '100vh' }}>
      <ToastProvider>
        <SessionProvider>
          <BrowserRouter>
            <Gate />
          </BrowserRouter>
        </SessionProvider>
      </ToastProvider>
    </ThemeProvider>
  );
}

export function App() {
  return (
    <PreferencesProvider>
      <Themed />
    </PreferencesProvider>
  );
}
