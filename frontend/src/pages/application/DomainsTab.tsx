import { useState } from 'react';
import { AlertDialog, Badge, Button, Dialog, Form, Icon, IconButton, Input, Link, MessageStrip, Switch, Table, type TableColumn } from '@enterprise-ui/react';
import { api } from '../../services/api';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { ApplicationDetail, Domain } from '../../services/types';
import { formatDateTime } from '../../services/format';

/** Domain management (spec section 28). Caddy obtains certificates automatically for TLS domains. */
export function DomainsTab({ detail, onChange }: { detail: ApplicationDetail; onChange: () => void }) {
  const a = detail.summary;
  const { busy, run } = useAsyncAction();
  const [adding, setAdding] = useState(false);
  const [hostname, setHostname] = useState('');
  const [tls, setTls] = useState(true);
  const [removing, setRemoving] = useState<Domain | null>(null);

  const add = async (e: React.FormEvent) => {
    e.preventDefault();
    const r = await run('add', () => api.applications.addDomain(a.id, hostname, tls, false), (d) => `${d.hostname} added`);
    if (r) {
      setAdding(false);
      setHostname('');
      onChange();
    }
  };

  const columns: TableColumn<Domain>[] = [
    { id: 'host', header: 'Hostname', rowHeader: true, cell: (d) => (
      <span className="sy-row">
        <Link href={`${d.tlsEnabled ? 'https' : 'http'}://${d.hostname}`} target="_blank" rel="noreferrer">{d.hostname}</Link>
        {d.primary && <Badge tone="information">Primary</Badge>}
      </span>
    ) },
    { id: 'tls', header: 'HTTPS', cell: (d) => (
      <Switch aria-label={`HTTPS for ${d.hostname}`} checked={d.tlsEnabled}
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
      <Table caption="Domains" columns={columns} data={detail.domains} getRowId={(d) => d.id} density="compact" responsive="stack" emptyState="No domains. Add one to make the application reachable." />
      <div><Button variant="primary" icon={<Icon name="add" />} onClick={() => setAdding(true)}>Add domain</Button></div>

      <Dialog open={adding} onClose={() => setAdding(false)} title="Add domain" size="sm"
        footer={<><Button onClick={() => setAdding(false)}>Cancel</Button><Button variant="primary" type="submit" form="domain-form" loading={busy === 'add'}>Add</Button></>}>
        <Form id="domain-form" onSubmit={add} aria-label="Add domain">
          <Input label="Hostname" required value={hostname} onChange={(e) => setHostname(e.target.value)} placeholder="www.example.com" autoFocus />
          <Switch label="HTTPS (automatic certificate)" checked={tls} onChange={setTls} />
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
