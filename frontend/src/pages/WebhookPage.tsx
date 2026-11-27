import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertDialog, Badge, Button, Card, CopyButton, DescriptionList, Dialog, Icon, Link, MessageStrip, StatusIndicator, Switch, Table,
  type TableColumn, type Tone,
} from '@enterprise-ui/react';
import { api } from '../services/api';
import { useWebhook, useWebhookEvents } from '../hooks/useWebhook';
import { useAsyncAction } from '../hooks/useAsyncAction';
import type { WebhookEvent, WebhookTestResult } from '../services/types';
import { formatDateTime, humanize, relativeTime, shortSha } from '../services/format';
import { appHref } from '../app/platformMode';

const eventTone: Record<WebhookEvent['status'], Tone> = {
  RECEIVED: 'information', QUEUED: 'information', PROCESSING: 'information', DEPLOYED: 'success', IGNORED: 'neutral', FAILED: 'error',
};

const statusTone = { ACTIVE: 'success', WAITING: 'information', DISABLED: 'neutral', ERROR: 'error' } as const;

/** Webhook configuration, status and history (spec sections 49–52). */
export function WebhookPage({ applicationId }: { applicationId: string }) {
  const navigate = useNavigate();
  const hook = useWebhook(applicationId);
  const events = useWebhookEvents(applicationId);
  const { busy, run } = useAsyncAction();
  const [secret, setSecret] = useState<string | null>(null);
  const [confirmRegenerate, setConfirmRegenerate] = useState(false);
  const [test, setTest] = useState<WebhookTestResult | null>(null);
  const w = hook.data;

  const columns: TableColumn<WebhookEvent>[] = [
    { id: 'time', header: 'Received', rowHeader: true, cell: (e) => <span title={formatDateTime(e.receivedAt)}>{formatDateTime(e.receivedAt)}</span> },
    { id: 'type', header: 'Event', cell: (e) => <span className="sy-row">{e.eventType}{e.test && <Badge>Test</Badge>}</span> },
    { id: 'branch', header: 'Branch', cell: (e) => e.branch ?? '–' },
    { id: 'commit', header: 'Commit', cell: (e) => <code className="sy-mono">{shortSha(e.commitSha)}</code> },
    { id: 'author', header: 'Author', cell: (e) => e.author ?? '–' },
    { id: 'status', header: 'Status', cell: (e) => <StatusIndicator size="sm" tone={eventTone[e.status]}>{humanize(e.status)}</StatusIndicator> },
    { id: 'detail', header: 'Details', wrap: true, cell: (e) => e.error ?? (e.deploymentId ? 'Deployment created' : '–') },
  ];

  if (!w) {
    return hook.error ? <MessageStrip tone="error">{hook.error}</MessageStrip> : <Card title="Webhook" aria-busy="true">Loading…</Card>;
  }

  return (
    <div className="sy-stack">
      {w.status === 'DISABLED' && (
        <MessageStrip tone="warning" title="Automatic deployment is off">
          Push events are recorded but do not deploy. Manual deployments are still available; the webhook does not need to be deleted.
        </MessageStrip>
      )}
      {w.status === 'ERROR' && (
        <MessageStrip tone="error" title="Signature check failed">
          The last delivery had an invalid signature. Make sure the secret in GitHub matches the one shown here.
        </MessageStrip>
      )}
      <div className="sy-grid-2">
        <Card title="Webhook status" headerActions={<StatusIndicator tone={statusTone[w.status]}>{humanize(w.status)}</StatusIndicator>}>
          <DescriptionList
            columns={2}
            items={[
              { term: 'Repository', description: w.repository },
              { term: 'Branch', description: w.branch },
              { term: 'Last event', description: w.lastEvent ? `${formatDateTime(w.lastEvent.receivedAt)} (${relativeTime(w.lastEvent.receivedAt)})` : 'None received yet' },
              { term: 'Last commit', description: w.lastEvent?.commitSha ? <code className="sy-mono">{shortSha(w.lastEvent.commitSha)}</code> : '–' },
              { term: 'Last deployment', description: w.lastDeployment
                ? <Link href={appHref(`/applications/${applicationId}/deployments/${w.lastDeployment.id}`)} onClick={(e) => { e.preventDefault(); navigate(`/applications/${applicationId}/deployments/${w.lastDeployment!.id}`); }}>#{w.lastDeployment.number}</Link>
                : '–' },
              { term: 'Last deployment status', description: w.lastDeployment ? humanize(w.lastDeployment.status) : '–' },
            ]}
          />
          <div className="sy-row" style={{ marginTop: 'var(--eui-space-4)' }}>
            <Switch label="Automatic deployment" checked={w.automaticDeployment}
              onChange={(v) => run('auto', () => api.applications.updateWebhook(applicationId, { automaticDeployment: v }), v ? 'Automatic deployment enabled' : 'Automatic deployment disabled').then((r) => r && hook.setData(r))} />
            <Switch label="Deploy on push" checked={w.deployOnPush}
              onChange={(v) => run('push', () => api.applications.updateWebhook(applicationId, { deployOnPush: v }), v ? 'Deploy on push enabled' : 'Deploy on push disabled').then((r) => r && hook.setData(r))} />
          </div>
        </Card>

        <Card title="GitHub configuration" subtitle="Repository → Settings → Webhooks → Add webhook">
          <DescriptionList
            columns={1}
            items={[
              { term: 'Payload URL', description: <span className="sy-row"><code className="sy-mono" style={{ wordBreak: 'break-all' }}>{w.endpointUrl}</code><CopyButton iconOnly size="sm" value={w.endpointUrl} label="Copy payload URL" /></span> },
              { term: 'Content type', description: <code className="sy-mono">{w.contentType}</code> },
              { term: 'Secret', description: (
                <span className="sy-row">
                  <code className="sy-mono">••••••••••••••••</code>
                  <Button size="sm" variant="tertiary" icon={<Icon name="show" />} loading={busy === 'reveal'}
                    onClick={() => run('reveal', () => api.applications.revealWebhookSecret(applicationId)).then((r) => r && setSecret(r.secret))}>Reveal</Button>
                </span>
              ) },
              { term: 'Events', description: 'Just the push event' },
            ]}
          />
          <div className="sy-row" style={{ marginTop: 'var(--eui-space-4)' }}>
            <Button icon={<Icon name="sys-enter-2" />} loading={busy === 'test'}
              onClick={() => run('test', () => api.applications.testWebhook(applicationId)).then((r) => { if (r) { setTest(r); events.reload(); } })}>Test webhook</Button>
            <Button icon={<Icon name="key" />} onClick={() => setConfirmRegenerate(true)}>Regenerate secret</Button>
            {w.automaticDeployment && (
              <Button variant="tertiary" onClick={() => run('auto', () => api.applications.updateWebhook(applicationId, { automaticDeployment: false }), 'Automatic deployment disabled').then((r) => r && hook.setData(r))}>
                Disable automatic deployment
              </Button>
            )}
          </div>
        </Card>
      </div>

      <Table
        caption="Webhook history"
        columns={columns}
        data={events.data ?? []}
        loading={events.loading}
        getRowId={(e) => e.id}
        density="compact"
        responsive="stack"
        getRowHighlight={(e) => (e.status === 'FAILED' ? 'error' : undefined)}
        onRowClick={(e) => e.deploymentId && navigate(`/applications/${applicationId}/deployments/${e.deploymentId}`)}
        emptyState="No webhook events yet. Push to the repository or use Test webhook."
      />

      <Dialog open={!!secret} onClose={() => setSecret(null)} title="Webhook secret" size="sm"
        footer={<Button variant="primary" onClick={() => setSecret(null)}>Done</Button>}>
        <p style={{ marginTop: 0 }}>Paste this into the <strong>Secret</strong> field of the GitHub webhook. Every delivery is verified with HMAC-SHA256.</p>
        <span className="sy-row"><code className="sy-mono" style={{ wordBreak: 'break-all' }}>{secret}</code>{secret && <CopyButton size="sm" value={secret} label="Copy secret" />}</span>
      </Dialog>
      <Dialog open={!!test} onClose={() => setTest(null)} title="Webhook test" size="sm" tone={test?.outcome === 'UNAUTHORIZED' ? 'error' : 'success'}
        footer={<Button variant="primary" onClick={() => setTest(null)}>Close</Button>}>
        {test && (
          <DescriptionList columns={1} items={[
            { term: 'Signature', description: test.outcome === 'UNAUTHORIZED' ? 'Invalid' : 'Valid (HMAC-SHA256)' },
            { term: 'Result', description: test.message },
            { term: 'Deployment', description: 'Not triggered — test events never deploy' },
          ]} />
        )}
      </Dialog>
      <AlertDialog open={confirmRegenerate} tone="warning" title="Regenerate secret?" confirmLabel="Regenerate" onCancel={() => setConfirmRegenerate(false)}
        onConfirm={async () => {
          setConfirmRegenerate(false);
          const r = await run('regen', () => api.applications.regenerateWebhookSecret(applicationId), 'Secret regenerated');
          if (r) setSecret(r.secret);
        }}>
        Deliveries signed with the old secret will be rejected until you update the secret in GitHub.
      </AlertDialog>
    </div>
  );
}
