import { Card, DescriptionList, Link, Text } from '@enterprise-ui/react';
import { useNavigate } from 'react-router-dom';
import type { ApplicationSummary } from '../services/types';
import { formatBytes, formatPercent, shortSha } from '../services/format';
import { ApplicationStatus } from './ApplicationStatus';

/** Compact application tile used on the dashboard. */
export function ApplicationCard({ app }: { app: ApplicationSummary }) {
  const navigate = useNavigate();
  const d = app.currentDeployment;
  return (
    <Card
      title={app.name}
      subtitle={app.repository ? `${app.repository.fullName} · ${app.repository.branch}` : undefined}
      headerActions={<ApplicationStatus status={app.status} size="sm" />}
      onClick={() => navigate(`/applications/${app.id}`)}
      aria-label={`${app.name}, ${app.status.toLowerCase()}`}
    >
      <DescriptionList
        columns={2}
        items={[
          { term: 'Deployment', description: d ? `#${d.number} · ${shortSha(d.commitSha)}` : 'Not deployed' },
          { term: 'Type', description: app.framework ?? (app.projectType ? app.projectType.toLowerCase() : '–') },
          { term: 'CPU', description: app.metrics ? formatPercent(app.metrics.cpuPercent, 1) : app.projectType === 'STATIC' ? 'Static' : '–' },
          { term: 'Memory', description: app.metrics ? formatBytes(app.metrics.memoryBytes) : '–' },
        ]}
      />
      {app.primaryUrl && (
        <Text as="p" size="sm" className="sy-truncate" style={{ margin: 'var(--eui-space-2) 0 0' }}>
          <Link href={app.primaryUrl} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}>{app.primaryUrl.replace(/^https?:\/\//, '')}</Link>
        </Text>
      )}
    </Card>
  );
}
