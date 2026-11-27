import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { DataGrid, ObjectIdentifier, Page, type DataGridColumn } from '@enterprise-ui/react';
import { useRecentDeployments } from '../hooks/useDeployments';
import { useApplications } from '../hooks/useApplications';
import { DeploymentStatus } from '../components/DeploymentStatus';
import { SourceBadge } from '../components/SourceBadge';
import type { Deployment } from '../services/types';
import { duration, formatDateTime, shortSha } from '../services/format';

/** Deployments across all applications. */
export function DeploymentsPage() {
  const navigate = useNavigate();
  const deps = useRecentDeployments(300);
  const apps = useApplications();
  const names = useMemo(() => Object.fromEntries((apps.data ?? []).map((a) => [a.id, a.name])), [apps.data]);

  const columns: DataGridColumn<Deployment>[] = [
    { id: 'app', header: 'Application', rowHeader: true, alwaysVisible: true, accessor: (d) => names[d.applicationId] ?? '', sortable: true,
      cell: (d) => <ObjectIdentifier title={names[d.applicationId] ?? '…'} text={`#${d.number}`} /> },
    { id: 'status', header: 'Status', accessor: 'status', sortable: true, cell: (d) => <DeploymentStatus status={d.status} size="sm" /> },
    { id: 'commit', header: 'Commit', cell: (d) => <ObjectIdentifier title={<code className="sy-mono">{shortSha(d.commitSha)}</code>} text={d.commitMessage ?? undefined} /> },
    { id: 'branch', header: 'Branch', accessor: 'branch' },
    { id: 'source', header: 'Source', accessor: 'source', sortable: true, cell: (d) => <SourceBadge source={d.source} /> },
    { id: 'framework', header: 'Build', cell: (d) => d.framework ?? '–' },
    { id: 'created', header: 'Created', accessor: 'createdAt', sortable: true, cell: (d) => formatDateTime(d.createdAt) },
    { id: 'duration', header: 'Duration', align: 'end', cell: (d) => duration(d.startedAt, d.finishedAt) },
  ];

  return (
    <Page title="Deployments" subtitle="Every deployment on this server, newest first">
      <DataGrid
        title="Deployments"
        columns={columns}
        data={deps.data ?? []}
        loading={deps.loading}
        getRowId={(d) => d.id}
        getRowLabel={(d) => `${names[d.applicationId] ?? 'Application'} deployment ${d.number}`}
        getRowHighlight={(d) => (d.status === 'FAILED' ? 'error' : undefined)}
        searchable
        searchPlaceholder="Search deployments"
        pageSize={25}
        defaultDensity="compact"
        responsive="stack"
        onRowClick={(d) => navigate(`/applications/${d.applicationId}/deployments/${d.id}`)}
        emptyState="No deployments yet."
      />
    </Page>
  );
}
