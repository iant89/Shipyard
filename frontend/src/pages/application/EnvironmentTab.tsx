import { useState } from 'react';
import { AlertDialog, Badge, Button, Checkbox, Dialog, Form, Icon, IconButton, Input, MessageStrip, PasswordInput, Table, type TableColumn } from '@enterprise-ui/react';
import { api } from '../../services/api';
import { useResource } from '../../hooks/useResource';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { EnvVar } from '../../services/types';
import { relativeTime } from '../../services/format';

/** Environment variables (spec section 29). Secret values are write-only. */
export function EnvironmentTab({ applicationId }: { applicationId: string }) {
  const vars = useResource(() => api.applications.environment(applicationId), [applicationId]);
  const { busy, run } = useAsyncAction();
  const [editing, setEditing] = useState<{ name: string; value: string; secret: boolean; existing: boolean } | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editing) return;
    // Empty value on an existing secret keeps the stored value.
    const value = editing.existing && editing.secret && editing.value === '' ? null : editing.value;
    const r = await run('save', () => api.applications.setEnv(applicationId, editing.name.trim(), value, editing.secret), `${editing.name} saved`);
    if (r) {
      vars.setData(r);
      setEditing(null);
    }
  };

  const columns: TableColumn<EnvVar>[] = [
    { id: 'name', header: 'Name', accessor: 'name', rowHeader: true, sortable: true, cell: (v) => <code className="sy-mono">{v.name}</code> },
    { id: 'value', header: 'Value', cell: (v) => <code className="sy-mono">{v.value}</code> },
    { id: 'kind', header: 'Type', cell: (v) => (v.secret ? <Badge tone="warning" icon="locked">Secret</Badge> : <Badge>Plain · build &amp; runtime</Badge>) },
    { id: 'updated', header: 'Updated', align: 'end', cell: (v) => relativeTime(v.updatedAt) },
    {
      id: 'actions', header: 'Actions', align: 'end',
      cell: (v) => (
        <span className="sy-row" style={{ justifyContent: 'flex-end' }}>
          <IconButton size="sm" icon={<Icon name="edit" />} label={`Edit ${v.name}`}
            onClick={() => setEditing({ name: v.name, value: v.secret ? '' : v.value, secret: v.secret, existing: true })} />
          <IconButton size="sm" icon={<Icon name="delete" />} label={`Delete ${v.name}`} onClick={() => setDeleting(v.name)} />
        </span>
      ),
    },
  ];

  return (
    <div className="sy-stack">
      <MessageStrip tone="information">
        Changes apply on the next deployment. Secret values are encrypted at rest, masked in every log and never returned by the API.
        Plain variables are also passed to builds as build arguments (for example <code className="sy-mono">VITE_API_URL</code>).
      </MessageStrip>
      <Table
        caption="Environment variables"
        columns={columns}
        data={vars.data ?? []}
        loading={vars.loading}
        getRowId={(v) => v.id}
        density="compact"
        responsive="stack"
        emptyState="No environment variables."
      />
      <div>
        <Button variant="primary" icon={<Icon name="add" />} onClick={() => setEditing({ name: '', value: '', secret: true, existing: false })}>Add variable</Button>
      </div>

      <Dialog open={!!editing} onClose={() => setEditing(null)} title={editing?.existing ? `Edit ${editing.name}` : 'Add variable'} size="sm"
        footer={<><Button onClick={() => setEditing(null)}>Cancel</Button><Button variant="primary" type="submit" form="env-form" loading={busy === 'save'}>Save</Button></>}>
        {editing && (
          <Form id="env-form" onSubmit={save} aria-label="Environment variable">
            <Input label="Name" required value={editing.name} disabled={editing.existing} className="sy-mono"
              onChange={(e) => setEditing({ ...editing, name: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, '_') })} placeholder="DATABASE_URL" />
            {editing.secret ? (
              <PasswordInput label="Value" value={editing.value} onChange={(e) => setEditing({ ...editing, value: e.target.value })} autoComplete="off"
                description={editing.existing ? 'Leave empty to keep the current value.' : undefined} required={!editing.existing} />
            ) : (
              <Input label="Value" value={editing.value} onChange={(e) => setEditing({ ...editing, value: e.target.value })} className="sy-mono" />
            )}
            <Checkbox label="Secret" checked={editing.secret} onChange={(v) => setEditing({ ...editing, secret: v, value: '' })}
              description="Masked everywhere; available to the running container only." />
          </Form>
        )}
      </Dialog>
      <AlertDialog open={!!deleting} tone="error" title={`Delete ${deleting}?`} confirmLabel="Delete" onCancel={() => setDeleting(null)}
        onConfirm={async () => {
          const name = deleting!;
          setDeleting(null);
          const r = await run('delete', () => api.applications.deleteEnv(applicationId, name), `${name} deleted`);
          if (r) vars.setData(r);
        }}>
        The variable is removed from the next deployment.
      </AlertDialog>
    </div>
  );
}
