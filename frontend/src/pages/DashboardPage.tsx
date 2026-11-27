import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Card, Dashboard, DashboardWidget, EmptyState, Icon, MetricCard, Page, Table, type TableColumn } from '@enterprise-ui/react';
import { api } from '../services/api';
import { useResource } from '../hooks/useResource';
import { useApplications } from '../hooks/useApplications';
import { ResourceMetrics } from '../components/ResourceMetrics';
import { ApplicationCard } from '../components/ApplicationCard';
import { ActivityTimeline } from '../components/ActivityTimeline';
import { DeploymentStatus } from '../components/DeploymentStatus';
import { SourceBadge } from '../components/SourceBadge';
import type { Deployment } from '../services/types';
import { relativeTime, shortSha } from '../services/format';

export function DashboardPage() {
  const navigate = useNavigate();
  const apps = useApplications();
  const dash = useResource(() => api.dashboard(), [], {
    stream: null,
    refreshOn: ['DeploymentStatusChanged', 'DeploymentHealthy', 'DeploymentFailed', 'ApplicationUpdated', 'MetricsUpdated'],
  });
  const names = useMemo(() => Object.fromEntries((apps.data ?? []).map((a) => [a.id, a.name])), [apps.data]);
  const d = dash.data;

  const columns: TableColumn<Deployment>[] = [
    { id: 'app', header: 'Application', rowHeader: true, cell: (r) => `${names[r.applicationId] ?? '…'} #${r.number}` },
    { id: 'commit', header: 'Commit', cell: (r) => <code className="sy-mono">{shortSha(r.commitSha)}</code> },
    { id: 'source', header: 'Source', cell: (r) => <SourceBadge source={r.source} /> },
    { id: 'status', header: 'Status', cell: (r) => <DeploymentStatus status={r.status} size="sm" /> },
    { id: 'when', header: 'Created', align: 'end', cell: (r) => relativeTime(r.createdAt) },
  ];

  return (
    <Page
      title="Dashboard"
      subtitle="Everything hosted on this server"
      actions={<Button variant="primary" icon={<Icon name="add" />} onClick={() => navigate('/applications/new')}>Add application</Button>}
    >
      <div className="sy-stack">
        <Dashboard title="Applications" columns={{ base: 2, md: 4 }}>
          <MetricCard title="Applications" value={d ? String(d.applications) : '–'} loading={!d} icon="product" onClick={() => navigate('/applications')} />
          <MetricCard title="Running" value={d ? String(d.running) : '–'} loading={!d} tone="success" onClick={() => navigate('/applications?status=RUNNING')} />
          <MetricCard title="Deploying" value={d ? String(d.deploying) : '–'} loading={!d} tone={d?.deploying ? 'information' : undefined} onClick={() => navigate('/deployments')} />
          <MetricCard title="Failed" value={d ? String(d.failed) : '–'} loading={!d} tone={d?.failed ? 'error' : 'success'} onClick={() => navigate('/applications?status=FAILED')} />
        </Dashboard>

        <ResourceMetrics host latest={d?.host} history={d?.hostHistory ?? []} title="Server resources" />

        <Card title="Applications" headerActions={<Button size="sm" variant="tertiary" onClick={() => navigate('/applications')}>View all</Button>}>
          {apps.data && apps.data.length === 0 ? (
            <EmptyState
              icon="product"
              title="No applications yet"
              description="Connect a GitHub repository and Shipyard will detect, build and host it."
              actions={<Button variant="primary" icon={<Icon name="add" />} onClick={() => navigate('/applications/new')}>Add application</Button>}
            />
          ) : (
            <div className="sy-app-cards">
              {(apps.data ?? []).map((a) => <ApplicationCard key={a.id} app={a} />)}
            </div>
          )}
        </Card>

        <Dashboard columns={{ base: 1, lg: 2 }}>
          <DashboardWidget>
            <Card title="Recent deployments">
              <Table
                caption="Recent deployments"
                hideCaption
                columns={columns}
                data={d?.recentDeployments ?? []}
                loading={!d}
                density="compact"
                responsive="stack"
                onRowClick={(r) => navigate(`/applications/${r.applicationId}/deployments/${r.id}`)}
                getRowLabel={(r) => `Deployment ${r.number}`}
                emptyState="No deployments yet"
              />
            </Card>
          </DashboardWidget>
          <DashboardWidget>
            <Card title="Recent activity" headerActions={<Button size="sm" variant="tertiary" onClick={() => navigate('/activity')}>View all</Button>}>
              {d && d.recentActivity.length === 0 ? <EmptyState size="sm" title="No activity yet" /> : <ActivityTimeline events={d?.recentActivity ?? []} applicationNames={names} />}
            </Card>
          </DashboardWidget>
        </Dashboard>
      </div>
    </Page>
  );
}
