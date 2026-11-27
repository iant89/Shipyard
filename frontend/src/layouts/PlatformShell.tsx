import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { AppShell, ApplicationHeader, Icon, List, NavigationGroup, NavigationItem, SideNavigation, StatusIndicator } from '@enterprise-ui/react';
import { useSession } from '../state/session';
import { usePreferences } from '../state/preferences';
import { useApplications } from '../hooks/useApplications';
import { onConnectionChange, subscribe } from '../services/events';
import type { PlatformEvent } from '../services/types';
import { formatTime } from '../services/format';

interface Notice {
  id: string;
  title: string;
  description: string;
  tone: 'success' | 'error' | 'warning' | 'information';
  applicationId: string | null;
  deploymentId: string | null;
  read: boolean;
}

/** Turns platform events into header notifications. */
function toNotice(e: PlatformEvent, names: Record<string, string>): Notice | null {
  const app = (e.applicationId && names[e.applicationId]) || 'Application';
  const number = e.data.number != null ? `#${e.data.number}` : '';
  const base = { id: `${e.type}-${e.deploymentId ?? e.applicationId}-${e.timestamp}`, applicationId: e.applicationId, deploymentId: e.deploymentId, read: false };
  switch (e.type) {
    case 'DeploymentHealthy': return { ...base, title: `${app} deployed`, description: `Deployment ${number} is running · ${formatTime(e.timestamp)}`, tone: 'success' };
    case 'DeploymentFailed': return { ...base, title: `${app} deployment failed`, description: `${number} ${String(e.data.error ?? '')}`.trim(), tone: 'error' };
    case 'DeploymentRolledBack': return { ...base, title: `${app} rolled back`, description: `Now serving ${number}`, tone: 'warning' };
    case 'ApplicationCrashed': return { ...base, title: `${app} crashed`, description: `Restart count ${String(e.data.restartCount ?? '?')}`, tone: 'error' };
    default: return null;
  }
}

/** Platform application shell: ENTERPRISE-UI AppShell + ApplicationHeader + SideNavigation. */
export function PlatformShell({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { status, logout } = useSession();
  const { theme, setTheme } = usePreferences();
  const apps = useApplications();
  const [connected, setConnected] = useState(false);
  const [notices, setNotices] = useState<Notice[]>([]);

  const names = useMemo(() => Object.fromEntries((apps.data ?? []).map((a) => [a.id, a.name])), [apps.data]);
  const running = (apps.data ?? []).filter((a) => a.status === 'RUNNING').length;
  const failed = (apps.data ?? []).filter((a) => a.status === 'FAILED' || a.status === 'CRASHED').length;

  useEffect(() => onConnectionChange(setConnected), []);
  useEffect(
    () => subscribe(null, (e) => {
      const n = toNotice(e, names);
      if (n) setNotices((cur) => [n, ...cur].slice(0, 30));
    }),
    [names],
  );

  const path = location.pathname;
  const params = new URLSearchParams(location.search);
  const activeId =
    path === '/' ? 'dashboard'
      : path.startsWith('/applications') ? (path === '/applications' && params.get('status') === 'RUNNING' ? 'apps-running'
        : path === '/applications' && params.get('status') === 'FAILED' ? 'apps-failed' : 'apps-all')
        : path.split('/')[1];

  const go = (to: string) => (_id: string, event: { preventDefault: () => void }) => {
    event.preventDefault();
    navigate(to);
  };
  const routes: Record<string, string> = {
    dashboard: '/', 'apps-all': '/applications', 'apps-running': '/applications?status=RUNNING', 'apps-failed': '/applications?status=FAILED',
    deployments: '/deployments', infrastructure: '/infrastructure', activity: '/activity', settings: '/settings',
  };
  const unread = notices.filter((n) => !n.read).length;

  return (
    <AppShell
      fullHeight
      header={
        <ApplicationHeader
          productName="Shipyard"
          secondaryTitle="Application Platform"
          logo={<Icon name="sap-box" color="var(--eui-color-primary)" />}
          homeHref="/"
          context={[
            {
              id: 'system',
              priority: 1,
              content: (
                <StatusIndicator tone={!connected ? 'warning' : failed ? 'error' : 'success'} size="sm">
                  {!connected ? 'Reconnecting…' : failed ? `${failed} failing` : `${running} running`}
                </StatusIndicator>
              ),
            },
          ]}
          notificationCount={unread}
          onNotificationsClick={() => setNotices((cur) => cur.map((n) => ({ ...n, read: true })))}
          notifications={
            <List
              aria-label="Notifications"
              emptyState="No notifications yet"
              items={notices.map((n) => ({
                id: n.id,
                title: n.title,
                textValue: n.title,
                description: n.description,
                trailing: <StatusIndicator tone={n.tone} size="sm">{n.tone === 'success' ? 'OK' : n.tone === 'error' ? 'Error' : 'Info'}</StatusIndicator>,
              }))}
              onItemClick={(item) => {
                const n = notices.find((x) => x.id === item.id);
                if (n?.applicationId) navigate(n.deploymentId ? `/applications/${n.applicationId}/deployments/${n.deploymentId}` : `/applications/${n.applicationId}`);
              }}
            />
          }
          helpItems={[
            { id: 'docs', label: 'Documentation' },
            { id: 'theme', label: theme === 'dark' ? 'Use light theme' : 'Use dark theme' },
          ]}
          onHelpAction={(id) => {
            if (id === 'docs') window.open('https://github.com/iant89/Shipyard#readme', '_blank', 'noreferrer');
            if (id === 'theme') setTheme(theme === 'dark' ? 'light' : 'dark');
          }}
          onSettingsClick={() => navigate('/settings')}
          user={{ name: status?.username ?? 'admin', role: 'Administrator' }}
          userMenuItems={[{ id: 'settings', label: 'Settings' }, { type: 'separator' }, { id: 'logout', label: 'Sign out' }]}
          onUserAction={(id) => {
            if (id === 'settings') navigate('/settings');
            if (id === 'logout') logout();
          }}
        />
      }
      navigation={
        <SideNavigation
          activeId={activeId}
          onNavigate={(id, event) => {
            const to = routes[id];
            if (to) go(to)(id, event);
            else event.preventDefault();
          }}
          footer={<NavigationItem id="settings" label="Settings" icon={<Icon name="settings" />} href="/settings" />}
        >
          <NavigationGroup label="Application Platform">
            <NavigationItem id="dashboard" label="Dashboard" icon={<Icon name="home" />} href="/" />
            <NavigationItem id="applications" label="Applications" icon={<Icon name="product" />} defaultExpanded>
              <NavigationItem id="apps-all" label="All applications" href="/applications" />
              <NavigationItem id="apps-running" label="Running" href="/applications?status=RUNNING" badge={running || undefined} />
              <NavigationItem id="apps-failed" label="Failed" href="/applications?status=FAILED" badge={failed || undefined} />
            </NavigationItem>
            <NavigationItem id="deployments" label="Deployments" icon={<Icon name="process" />} href="/deployments" />
            <NavigationItem id="infrastructure" label="Infrastructure" icon={<Icon name="it-host" />} href="/infrastructure" />
            <NavigationItem id="activity" label="Activity" icon={<Icon name="history" />} href="/activity" />
          </NavigationGroup>
        </SideNavigation>
      }
    >
      {children}
    </AppShell>
  );
}
