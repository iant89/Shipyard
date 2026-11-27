import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, UNAUTHORIZED_EVENT } from '../services/api';
import { closeAllStreams } from '../services/events';
import type { AuthStatus } from '../services/types';

interface Session {
  status: AuthStatus | null;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
}

const Ctx = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus | null>(null);
  const refresh = useCallback(async () => {
    try {
      setStatus(await api.auth.status());
    } catch {
      setStatus({ setupRequired: false, authenticated: false, username: null });
    }
  }, []);
  const logout = useCallback(async () => {
    closeAllStreams();
    try {
      await api.auth.logout();
    } finally {
      await refresh();
    }
  }, [refresh]);

  useEffect(() => {
    refresh();
    const onUnauthorized = () => {
      closeAllStreams();
      setStatus((s) => (s ? { ...s, authenticated: false, username: null } : s));
    };
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, [refresh]);

  const value = useMemo(() => ({ status, refresh, logout }), [status, refresh, logout]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession(): Session {
  const v = useContext(Ctx);
  if (!v) throw new Error('useSession outside SessionProvider');
  return v;
}
