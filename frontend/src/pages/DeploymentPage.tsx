import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { BusyIndicator, Button, Card, DescriptionList, EmptyState, Icon, MessageStrip, Page } from '@enterprise-ui/react';
import { useDeployment } from '../hooks/useDeployments';
import { useApplication } from '../hooks/useApplications';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { api } from '../services/api';
import { DeploymentStatus, isActive } from '../components/DeploymentStatus';
import { DeploymentTimeline } from '../components/DeploymentTimeline';
import { BuildLogViewer } from '../components/BuildLogViewer';
import { SourceBadge } from '../components/SourceBadge';
import { CommitRef } from '../components/CommitRef';
import { duration, formatDateTime, humanize } from '../services/format';
import { appHref } from '../app/platformMode';

/** Deployment details: pipeline, metadata and live build/deployment logs. */
export function DeploymentPage() {
  const { id = '', deploymentId = '' } = useParams();
  const navigate = useNavigate();
  const app = useApplication(id);
  // Poll while the deployment is in progress so the pipeline keeps moving even
  // if real-time events are delayed by a proxy or a reconnect.
  const [polling, setPolling] = useState(true);
  const dep = useDeployment(id, deploymentId, polling ? 3000 : undefined);
  const { busy, run } = useAsyncAction();
  const d = dep.data;
  const a = app.data?.summary;
  const inProgress = !d || isActive(d.status) || d.status === 'CREATED';
  useEffect(() => setPolling(inProgress), [inProgress]);

  if (dep.loading) return <BusyIndicator active text="Loading deployment…" style={{ minHeight: '50vh' }} />;
  if (!d) {
    return <Page title="Deployment"><EmptyState icon="error" title="Deployment not found" description={dep.error ?? undefined} /></Page>;
  }
  const active = isActive(d.status);
  const isCurrent = a?.currentDeployment?.id === d.id;
  const canRollback = !isCurrent && !active && d.status !== 'FAILED' && d.status !== 'CANCELLED' && (!!d.imageTag || d.projectType === 'STATIC');
  const appCrumb = (e: React.MouseEvent) => { e.preventDefault(); navigate(`/applications/${id}`); };

  return (
    <Page
      title={`Deployment #${d.number}`}
      subtitle={`${a?.name ?? ''} · ${d.branch}`}
      titleAddon={<DeploymentStatus status={d.status} />}
      breadcrumbs={[
        { label: 'Applications', href: appHref('/applications'), onClick: (e) => { e.preventDefault(); navigate('/applications'); } },
        { label: a?.name ?? 'Application', href: appHref(`/applications/${id}`), onClick: appCrumb },
        { label: 'Deployments', href: appHref(`/applications/${id}/deployments`), onClick: (e) => { e.preventDefault(); navigate(`/applications/${id}/deployments`); } },
        { label: `#${d.number}` },
      ]}
      actions={
        <>
          {active && <Button icon={<Icon name="decline" />} loading={busy === 'cancel'} onClick={() => run('cancel', () => api.applications.cancelDeployment(id, d.id), 'Cancellation requested')}>Cancel</Button>}
          {d.commitSha && !active && (
            <Button icon={<Icon name="synchronize" />} loading={busy === 'redeploy'}
              onClick={() => run('redeploy', () => api.applications.redeploy(id, d.id), (r) => `Deployment #${r.number} queued`).then((r) => r && navigate(`/applications/${id}/deployments/${r.id}`))}>Redeploy</Button>
          )}
          {canRollback && (
            <Button icon={<Icon name="undo" />} loading={busy === 'rollback'} onClick={() => run('rollback', () => api.applications.rollback(id, d.id), `Rolling back to #${d.number}`)}>Roll back to this</Button>
          )}
        </>
      }
    >
      <div className="sy-stack">
        {d.status === 'FAILED' && (
          <MessageStrip tone="error" title="Deployment failed">
            {d.error}{a?.currentDeployment && a.currentDeployment.id !== d.id ? ` Deployment #${a.currentDeployment.number} remains online.` : ''}
          </MessageStrip>
        )}
        <Card title="Pipeline">
          <DeploymentTimeline deployment={d} />
        </Card>
        <Card title="Details">
          <DescriptionList
            columns={4}
            items={[
              { term: 'Deployment ID', description: <code className="sy-mono">{d.id}</code>, span: 2 },
              { term: 'Commit', description: <CommitRef sha={d.commitSha} message={d.commitMessage} repositoryUrl={a?.repository?.url} />, span: 2 },
              { term: 'Branch', description: d.branch },
              { term: 'Source', description: <SourceBadge source={d.source} /> },
              { term: 'Author', description: d.commitAuthor ?? '–' },
              { term: 'Current', description: isCurrent ? 'Yes — serving traffic' : 'No' },
              { term: 'Type', description: d.projectType ? humanize(d.projectType) : '–' },
              { term: 'Framework', description: d.framework ?? '–' },
              { term: 'Build', description: humanize(d.buildStatus) },
              { term: 'Runtime', description: humanize(d.runtimeStatus) },
              { term: 'Artifact', description: d.imageTag ? <code className="sy-mono">{d.imageTag}</code> : d.projectType === 'STATIC' ? 'Static files' : '–', span: 2 },
              { term: 'Container', description: d.containerName ?? '–', span: 2 },
              { term: 'Created', description: formatDateTime(d.createdAt) },
              { term: 'Started', description: formatDateTime(d.startedAt) },
              { term: 'Finished', description: formatDateTime(d.finishedAt) },
              { term: 'Duration', description: duration(d.startedAt, d.finishedAt) },
            ]}
          />
        </Card>
        <BuildLogViewer applicationId={id} deploymentId={d.id} live={inProgress} />
      </div>
    </Page>
  );
}
