import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import type { Density, ThemePreference } from '@enterprise-ui/react';

/** UI state that persists per browser: theme and density. */
interface Preferences {
  theme: ThemePreference;
  density: Density;
  setTheme: (t: ThemePreference) => void;
  setDensity: (d: Density) => void;
}

const KEY = 'shipyard.preferences';
const Ctx = createContext<Preferences | null>(null);

function load(): { theme: ThemePreference; density: Density } {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const p = JSON.parse(raw);
      return { theme: p.theme ?? 'system', density: p.density ?? 'compact' };
    }
  } catch {
    // storage unavailable
  }
  return { theme: 'system', density: 'compact' };
}

function save(p: { theme: ThemePreference; density: Density }) {
  try {
    localStorage.setItem(KEY, JSON.stringify(p));
  } catch {
    // storage unavailable
  }
}

export function PreferencesProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState(load);
  const setTheme = useCallback((theme: ThemePreference) => setState((s) => { const n = { ...s, theme }; save(n); return n; }), []);
  const setDensity = useCallback((density: Density) => setState((s) => { const n = { ...s, density }; save(n); return n; }), []);
  const value = useMemo(() => ({ ...state, setTheme, setDensity }), [state, setTheme, setDensity]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function usePreferences(): Preferences {
  const v = useContext(Ctx);
  if (!v) throw new Error('usePreferences outside PreferencesProvider');
  return v;
}
