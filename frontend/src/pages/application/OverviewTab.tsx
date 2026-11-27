import { useNavigate } from 'react-router-dom';
import { Button, Card, DescriptionList, Link, StatusIndicator, Timeline } from '@enterprise-ui/react';
import { api } from '../../services/api';
import { useResource } from '../../hooks/useResource';
import { useWebhook } from '../../hooks/useWebhook';
import { useDeployments } from '../../hooks/useDeployments';
import type { ApplicationDetail } from '../../services/types';
import { formatBytes, formatDateTime, formatPercent, formatRate, humanize, relativeTime, shortSha } from '../../services/format';
import { ApplicationStatus } from '../../components/ApplicationStatus';
import { DeploymentStatus, deploymentTone } from '../../components/DeploymentStatus';
import { CommitRef } from '../../components/CommitRef';
import { appHref } from '../../app/platformMode';

/** Object page sections: Overview, Runtime, Deployment, Automation (spec section 14). */
export function OverviewTab({ detail }: { detail: ApplicationDetail }) {
  const navigate = useNavigate();
  const a = detail.summary;
  const current = a.currentDeployment;
  const latest = a.latestDeployment;
  const metrics = useResource(() => api.applications.metrics(a.id), [a.id], { stream: null, refreshOn: ['MetricsUpdated'] });
  const webhook = useWebhook(a.id);
  const deployments = useDeployments(a.id, 6);
  const m = metrics.data?.latest;
  const isStatic = (current ?? latest)?.projectType === 'STATIC';

  return (
    <div className="sy-stack">
      <div className="sy-grid-2">
        <Card title="Overview">
          <DescriptionList
            columns={2}
            items={[
              { term: 'Status', description: <ApplicationStatus status={a.status} size="sm" /> },
              { term: 'Repository', description: a.repository ? <Link href={a.repository.url.replace(/\.git$/, '')} target="_blank" rel="noreferrer">{a.repository.fullName}</Link> : '–' },
              { term: 'Branch', description: a.repository?.branch ?? '–' },
              { term: 'Current commit', description: current ? <CommitRef sha={current.commitSha} message={current.commitMessage} repositoryUrl={a.repository?.url} /> : '–' },
              { term: 'Current deployment', description: current ? <Link href={appHref(`/applications/${a.id}/deployments/${current.id}`)} onClick={(e) => { e.preventDefault(); navigate(`/applications/${a.id}/deployments/${current.id}`); }}>#{current.number}</Link> : 'None' },
              { term: 'Domain', description: a.urls.length ? a.urls.map((u) => <div key={u}><Link href={u} target="_blank" rel="noreferrer">{u.replace(/^https?:\/\//, '')}</Link></div>) : <Button size="sm" variant="tertiary" onClick={() => navigate(`/applications/${a.id}/domains`)}>Add a domain</Button> },
            ]}
          />
        </Card>

        <Card title="Runtime" headerActions={<Button size="sm" variant="tertiary" onClick={() => navigate(`/applications/${a.id}/metrics`)}>Metrics</Button>}>
          {isStatic ? (
            <DescriptionList columns={2} items={[
              { term: 'Runtime', description: 'Static files served by Caddy' },
              { term: 'Container', description: 'None (no process to run)' },
            ]} />
          ) : (
            <DescriptionList
              columns={2}
              items={[
                { term: 'CPU', description: m ? `${formatPercent(m.cpuPercent, 1)} of ${detail.service.cpuLimit} cores` : '–' },
                { term: 'Memory', description: m ? `${formatBytes(m.memoryBytes)} / ${formatBytes(detail.service.memoryLimitMb * 1048576)}` : '–' },
                { term: 'Network', description: m ? `↓ ${formatRate(m.networkRxRate)} ↑ ${formatRate(m.networkTxRate)}` : '–' },
                { term: 'Container', description: metrics.data?.container
                  ? <StatusIndicator size="sm" tone={metrics.data.container.running ? 'success' : 'error'}>{metrics.data.container.name} · {metrics.data.container.status}</StatusIndicator>
                  : current?.containerName ?? '–' },
                { term: 'Restarts', description: String(metrics.data?.restartCount ?? detail.service.restartCount) },
                { term: 'Port', description: String(detail.service.internalPort ?? current?.containerName ? (detail.service.internalPort ?? 'detected') : '–') },
              ]}
            />
          )}
        </Card>

        <Card title="Deployment" headerActions={latest ? <DeploymentStatus status={latest.status} size="sm" /> : undefined}>
          {latest ? (
            <DescriptionList
                columns={2}
                items={[
                  { term: 'Deployment', description: <Link href={appHref(`/applications/${a.id}/deployments/${latest.id}`)} onClick={(e) => { e.preventDefault(); navigate(`/applications/${a.id}/deployments/${latest.id}`); }}>#{latest.number}</Link> },
                  { term: 'Commit', description: <CommitRef sha={latest.commitSha} message={latest.commitMessage} repositoryUrl={a.repository?.url} /> },
                  { term: 'Build', description: humanize(latest.buildStatus) },
                  { term: 'Source', description: humanize(latest.source) },
                  { term: 'Started', description: formatDateTime(latest.startedAt ?? latest.createdAt) },
                  { term: 'Error', description: latest.error ?? '–', span: 'full' },
                ]}
              />
          ) : (
            <Button variant="primary" onClick={() => navigate(`/applications/${a.id}/deployments`)}>No deployments yet</Button>
          )}
        </Card>

        <Card title="Automation" headerActions={<Button size="sm" variant="tertiary" onClick={() => navigate(`/applications/${a.id}/webhook`)}>Webhook</Button>}>
          <DescriptionList
            columns={2}
            items={[
              { term: 'Automatic deployment', description: <StatusIndicator size="sm" tone={a.autoDeploy ? 'success' : 'neutral'}>{a.autoDeploy ? 'On' : 'Off'}</StatusIndicator> },
              { term: 'Webhook status', description: webhook.data ? <StatusIndicator size="sm" tone={webhook.data.status === 'ACTIVE' ? 'success' : webhook.data.status === 'ERROR' ? 'error' : webhook.data.status === 'WAITING' ? 'information' : 'neutral'}>{humanize(webhook.data.status)}</StatusIndicator> : '–' },
              { term: 'Last event', description: webhook.data?.lastEvent ? `${webhook.data.lastEvent.eventType} · ${relativeTime(webhook.data.lastEvent.receivedAt)}` : 'None received' },
              { term: 'Last commit', description: webhook.data?.lastEvent?.commitSha ? shortSha(webhook.data.lastEvent.commitSha) : '–' },
              { term: 'Automatic rollback', description: detail.autoRollback ? 'On' : 'Off' },
              { term: 'Cancel stale builds', description: detail.cancelStaleBuilds ? 'On' : 'Off' },
            ]}
          />
        </Card>
      </div>

      <Card title="Recent deployments" headerActions={<Button size="sm" variant="tertiary" onClick={() => navigate(`/applications/${a.id}/deployments`)}>All deployments</Button>}>
        <Timeline
          items={(deployments.data ?? []).map((d) => ({
            id: d.id,
            title: `#${d.number} · ${humanize(d.status)}`,
            timestamp: formatDateTime(d.createdAt),
            description: `${shortSha(d.commitSha)} ${d.commitMessage ?? ''} · ${humanize(d.source)}${d.error ? ` · ${d.error}` : ''}`,
            tone: deploymentTone(d.status),
          }))}
        />
      </Card>
    </div>
  );
}
