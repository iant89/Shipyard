import { useState } from 'react';
import { AlertDialog, Badge, Button, Dialog, Form, Icon, IconButton, Input, Link, MessageStrip, Switch, Table, type TableColumn } from '@enterprise-ui/react';
import { api } from '../../services/api';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { ApplicationDetail, Domain } from '../../services/types';
import { appUrl, formatDateTime, isLocalHostname, localHostnameHelp } from '../../services/format';
import { useResource } from '../../hooks/useResource';

/** Domain management (spec section 28). Caddy obtains certificates automatically for TLS domains. */
export function DomainsTab({ detail, onChange }: { detail: ApplicationDetail; onChange: () => void }) {
  const a = detail.summary;
  const { busy, run } = useAsyncAction();
  // When the platform serves plain HTTP, per-domain HTTPS switches do nothing.
  const info = useResource(() => api.platform.info(), []).data;
  const httpsEnabled = info?.https !== false;
  const [adding, setAdding] = useState(false);
  const [hostname, setHostname] = useState('');
  // Until chosen: off for local names (no public certificate for devstick.local), on otherwise.
  const [tlsChoice, setTlsChoice] = useState<boolean | null>(null);
  const tls = tlsChoice ?? !isLocalHostname(hostname);
  const preview = hostname.trim() && isLocalHostname(hostname) ? appUrl(hostname.trim().toLowerCase(), tls && httpsEnabled, info?.httpPort) : null;
  const [removing, setRemoving] = useState<Domain | null>(null);

  const add = async (e: React.FormEvent) => {
    e.preventDefault();
    const r = await run('add', () => api.applications.addDomain(a.id, hostname, tls, false), (d) => `${d.hostname} added`);
    if (r) {
      setAdding(false);
      setHostname('');
      setTlsChoice(null);
      onChange();
    }
  };

  const columns: TableColumn<Domain>[] = [
    { id: 'host', header: 'Hostname', rowHeader: true, cell: (d) => (
      <span className="sy-row">
        <Link href={appUrl(d.hostname, d.tlsEnabled && httpsEnabled, info?.httpPort)} target="_blank" rel="noreferrer">{d.hostname}</Link>
        {d.primary && <Badge tone="information">Primary</Badge>}
      </span>
    ) },
    { id: 'tls', header: 'HTTPS', cell: (d) => (
      <Switch aria-label={`HTTPS for ${d.hostname}`} checked={d.tlsEnabled && httpsEnabled} disabled={!httpsEnabled}
        onChange={(v) => run('tls', () => api.applications.updateDomain(a.id, d.id, { tlsEnabled: v }), `HTTPS ${v ? 'enabled' : 'disabled'} for ${d.hostname}`).then(onChange)} />
    ) },
    { id: 'created', header: 'Added', cell: (d) => formatDateTime(d.createdAt) },
    { id: 'actions', header: 'Actions', align: 'end', cell: (d) => (
      <span className="sy-row" style={{ justifyContent: 'flex-end' }}>
        {!d.primary && <Button size="sm" variant="tertiary" onClick={() => run('primary', () => api.applications.updateDomain(a.id, d.id, { primary: true }), `${d.hostname} is now primary`).then(onChange)}>Make primary</Button>}
        <IconButton size="sm" icon={<Icon name="delete" />} label={`Remove ${d.hostname}`} onClick={() => setRemoving(d)} />
      </span>
    ) },
  ];

  return (
    <div className="sy-stack">
      <MessageStrip tone="information">
        Point each hostname's DNS (A/AAAA record) at this server. With HTTPS on, Caddy requests and renews the TLS certificate automatically;
        turn it off for LAN-only names such as <code className="sy-mono">app.home.arpa</code>.
      </MessageStrip>
      {!httpsEnabled && (
        <MessageStrip tone="warning">
          HTTPS is disabled for this platform (<code className="sy-mono">SHIPYARD_HTTPS=false</code>), so every domain is served over plain HTTP
          and per-domain HTTPS switches are ignored.
        </MessageStrip>
      )}
      {info?.mdns?.enabled && !info.mdns.running && detail.domains.some((d) => d.hostname.endsWith('.local')) && (
        <MessageStrip tone="warning" title="The mDNS responder is not running">
          .local hostnames are not published on your network. On the server, check <code className="sy-mono">docker compose ps mdns</code> and{' '}
          <code className="sy-mono">docker compose logs mdns</code>.
        </MessageStrip>
      )}
      <Table caption="Domains" columns={columns} data={detail.domains} getRowId={(d) => d.id} density="compact" responsive="stack" emptyState="No domains. Add one to make the application reachable." />
      <div><Button variant="primary" icon={<Icon name="add" />} onClick={() => setAdding(true)}>Add domain</Button></div>

      <Dialog open={adding} onClose={() => setAdding(false)} title="Add domain" size="sm"
        footer={<><Button onClick={() => setAdding(false)}>Cancel</Button><Button variant="primary" type="submit" form="domain-form" loading={busy === 'add'}>Add</Button></>}>
        <Form id="domain-form" onSubmit={add} aria-label="Add domain">
          <Input label="Hostname" required value={hostname} onChange={(e) => setHostname(e.target.value)} placeholder="www.example.com" autoFocus
            description={preview ? localHostnameHelp(preview, hostname.trim().toLowerCase(), !!info?.mdns?.enabled) : undefined} />
          <Switch label="HTTPS (automatic certificate)" checked={tls && httpsEnabled} disabled={!httpsEnabled} onChange={setTlsChoice} />
        </Form>
      </Dialog>
      <AlertDialog open={!!removing} tone="error" title={`Remove ${removing?.hostname}?`} confirmLabel="Remove" onCancel={() => setRemoving(null)}
        onConfirm={async () => {
          const d = removing!;
          setRemoving(null);
          await run('remove', () => api.applications.removeDomain(a.id, d.id), `${d.hostname} removed`);
          onChange();
        }}>
        Caddy stops routing this hostname to the application immediately.
      </AlertDialog>
    </div>
  );
}
