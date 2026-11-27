import { Button, Card, DescriptionList, Icon, MessageStrip, Page, StatusIndicator } from '@enterprise-ui/react';
import { api } from '../services/api';
import { useResource } from '../hooks/useResource';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { ResourceMetrics } from '../components/ResourceMetrics';

/** Server, container runtime and reverse proxy. */
export function InfrastructurePage() {
  const info = useResource(() => api.platform.info(), []);
  const host = useResource(() => api.platform.metrics(), [], { stream: null, refreshOn: ['MetricsUpdated'] });
  const caddy = useResource(() => api.platform.caddy(), [], { stream: null, refreshOn: ['DeploymentHealthy', 'DeploymentRolledBack', 'ApplicationStopped', 'ApplicationStarted'] });
  const { busy, run } = useAsyncAction();
  const i = info.data;

  return (
    <Page title="Infrastructure" subtitle="This server, its container runtime and the Caddy reverse proxy">
      <div className="sy-stack">
        {i && !i.dockerAvailable && (
          <MessageStrip tone="error" title="Docker is not reachable">
            Builds and server applications need Docker. Check that the Docker socket is mounted into the Shipyard container.
          </MessageStrip>
        )}
        <ResourceMetrics host latest={host.data?.latest} history={host.data?.history ?? []} title="Server" />
        <div className="sy-grid-2">
          <Card title="Container runtime" headerActions={i ? <StatusIndicator tone={i.dockerAvailable ? 'success' : 'error'}>{i.dockerAvailable ? 'Connected' : 'Unavailable'}</StatusIndicator> : undefined}>
            <DescriptionList columns={2} items={[
              { term: 'Runtime', description: 'Docker' },
              { term: 'Version', description: i?.dockerVersion ?? '–' },
              { term: 'Application network', description: <code className="sy-mono">{i?.dockerNetwork ?? '–'}</code> },
              { term: 'Concurrent deployments', description: String(i?.workerThreads ?? '–') },
              { term: 'Retained deployments', description: i ? `${i.retainDeployments} per application` : '–' },
              { term: 'Data directory', description: <code className="sy-mono">{i?.dataDir ?? '–'}</code> },
            ]} />
          </Card>
          <Card title="Reverse proxy" headerActions={i ? <StatusIndicator tone={i.caddyAdminConfigured ? 'success' : 'warning'}>{i.caddyAdminConfigured ? 'Managed' : 'Not connected'}</StatusIndicator> : undefined}>
            <DescriptionList columns={1} items={[
              { term: 'Proxy', description: 'Caddy (automatic HTTPS)' },
              { term: 'Platform URL', description: i?.publicUrl ?? '–' },
              { term: 'Platform domain', description: i?.platformDomain || '–' },
              { term: 'Base domain', description: i?.baseDomain ? `*.${i.baseDomain}` : 'Not set' },
              { term: 'Last apply', description: caddy.data?.status ?? '–' },
            ]} />
            <div className="sy-row" style={{ marginTop: 'var(--eui-space-4)' }}>
              <Button icon={<Icon name="refresh" />} loading={busy === 'reload'}
                onClick={() => run('reload', () => api.platform.reloadCaddy(), (r) => r.message).then(() => caddy.reload())}>Apply configuration</Button>
            </div>
          </Card>
        </div>
        <Card title="Generated Caddyfile" subtitle="Regenerated after every deployment, rollback and domain change">
          <pre className="sy-code-block">{caddy.data?.config ?? 'Loading…'}</pre>
        </Card>
      </div>
    </Page>
  );
}
