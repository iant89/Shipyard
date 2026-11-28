import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertDialog, Badge, DataGrid, Icon, OverflowMenu, type DataGridColumn } from '@enterprise-ui/react';
import { useDeployments } from '../../hooks/useDeployments';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import { api } from '../../services/api';
import type { ApplicationDetail, Deployment } from '../../services/types';
import { duration, formatDateTime } from '../../services/format';
import { DeploymentStatus, isActive } from '../../components/DeploymentStatus';
import { SourceBadge } from '../../components/SourceBadge';
import { CommitRef } from '../../components/CommitRef';

/** Deployment history with actions (spec section 55). */
export function DeploymentsTab({ detail }: { detail: ApplicationDetail }) {
  const navigate = useNavigate();
  const a = detail.summary;
  const { data, loading } = useDeployments(a.id, 200);
  const { run } = useAsyncAction();
  const [rollbackTo, setRollbackTo] = useState<Deployment | null>(null);
  const currentId = a.currentDeployment?.id;

  const action = (d: Deployment, id: string) => {
    if (id === 'logs') navigate(`/applications/${a.id}/deployments/${d.id}`);
    if (id === 'redeploy') run('redeploy', () => api.applications.redeploy(a.id, d.id), (r) => `Deployment #${r.number} queued`);
    if (id === 'rollback') setRollbackTo(d);
    if (id === 'cancel') run('cancel', () => api.applications.cancelDeployment(a.id, d.id), `Deployment #${d.number} cancelled`);
    if (id === 'stop') run('stop', () => api.applications.stop(a.id), `${a.name} stopped`);
    if (id === 'restart') run('restart', () => api.applications.restart(a.id), `${a.name} restarted`);
  };

  const columns: DataGridColumn<Deployment>[] = [
    { id: 'number', header: 'Deployment', accessor: 'number', sortable: true, rowHeader: true, alwaysVisible: true,
      cell: (d) => <span className="sy-row"><strong>#{d.number}</strong>{d.id === currentId && <Badge tone="success">Current</Badge>}</span> },
    { id: 'commit', header: 'Commit', cell: (d) => <CommitRef sha={d.commitSha} message={d.commitMessage} repositoryUrl={a.repository?.url} /> },
    { id: 'branch', header: 'Branch', accessor: 'branch' },
    { id: 'source', header: 'Source', accessor: 'source', sortable: true, cell: (d) => <SourceBadge source={d.source} /> },
    { id: 'status', header: 'Status', accessor: 'status', sortable: true, cell: (d) => <DeploymentStatus status={d.status} size="sm" /> },
    { id: 'framework', header: 'Build', cell: (d) => d.framework ?? '–' },
    { id: 'created', header: 'Created', accessor: 'createdAt', sortable: true, cell: (d) => formatDateTime(d.createdAt) },
    { id: 'duration', header: 'Duration', align: 'end', cell: (d) => duration(d.startedAt, d.finishedAt ?? (isActive(d.status) ? null : d.startedAt)) },
    {
      id: 'actions', header: 'Actions', align: 'end', alwaysVisible: true,
      cell: (d) => (
        <span onClick={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()} role="presentation">
          <OverflowMenu
            label={`Actions for deployment ${d.number}`}
            placement="bottom-end"
            items={[
              { id: 'logs', label: 'View logs', icon: <Icon name="log" /> },
              { id: 'redeploy', label: 'Redeploy this commit', icon: <Icon name="synchronize" />, disabled: !d.commitSha },
              { id: 'rollback', label: 'Roll back to this deployment', icon: <Icon name="undo" />,
                disabled: d.id === currentId || isActive(d.status) || !(d.imageTag || d.projectType === 'STATIC') || d.status === 'FAILED' || d.status === 'CANCELLED' },
              { id: 'cancel', label: 'Cancel', icon: <Icon name="decline" />, disabled: !isActive(d.status) },
              { type: 'separator' },
              { id: 'restart', label: 'Restart', icon: <Icon name="refresh" />, disabled: d.id !== currentId },
              { id: 'stop', label: 'Stop', icon: <Icon name="stop" />, disabled: d.id !== currentId || a.status === 'STOPPED', destructive: true },
            ]}
            onAction={(id) => action(d, id)}
          />
        </span>
      ),
    },
  ];

  return (
    <>
      <DataGrid
        title="Deployments"
        columns={columns}
        data={data ?? []}
        loading={loading}
        getRowId={(d) => d.id}
        getRowLabel={(d) => `Deployment ${d.number}`}
        getRowHighlight={(d) => (d.id === currentId ? 'success' : d.status === 'FAILED' ? 'error' : undefined)}
        defaultSort={{ columnId: 'number', direction: 'descending' }}
        pageSize={25}
        defaultDensity="compact"
        responsive="stack"
        onRowClick={(d) => navigate(`/applications/${a.id}/deployments/${d.id}`)}
        emptyState="No deployments yet. Use Deploy to start the first one."
      />
      <AlertDialog open={!!rollbackTo} tone="warning" title={`Roll back to #${rollbackTo?.number}?`} confirmLabel="Roll back"
        onCancel={() => setRollbackTo(null)}
        onConfirm={async () => {
          const target = rollbackTo;
          setRollbackTo(null);
          if (target) await run('rollback', () => api.applications.rollback(a.id, target.id), `Rolling back to #${target.number}`);
        }}>
        Deployment #{rollbackTo?.number} ({rollbackTo?.commitSha?.slice(0, 7)}) is started from its stored artifact without rebuilding, health-checked, and then receives traffic.
      </AlertDialog>
    </>
  );
}
