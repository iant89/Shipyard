import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Button, Card, DescriptionList, Form, FormGroup, PasswordInput, Page, SegmentedButton, Select, THEMES, type ThemePreference } from '@enterprise-ui/react';
import { api } from '../services/api';
import { useResource } from '../hooks/useResource';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { usePreferences } from '../state/preferences';
import { useSession } from '../state/session';
import { ShipyardUpdatesCard } from './settings/ShipyardUpdatesCard';

/** Platform settings: appearance (theme, density), account and platform information. */
export function SettingsPage() {
  const { theme, density, setTheme, setDensity } = usePreferences();
  const { status } = useSession();
  const info = useResource(() => api.platform.info(), []);
  const { busy, run } = useAsyncAction();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const { hash } = useLocation();
  useEffect(() => {
    if (hash === '#updates') document.getElementById('updates')?.scrollIntoView({ block: 'start' });
  }, [hash]);

  const changePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    const ok = await run('pw', () => api.auth.changePassword(current, next).then(() => true), 'Password changed');
    if (ok) {
      setCurrent('');
      setNext('');
    }
  };

  const themeOptions = [
    { value: 'system', label: 'System' },
    { value: 'light', label: 'Light' },
    { value: 'dark', label: 'Dark' },
    { value: 'high-contrast', label: 'High contrast' },
    ...THEMES.filter((t) => !['light', 'dark', 'high-contrast'].includes(t.name)).map((t) => ({ value: t.name, label: t.label })),
  ];

  const i = info.data;
  return (
    <Page title="Settings" subtitle="Appearance, account and platform" width="contained">
      <div className="sy-stack">
        <Card title="Appearance">
          <FormGroup columns={2}>
            <Select label="Theme" value={theme} options={themeOptions} onChange={(v) => v && setTheme(v as ThemePreference)}
              description="System follows your operating system's light or dark preference." />
            <div>
              <SegmentedButton
                aria-label="Density"
                items={[{ value: 'compact', label: 'Compact' }, { value: 'cozy', label: 'Cozy' }]}
                value={[density]}
                onChange={(v) => v[0] && setDensity(v[0] as 'compact' | 'cozy')}
              />
              <p style={{ margin: 'var(--eui-space-2) 0 0', color: 'var(--eui-color-text-secondary)', fontSize: 'var(--eui-font-size-sm)' }}>
                Compact suits dashboards and tables; cozy gives forms more room and larger touch targets.
              </p>
            </div>
          </FormGroup>
        </Card>

        <Card title="Account">
          <DescriptionList columns={2} items={[
            { term: 'User', description: status?.username ?? '–' },
            { term: 'Role', description: 'Administrator' },
          ]} />
          <Form onSubmit={changePassword} aria-label="Change password" style={{ marginTop: 'var(--eui-space-4)' }}>
            <FormGroup title="Change password" columns={2}>
              <PasswordInput label="Current password" purpose="current" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
              <PasswordInput label="New password" purpose="new" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} required description="At least 10 characters." />
            </FormGroup>
            <div><Button type="submit" loading={busy === 'pw'}>Change password</Button></div>
          </Form>
        </Card>

        <ShipyardUpdatesCard />

        <Card title="Platform">
          <DescriptionList columns={2} items={[
            { term: 'Version', description: i?.version ?? '–' },
            { term: 'Public URL', description: i?.publicUrl ?? '–' },
            { term: 'Webhook base', description: i ? `${i.publicUrl}/api/webhooks/github/…` : '–' },
            { term: 'Data directory', description: i?.dataDir ?? '–' },
            { term: 'Database', description: 'SQLite' },
            { term: 'Docker network', description: i?.dockerNetwork ?? '–' },
          ]} />
          <p style={{ margin: 'var(--eui-space-4) 0 0', color: 'var(--eui-color-text-secondary)', fontSize: 'var(--eui-font-size-sm)' }}>
            Platform-wide options are set through SHIPYARD_* environment variables; see the README. Back up the data directory
            (database and <code className="sy-mono">database/secret.key</code>) to keep applications, secrets and webhooks.
          </p>
        </Card>
      </div>
    </Page>
  );
}
