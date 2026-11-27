import { Dashboard, MetricCard } from '@enterprise-ui/react';
import type { MetricSample } from '../services/types';
import { formatBytes, formatRate } from '../services/format';

interface Props {
  latest: MetricSample | null | undefined;
  history: MetricSample[];
  /** Container limits (application metrics) */
  cpuLimit?: number;
  memoryLimitMb?: number;
  /** Host metrics include disk; container metrics do not. */
  host?: boolean;
  title?: string;
}

/** CPU / memory / disk / network tiles with sparklines (ENTERPRISE-UI MetricCard). */
export function ResourceMetrics({ latest, history, cpuLimit, memoryLimitMb, host, title = 'Resource usage' }: Props) {
  const cpu = history.map((h) => round(h.cpuPercent));
  const mem = history.map((h) => round(h.memoryBytes / 1048576));
  const net = history.map((h) => round((h.networkRxRate + h.networkTxRate) / 1024));
  const memLimit = memoryLimitMb ? memoryLimitMb * 1048576 : latest?.memoryLimitBytes;
  const memPct = latest && memLimit ? (latest.memoryBytes / memLimit) * 100 : null;
  const cpuOfLimit = latest && cpuLimit ? latest.cpuPercent / cpuLimit : null;
  const diskPct = latest && latest.diskTotalBytes ? (latest.diskUsedBytes / latest.diskTotalBytes) * 100 : null;
  const loading = latest === undefined;

  return (
    <Dashboard title={title} columns={{ base: 1, sm: 2, lg: host ? 4 : 3 }}>
      <MetricCard
        title="CPU"
        loading={loading}
        value={latest ? latest.cpuPercent.toFixed(1) : '–'}
        unit="%"
        subtitle={cpuLimit ? `Limit ${cpuLimit} ${cpuLimit === 1 ? 'core' : 'cores'}` : host ? 'Host' : undefined}
        tone={toneFor(cpuOfLimit ?? latest?.cpuPercent ?? null)}
        sparkline={cpu.length > 1 ? cpu : undefined}
      />
      <MetricCard
        title="Memory"
        loading={loading}
        value={latest ? formatBytes(latest.memoryBytes) : '–'}
        subtitle={memLimit ? `of ${formatBytes(memLimit)}${memPct != null ? ` · ${memPct.toFixed(0)}%` : ''}` : undefined}
        tone={toneFor(memPct)}
        sparkline={mem.length > 1 ? mem : undefined}
      />
      {host && (
        <MetricCard
          title="Disk"
          loading={loading}
          value={latest ? formatBytes(latest.diskUsedBytes) : '–'}
          subtitle={latest?.diskTotalBytes ? `of ${formatBytes(latest.diskTotalBytes)} · ${diskPct?.toFixed(0)}%` : 'Platform data directory'}
          tone={toneFor(diskPct)}
        />
      )}
      <MetricCard
        title="Network"
        loading={loading}
        value={latest ? formatRate(latest.networkRxRate + latest.networkTxRate) : '–'}
        subtitle={latest ? `↓ ${formatRate(latest.networkRxRate)} · ↑ ${formatRate(latest.networkTxRate)}` : undefined}
        sparkline={net.length > 1 ? net : undefined}
      />
    </Dashboard>
  );
}

function round(v: number) {
  return Math.round(v * 10) / 10;
}

function toneFor(pct: number | null): 'success' | 'warning' | 'error' | undefined {
  if (pct == null) return undefined;
  if (pct >= 90) return 'error';
  if (pct >= 75) return 'warning';
  return undefined;
}
