import { Card, DescriptionList, EmptyState, LineChart, StatusIndicator } from '@enterprise-ui/react';
import { api } from '../../services/api';
import { useResource } from '../../hooks/useResource';
import type { ApplicationDetail } from '../../services/types';
import { formatDateTime, formatTime } from '../../services/format';
import { ResourceMetrics } from '../../components/ResourceMetrics';

/** Container resource usage (spec section 58). History is in-memory, about 15 minutes. */
export function MetricsTab({ detail }: { detail: ApplicationDetail }) {
  const a = detail.summary;
  const metrics = useResource(() => api.applications.metrics(a.id), [a.id], { stream: null, refreshOn: ['MetricsUpdated'] });
  const m = metrics.data;
  if (a.currentDeployment?.projectType === 'STATIC') {
    return <EmptyState icon="line-chart" title="No container metrics" description="Static sites have no container; their traffic is served by Caddy." />;
  }
  const history = m?.history ?? [];
  const chart = history.map((h) => ({
    time: formatTime(h.timestamp),
    cpu: Math.round(h.cpuPercent * 10) / 10,
    memory: Math.round(h.memoryBytes / 1048576),
    rx: Math.round(h.networkRxRate / 102.4) / 10,
    tx: Math.round(h.networkTxRate / 102.4) / 10,
  }));

  return (
    <div className="sy-stack">
      <ResourceMetrics latest={metrics.loading ? undefined : m?.latest ?? null} history={history} cpuLimit={m?.cpuLimit} memoryLimitMb={m?.memoryLimitMb} />
      <div className="sy-grid-2">
        <Card title="CPU">
          <LineChart label="CPU usage (%)" data={chart} categoryKey="time" categoryLabel="Time" series={[{ key: 'cpu', label: 'CPU %' }]}
            area height={220} includeZero loading={metrics.loading} formatValue={(v) => `${v}%`}
            referenceLines={m ? [{ value: m.cpuLimit * 100, label: 'Limit', tone: 'warning' }] : undefined} />
        </Card>
        <Card title="Memory">
          <LineChart label="Memory usage (MB)" data={chart} categoryKey="time" categoryLabel="Time" series={[{ key: 'memory', label: 'Memory MB' }]}
            area height={220} includeZero loading={metrics.loading} formatValue={(v) => `${v} MB`}
            referenceLines={m ? [{ value: m.memoryLimitMb, label: 'Limit', tone: 'warning' }] : undefined} />
        </Card>
        <Card title="Network">
          <LineChart label="Network throughput (KB/s)" data={chart} categoryKey="time" categoryLabel="Time"
            series={[{ key: 'rx', label: 'Received KB/s' }, { key: 'tx', label: 'Sent KB/s' }]} height={220} includeZero loading={metrics.loading} />
        </Card>
        <Card title="Container">
          {m?.container ? (
            <DescriptionList columns={2} items={[
              { term: 'Name', description: m.container.name },
              { term: 'State', description: <StatusIndicator size="sm" tone={m.container.running ? 'success' : 'error'}>{m.container.status}</StatusIndicator> },
              { term: 'Image', description: <code className="sy-mono">{m.container.image}</code> },
              { term: 'Restarts', description: String(m.container.restartCount) },
              { term: 'Started', description: formatDateTime(m.container.startedAt) },
              { term: 'Health', description: m.container.health ?? 'Checked by Shipyard on deploy' },
              { term: 'Internal IP', description: m.container.ipAddress ?? '–' },
              { term: 'Limits', description: `${m.cpuLimit} CPU · ${m.memoryLimitMb} MB` },
            ]} />
          ) : (
            <EmptyState size="sm" title="No running container" description="Deploy or start the application to see container details." />
          )}
        </Card>
      </div>
    </div>
  );
}
