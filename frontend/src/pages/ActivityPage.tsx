import { useMemo } from 'react';
import { Card, EmptyState, Page, StatusIndicator, Table, Tab, TabList, TabPanel, Tabs, type TableColumn, type Tone } from '@enterprise-ui/react';
import { api } from '../services/api';
import { useResource } from '../hooks/useResource';
import { useApplications } from '../hooks/useApplications';
import { ActivityTimeline } from '../components/ActivityTimeline';
import type { WebhookEvent } from '../services/types';
import { formatDateTime, humanize, shortSha } from '../services/format';

const tone: Record<WebhookEvent['status'], Tone> = { RECEIVED: 'information', QUEUED: 'information', PROCESSING: 'information', DEPLOYED: 'success', IGNORED: 'neutral', FAILED: 'error' };

/** Audit log and webhook deliveries across the platform. */
export function ActivityPage() {
  const apps = useApplications();
  const names = useMemo(() => Object.fromEntries((apps.data ?? []).map((a) => [a.id, a.name])), [apps.data]);
  const audit = useResource(() => api.platform.activity(200), [], { stream: null, refreshOn: ['DeploymentStatusChanged', 'ApplicationUpdated', 'WebhookProcessed'] });
  const hooks = useResource(() => api.platform.webhookEvents(200), [], { stream: null, refreshOn: ['WebhookReceived', 'WebhookProcessed'] });

  const columns: TableColumn<WebhookEvent>[] = [
    { id: 'time', header: 'Received', rowHeader: true, cell: (e) => formatDateTime(e.receivedAt) },
    { id: 'app', header: 'Application', cell: (e) => names[e.applicationId] ?? '–' },
    { id: 'type', header: 'Event', accessor: 'eventType' },
    { id: 'branch', header: 'Branch', cell: (e) => e.branch ?? '–' },
    { id: 'commit', header: 'Commit', cell: (e) => <code className="sy-mono">{shortSha(e.commitSha)}</code> },
    { id: 'status', header: 'Status', cell: (e) => <StatusIndicator size="sm" tone={tone[e.status]}>{humanize(e.status)}</StatusIndicator> },
    { id: 'detail', header: 'Details', wrap: true, cell: (e) => e.error ?? '–' },
  ];

  return (
    <Page title="Activity" subtitle="Audit log and webhook deliveries">
      <Tabs defaultValue="audit">
        <TabList aria-label="Activity type">
          <Tab value="audit">Audit log</Tab>
          <Tab value="webhooks">Webhook deliveries</Tab>
        </TabList>
        <TabPanel value="audit">
          <Card>
            {audit.data && audit.data.length === 0 ? <EmptyState title="No activity yet" /> : <ActivityTimeline events={audit.data ?? []} applicationNames={names} />}
          </Card>
        </TabPanel>
        <TabPanel value="webhooks">
          <Table caption="Webhook deliveries" columns={columns} data={hooks.data ?? []} loading={hooks.loading} getRowId={(e) => e.id}
            density="compact" responsive="stack" emptyState="No webhook deliveries yet." getRowHighlight={(e) => (e.status === 'FAILED' ? 'error' : undefined)} />
        </TabPanel>
      </Tabs>
    </Page>
  );
}
