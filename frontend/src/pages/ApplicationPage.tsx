import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  AlertDialog, BusyIndicator, Button, DescriptionList, EmptyState, Icon, Link, MessageStrip, OverflowMenu, Page, Tab, TabList, TabPanel, Tabs,
} from '@enterprise-ui/react';
import { useApplication } from '../hooks/useApplications';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { api } from '../services/api';
import { ApplicationStatus } from '../components/ApplicationStatus';
import { shortSha } from '../services/format';
import { DeployDialog } from './application/DeployDialog';
import { UpdateBanner } from './application/UpdateBanner';
import { isActive } from '../components/DeploymentStatus';
import { OverviewTab } from './application/OverviewTab';
import { DeploymentsTab } from './application/DeploymentsTab';
import { LogsTab } from './application/LogsTab';
import { MetricsTab } from './application/MetricsTab';
import { EnvironmentTab } from './application/EnvironmentTab';
import { DomainsTab } from './application/DomainsTab';
import { WebhookPage } from './WebhookPage';
import { SettingsTab } from './application/SettingsTab';
import { appHref } from '../app/platformMode';

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'deployments', label: 'Deployments' },
  { id: 'logs', label: 'Logs' },
  { id: 'metrics', label: 'Metrics' },
  { id: 'environment', label: 'Environment' },
  { id: 'domains', label: 'Domains' },
  { id: 'webhook', label: 'Webhook' },
  { id: 'settings', label: 'Settings' },
] as const;

/**
 * Application object page (spec section 14): header with status, key
 * attributes and actions, and one tab per area. Tabs map to routes
 * (/applications/:id/deployments, …) so every view is linkable.
 */
