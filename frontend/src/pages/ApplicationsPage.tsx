import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button, DataGrid, FilterBar, Icon, Link, MessageStrip, ObjectIdentifier, Page, Select, type DataGridColumn } from '@enterprise-ui/react';
import { useApplications } from '../hooks/useApplications';
import { ApplicationStatus } from '../components/ApplicationStatus';
import type { ApplicationStatus as Status, ApplicationSummary } from '../services/types';
import { formatBytes, formatPercent, humanize, relativeTime, shortSha } from '../services/format';

const STATUSES: Status[] = ['RUNNING', 'DEPLOYING', 'BUILDING', 'STARTING', 'FAILED', 'CRASHED', 'STOPPED', 'CREATED', 'ROLLING_BACK'];

export function ApplicationsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const { data, error, loading } = useApplications();
  const [search, setSearch] = useState('');
  const status = params.get('status');

  const rows = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (data ?? []).filter((a) => {
      if (status === 'FAILED' && !(a.status === 'FAILED' || a.status === 'CRASHED')) return false;
      if (status && status !== 'FAILED' && a.status !== status) return false;
      return !q || a.name.toLowerCase().includes(q) || (a.repository?.fullName ?? '').includes(q);
    });
  }, [data, search, status]);

  const columns: DataGridColumn<ApplicationSummary>[] = [
    { id: 'status', header: 'Status', accessor: 'status', sortable: true, cell: (a) => <ApplicationStatus status={a.status} size="sm" /> },
    { id: 'name', header: 'Application', accessor: 'name', sortable: true, rowHeader: true, alwaysVisible: true,
      cell: (a) => <ObjectIdentifier title={a.name} text={a.framework ?? a.description ?? undefined} /> },
    { id: 'repository', header: 'Repository', accessor: (a) => a.repository?.fullName ?? '', sortable: true },
    { id: 'branch', header: 'Branch', accessor: (a) => a.repository?.branch ?? '' },
    { id: 'deployment', header: 'Deployment', accessor: (a) => a.currentDeployment?.number ?? 0, sortable: true,
      cell: (a) => (a.currentDeployment ? `#${a.currentDeployment.number} · ${shortSha(a.currentDeployment.commitSha)}` : '–') },
    { id: 'cpu', header: 'CPU', align: 'end', accessor: (a) => a.metrics?.cpuPercent ?? -1, sortable: true,
      cell: (a) => (a.metrics ? formatPercent(a.metrics.cpuPercent, 1) : '–') },
    { id: 'memory', header: 'Memory', align: 'end', accessor: (a) => a.metrics?.memoryBytes ?? -1, sortable: true,
      cell: (a) => (a.metrics ? formatBytes(a.metrics.memoryBytes) : '–') },
    { id: 'domain', header: 'Domain', cell: (a) => a.primaryUrl
      ? <Link href={a.primaryUrl} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}>{a.primaryUrl.replace(/^https?:\/\//, '')}</Link>
      : '–' },
    { id: 'updated', header: 'Updated', accessor: 'updatedAt', sortable: true, align: 'end', cell: (a) => relativeTime(a.updatedAt) },
  ];

  return (
    <Page
      title={status === 'RUNNING' ? 'Running applications' : status === 'FAILED' ? 'Failed applications' : 'Applications'}
      subtitle="Static sites and server applications deployed from Git"
      actions={<Button variant="primary" icon={<Icon name="add" />} onClick={() => navigate('/applications/new')}>Add application</Button>}
      toolbar={
        <FilterBar liveMode activeCount={status ? 1 : 0} onClear={() => setParams({})}>
          <Select
            label="Status"
            placeholder="All statuses"
            size="sm"
            options={[{ value: 'FAILED', label: 'Failed or crashed' }, ...STATUSES.filter((s) => s !== 'FAILED' && s !== 'CRASHED').map((s) => ({ value: s, label: humanize(s) }))]}
            value={status}
            onChange={(v) => setParams(v ? { status: v } : {})}
          />
        </FilterBar>
      }
    >
      {error && <MessageStrip tone="error" title="Could not load applications">{error}</MessageStrip>}
      <DataGrid
        title="Applications"
        columns={columns}
        data={rows}
        loading={loading}
        getRowId={(a) => a.id}
        getRowLabel={(a) => a.name}
        searchable
        search={search}
        onSearchChange={setSearch}
        searchPlaceholder="Search by name or repository"
        pageSize={25}
        defaultDensity="compact"
        responsive="stack"
        onRowClick={(a) => navigate(`/applications/${a.id}`)}
        emptyState={data && data.length === 0 ? 'No applications yet. Add one to get started.' : 'No applications match the filters.'}
      />
    </Page>
  );
}
