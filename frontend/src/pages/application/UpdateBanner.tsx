import { useEffect, useState } from 'react';
import { Button, Dialog, Form, MessageStrip, RadioGroup } from '@enterprise-ui/react';
import { api } from '../../services/api';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import { relativeTime, shortSha } from '../../services/format';
import type { CommitInfo, Deployment, UpdateCheck } from '../../services/types';

interface Props {
  applicationId: string;
  /** The running deployment; the check runs again when it changes. */
  currentDeploymentId: string | null;
  /** A deployment is queued or in progress, so an update is already under way. */
  deploying: boolean;
  /** Automatic updates are on: the newest commit is deployed within 90 seconds unless it already failed. */
  autoUpdate?: boolean;
  /** The latest deployment failed and is not the running one: flag its commit so it is not retried unknowingly. */
  failed?: { sha: string | null; number: number } | null;
  onDeployed: (d: Deployment) => void;
}

const dismissKey = (applicationId: string) => `shipyard.update-dismissed.${applicationId}`;

function readDismissed(applicationId: string): string | null {
  try { return sessionStorage.getItem(dismissKey(applicationId)); } catch { return null; }
}

function writeDismissed(applicationId: string, sha: string) {
  try { sessionStorage.setItem(dismissKey(applicationId), sha); } catch { /* storage unavailable: dismiss for this view only */ }
}

const describe = (c: CommitInfo) => `${shortSha(c.sha)} · ${c.author} · ${relativeTime(c.timestamp)}`;

/**
 * Shown on the application page when the configured branch has commits newer
 * than the running deployment. One newer commit: deploy it directly. Several:
 * deploy the newest, or pick one from the list.
 */
export function UpdateBanner({ applicationId, currentDeploymentId, deploying, autoUpdate, failed, onDeployed }: Props) {
  const [check, setCheck] = useState<UpdateCheck | null>(null);
  const [dismissed, setDismissed] = useState(() => readDismissed(applicationId));
  const [choosing, setChoosing] = useState(false);
  const [selected, setSelected] = useState('');
  const { busy, run } = useAsyncAction();

  useEffect(() => {
    let cancelled = false;
    setCheck(null);
    api.applications.updates(applicationId)
      .then((c) => { if (!cancelled) setCheck(c); })
      .catch(() => { /* the banner is optional: say nothing if the check fails */ });
    return () => { cancelled = true; };
  }, [applicationId, currentDeploymentId]);

  if (!check || check.status !== 'BEHIND' || check.commits.length === 0 || deploying) return null;
  // Dismissal lasts until something newer is pushed.
  if (dismissed === check.latestSha) return null;

  const latest = check.commits[0];
  const several = check.behind > 1;
  const failedNote = (c: CommitInfo) => (failed?.sha === c.sha ? `Deployment #${failed.number} of this commit failed.` : null);

  const deploy = async (sha: string) => {
    const d = await run(`deploy-${sha}`, () => api.applications.deploy(applicationId, sha), (r) => `Deployment #${r.number} queued`);
    if (d) {
      setChoosing(false);
      onDeployed(d);
    }
  };

  const dismiss = () => {
    if (check.latestSha) writeDismissed(applicationId, check.latestSha);
    setDismissed(check.latestSha);
  };

  const openChooser = () => {
    setSelected(latest.sha);
    setChoosing(true);
  };

  return (
    <>
      <MessageStrip
        tone="information"
        title={several ? `${check.behind} newer commits are available on ${check.branch}` : `A newer commit is available on ${check.branch}`}
        dismissible
        onDismiss={dismiss}
        actions={
          <>
            <Button size="sm" variant="primary" loading={busy === `deploy-${latest.sha}` && !choosing} onClick={() => deploy(latest.sha)}>
              {several ? 'Update to latest' : 'Update'}
            </Button>
            {several && <Button size="sm" onClick={openChooser}>Choose a commit…</Button>}
          </>
        }
      >
        {several ? 'Latest: ' : ''}“{latest.message}” ({describe(latest)}). The running deployment is {shortSha(check.currentSha)}.
        {failedNote(latest) && <> {failedNote(latest)}</>}
        {autoUpdate && !failedNote(latest) && <> Automatic updates will deploy it within 90 seconds.</>}
      </MessageStrip>

      <Dialog
        open={choosing}
        onClose={() => setChoosing(false)}
        title="Choose a commit to deploy"
        size="md"
        footer={
          <>
            <Button onClick={() => setChoosing(false)}>Cancel</Button>
            <Button variant="primary" type="submit" form="update-commit-form" loading={busy === `deploy-${selected}`} disabled={!selected}>
              Deploy {shortSha(selected)}
            </Button>
          </>
        }
      >
        <Form id="update-commit-form" aria-label="Choose a commit to deploy"
          onSubmit={(e) => { e.preventDefault(); if (selected) void deploy(selected); }}>
          <RadioGroup
            label={`Commits on ${check.branch} newer than ${shortSha(check.currentSha)}, newest first`}
            value={selected}
            onChange={setSelected}
            options={check.commits.map((c, i) => ({
              value: c.sha,
              label: i === 0 ? `${c.message} (latest)` : c.message,
              description: [describe(c), failedNote(c)].filter(Boolean).join(' · '),
            }))}
          />
          {check.behind > check.commits.length && (
            <p style={{ color: 'var(--eui-color-text-secondary)', margin: 0 }}>Showing the {check.commits.length} newest of {check.behind} commits.</p>
          )}
        </Form>
      </Dialog>
    </>
  );
}