export function ApplicationPage() {
  const { id = '', tab = 'overview' } = useParams();
  const navigate = useNavigate();
  const app = useApplication(id);
  const { busy, run } = useAsyncAction();
  const [deployOpen, setDeployOpen] = useState(false);
  const [confirm, setConfirm] = useState<'delete' | 'stop' | 'rollback' | null>(null);

  if (app.loading) return <BusyIndicator active text="Loading application…" style={{ minHeight: '50vh' }} />;
  if (!app.data) {
    return (
      <Page title="Application">
        <EmptyState icon="error" title="Application not found" description={app.error ?? undefined}
          actions={<Button onClick={() => navigate('/applications')}>Back to applications</Button>} />
      </Page>
    );
  }

  const detail = app.data;
  const a = detail.summary;
  const current = a.currentDeployment;
  const latest = a.latestDeployment;
  const stopped = a.status === 'STOPPED';
  const activeTab = TABS.some((t) => t.id === tab) ? tab : 'overview';

  const doAction = (key: string, fn: () => Promise<unknown>, msg: string) => run(key, fn, msg).then(() => app.reload());

  return (
    <Page
      title={a.name}
      subtitle={a.repository ? `${a.repository.fullName} · ${a.repository.branch}` : undefined}
      titleAddon={<ApplicationStatus status={a.status} />}
      breadcrumbs={[{ label: 'Applications', href: appHref('/applications'), onClick: (e) => { e.preventDefault(); navigate('/applications'); } }, { label: a.name }]}
      actions={
        <>
          <Button variant="primary" icon={<Icon name="upload" />} onClick={() => setDeployOpen(true)}>Deploy</Button>
          <Button icon={<Icon name="refresh" />} loading={busy === 'restart'} disabled={!current || stopped}
            onClick={() => doAction('restart', () => api.applications.restart(id), `${a.name} restarted`)}>Restart</Button>
          {stopped ? (
            <Button icon={<Icon name="media-play" />} loading={busy === 'start'} onClick={() => doAction('start', () => api.applications.start(id), `${a.name} started`)}>Start</Button>
          ) : (
            <Button icon={<Icon name="stop" />} disabled={!current} onClick={() => setConfirm('stop')}>Stop</Button>
          )}
          <OverflowMenu
            label="More actions"
            placement="bottom-end"
            items={[
              { id: 'open', label: 'Open application', icon: <Icon name="internet-browser" />, disabled: !a.primaryUrl },
              { id: 'rollback', label: 'Roll back to previous deployment', icon: <Icon name="undo" />, disabled: !current },
              { id: 'redetect', label: 'Re-run detection', icon: <Icon name="inspection" /> },
              { type: 'separator' },
              { id: 'delete', label: 'Delete application', icon: <Icon name="delete" />, destructive: true },
            ]}
            onAction={(action) => {
              if (action === 'open' && a.primaryUrl) window.open(a.primaryUrl, '_blank', 'noreferrer');
              if (action === 'rollback') setConfirm('rollback');
              if (action === 'redetect') navigate(`/applications/${id}/settings`);
              if (action === 'delete') setConfirm('delete');
            }}
          />
        </>
      }
      headerContent={
        <DescriptionList
          layout="stacked"
          columns={4}
          items={[
            { term: 'Current deployment', description: current ? `#${current.number} · ${shortSha(current.commitSha)}` : 'Not deployed' },
            { term: 'Type', description: a.framework ?? (a.projectType ? a.projectType.toLowerCase() : '–') },
            { term: 'Domain', description: a.primaryUrl ? <Link href={a.primaryUrl} target="_blank" rel="noreferrer">{a.primaryUrl.replace(/^https?:\/\//, '')}</Link> : 'None' },
            { term: 'Automatic deployment', description: a.autoDeploy ? 'On' : 'Off' },
          ]}
        />
      }
    >
      {latest?.status === 'FAILED' && latest.id !== current?.id && (
        <MessageStrip tone="error" title={`Deployment #${latest.number} failed`}
          actions={<Button size="sm" onClick={() => navigate(`/applications/${id}/deployments/${latest.id}`)}>View logs</Button>}>
          {latest.error}{current ? ` Deployment #${current.number} is still serving traffic.` : ''}
        </MessageStrip>
      )}
      <UpdateBanner applicationId={id} currentDeploymentId={current?.id ?? null} deploying={!!latest && isActive(latest.status)}
        failed={latest?.status === 'FAILED' && latest.id !== current?.id ? { sha: latest.commitSha, number: latest.number } : null}
        onDeployed={(d) => { app.reload(); navigate(`/applications/${id}/deployments/${d.id}`); }} />
      {a.status === 'CRASHED' && (
        <MessageStrip tone="error" title="Application crashed">
          The container keeps exiting ({detail.service.restartCount} restarts). Check the logs, then restart or redeploy.
        </MessageStrip>
      )}
      <Tabs value={activeTab} onValueChange={(t) => navigate(t === 'overview' ? `/applications/${id}` : `/applications/${id}/${t}`)}>
        <TabList aria-label="Application sections">
          {TABS.map((t) => <Tab key={t.id} value={t.id}>{t.label}</Tab>)}
        </TabList>
        <TabPanel value="overview">{activeTab === 'overview' && <OverviewTab detail={detail} />}</TabPanel>
        <TabPanel value="deployments">{activeTab === 'deployments' && <DeploymentsTab detail={detail} />}</TabPanel>
        <TabPanel value="logs">{activeTab === 'logs' && <LogsTab detail={detail} />}</TabPanel>
        <TabPanel value="metrics">{activeTab === 'metrics' && <MetricsTab detail={detail} />}</TabPanel>
        <TabPanel value="environment">{activeTab === 'environment' && <EnvironmentTab applicationId={id} />}</TabPanel>
        <TabPanel value="domains">{activeTab === 'domains' && <DomainsTab detail={detail} onChange={app.reload} />}</TabPanel>
        <TabPanel value="webhook">{activeTab === 'webhook' && <WebhookPage applicationId={id} />}</TabPanel>
        <TabPanel value="settings">{activeTab === 'settings' && <SettingsTab detail={detail} onSaved={app.setData} />}</TabPanel>
      </Tabs>

      <DeployDialog open={deployOpen} onClose={() => setDeployOpen(false)} applicationId={id} branch={a.repository?.branch ?? 'main'}
        onDeployed={(d) => navigate(`/applications/${id}/deployments/${d.id}`)} />
      <AlertDialog open={confirm === 'stop'} tone="warning" title={`Stop ${a.name}?`} confirmLabel="Stop"
        onCancel={() => setConfirm(null)}
        onConfirm={async () => { setConfirm(null); await doAction('stop', () => api.applications.stop(id), `${a.name} stopped`); }}>
        The application stops serving traffic until it is started again. Its deployment is kept.
      </AlertDialog>
      <AlertDialog open={confirm === 'rollback'} tone="warning" title="Roll back?" confirmLabel="Roll back"
        onCancel={() => setConfirm(null)}
        onConfirm={async () => { setConfirm(null); await doAction('rollback', () => api.applications.rollback(id), 'Rollback queued'); }}>
        The previous healthy deployment's artifact is started again (no rebuild). It replaces #{current?.number} once it passes its health check.
      </AlertDialog>
      <AlertDialog open={confirm === 'delete'} tone="error" title={`Delete ${a.name}?`} confirmLabel="Delete"
        onCancel={() => setConfirm(null)}
        onConfirm={async () => {
          setConfirm(null);
          const ok = await run('delete', () => api.applications.remove(id), `${a.name} deleted`);
          if (ok !== undefined || true) navigate('/applications');
        }}>
        This stops the application, removes its containers, images, deployment history, domains and webhook. This cannot be undone.
      </AlertDialog>
    </Page>
  );
}
