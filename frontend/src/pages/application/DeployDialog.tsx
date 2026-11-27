import { useEffect, useState } from 'react';
import { Button, Dialog, Form, Input, RadioGroup, Select } from '@enterprise-ui/react';
import { api } from '../../services/api';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import type { Deployment } from '../../services/types';

interface Props {
  open: boolean;
  onClose: () => void;
  applicationId: string;
  branch: string;
  onDeployed: (d: Deployment) => void;
}

/** Manual deployment: latest commit, another branch, or an exact commit (spec section 47). */
export function DeployDialog({ open, onClose, applicationId, branch, onDeployed }: Props) {
  const [mode, setMode] = useState('latest');
  const [selectedBranch, setSelectedBranch] = useState(branch);
  const [commit, setCommit] = useState('');
  const [branches, setBranches] = useState<string[]>([branch]);
  const { busy, run } = useAsyncAction();

  useEffect(() => {
    if (!open) return;
    setMode('latest');
    setCommit('');
    setSelectedBranch(branch);
    api.applications.branches(applicationId).then(setBranches).catch(() => setBranches([branch]));
  }, [open, applicationId, branch]);

  const ref = mode === 'branch' ? selectedBranch : mode === 'commit' ? commit.trim() : undefined;
  const invalid = mode === 'commit' && !/^[0-9a-f]{7,40}$/i.test(commit.trim());

  const submit = async (e?: React.FormEvent) => {
    e?.preventDefault();
    if (invalid) return;
    const d = await run('deploy', () => api.applications.deploy(applicationId, ref), (r) => `Deployment #${r.number} queued`);
    if (d) {
      onClose();
      onDeployed(d);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Deploy"
      size="sm"
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" type="submit" form="deploy-form" loading={busy === 'deploy'} disabled={invalid}>Deploy</Button>
        </>
      }
    >
      <Form id="deploy-form" onSubmit={submit} aria-label="Deploy">
        <RadioGroup
          label="What to deploy"
          value={mode}
          onChange={setMode}
          options={[
            { value: 'latest', label: `Latest commit on ${branch}` },
            { value: 'branch', label: 'Latest commit on another branch' },
            { value: 'commit', label: 'A specific commit' },
          ]}
        />
        {mode === 'branch' && (
          <Select label="Branch" value={selectedBranch} options={branches.map((b) => ({ value: b, label: b }))} onChange={(v) => v && setSelectedBranch(v)} />
        )}
        {mode === 'commit' && (
          <Input label="Commit SHA" value={commit} onChange={(e) => setCommit(e.target.value)} placeholder="8f3c21a" className="sy-mono"
            error={commit && invalid ? 'Enter 7 to 40 hexadecimal characters.' : undefined} autoFocus />
        )}
      </Form>
    </Dialog>
  );
}
