import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertDialog, Button, Card, DescriptionList, Icon, Link, MessageStrip, RadioGroup, StatusIndicator } from '@enterprise-ui/react';
import { api } from '../../services/api';
import { useResource } from '../../hooks/useResource';
import { useAsyncAction } from '../../hooks/useAsyncAction';
import { relativeTime, shortSha } from '../../services/format';
import { markUpdatePending, PlatformUpdateStrip, useUpdateProgress } from '../../components/PlatformUpdateProgress';

const RECENT_MS = 7 * 24 * 3600_000;

/**
 * Settings → Shipyard updates: the installed commit, newer commits on its
 * branch, and a one-click update with progress through the restart.
 */
export function ShipyardUpdatesCard() {
  const navigate = useNavigate();
  const version = useResource(() => api.platform.version(), []);
  const { busy, run } = useAsyncAction();
  const [selected, setSelected] = useState('');
  const [confirming, setConfirming] = useState(false);
  const v = version.data;
  const progress = useUpdateProgress(v?.update);
  const update = progress.status;
  const running = update?.state === 'RUNNING';
  // When an update finishes, the installed commit and the list of newer commits change.
  const wasRunning = useRef(false);
  useEffect(() => {
    if (wasRunning.current && !running) version.reload();
    wasRunning.current = running;
  }, [running]); // eslint-disable-line react-hooks/exhaustive-deps

  const check = v?.check;
  const commits = check?.status === 'BEHIND' ? check.commits : [];
  useEffect(() => { setSelected(commits[0]?.sha ?? ''); }, [check?.latestSha]); // eslint-disable-line react-hooks/exhaustive-deps

  const recheck = () => run('check', () => api.platform.version(true)).then((r) => r && version.setData(r));
  const start = async () => {
    setConfirming(false);
    const s = await run('update', () => api.platform.startUpdate(selected));
    if (s) {
      markUpdatePending(selected);
      version.setData({ ...v!, update: s });
      // Follow the update on its own screen: live log, status, and a reload when it finishes.
      navigate('/settings/update');
    }
  };

  const recent = update && (running || !update.finishedAt || Date.now() - new Date(update.finishedAt).getTime() < RECENT_MS);
  const headerStatus = !check ? undefined
    : check.status === 'BEHIND' ? <StatusIndicator tone="information">{check.behind === 1 ? '1 update available' : `${check.behind} updates available`}</StatusIndicator>
      : check.status === 'UP_TO_DATE' ? <StatusIndicator tone="success">Up to date</StatusIndicator>
        : <StatusIndicator tone="warning">Could not check</StatusIndicator>;

  return (
    <Card id="updates" title="Shipyard updates" headerActions={headerStatus}>
      <div className="sy-stack">
        {recent && <PlatformUpdateStrip progress={progress} signedIn />}
        {recent && (
          <div>
            <Button size="sm" variant="tertiary" icon={<Icon name="process" />} onClick={() => navigate('/settings/update')}>
              {running ? 'View update progress' : 'Open update screen'}
            </Button>
          </div>
        )}

        <DescriptionList columns={3} items={[
          { term: 'Installed', description: v?.commit ? `${shortSha(v.commit)}${v.branch ? ` on ${v.branch}` : ''}` : version.loading ? '…' : 'Unknown' },
          { term: 'Repository', description: v?.repository ? <Link href={`https://github.com/${v.repository}`} target="_blank" rel="noreferrer">{v.repository}</Link> : '–' },
          { term: 'Last checked', description: check ? relativeTime(check.checkedAt) : '–' },
        ]} />

        {check?.status === 'UNAVAILABLE' && <MessageStrip tone="warning">{check.message}</MessageStrip>}

        {commits.length > 0 && (
          <RadioGroup
            label={`New on ${check!.branch}, newest first`}
            value={selected}
            onChange={setSelected}
            disabled={running}
            options={commits.map((c, i) => ({
              value: c.sha,
              label: i === 0 ? `${c.message} (latest)` : c.message,
              description: `${shortSha(c.sha)} · ${c.author} · ${relativeTime(c.timestamp)}`,
            }))}
          />
        )}
        {check && check.behind > commits.length && commits.length > 0 && (
          <p style={{ margin: 0, color: 'var(--eui-color-text-secondary)' }}>Showing the {commits.length} newest of {check.behind} commits.</p>
        )}

        {v && !v.canUpdate && v.reason && (
          <MessageStrip tone={commits.length ? 'warning' : 'information'} title="Updating from here is not available">
            {v.reason} You can still update on the server: <code className="sy-mono">scripts/self-update.sh</code> in the Shipyard directory.
          </MessageStrip>
        )}

        <div className="sy-row">
          {commits.length > 0 && (
            <Button variant="primary" icon={<Icon name="download" />} disabled={!v?.canUpdate || running || !selected}
              loading={busy === 'update'} onClick={() => setConfirming(true)}>
              Update to {shortSha(selected)}
            </Button>
          )}
          <Button icon={<Icon name="refresh" />} loading={busy === 'check'} disabled={running} onClick={recheck}>Check now</Button>
        </div>
      </div>

      <AlertDialog open={confirming} tone="warning" title={`Update Shipyard to ${shortSha(selected)}?`} confirmLabel="Update"
        onCancel={() => setConfirming(false)} onConfirm={start}>
        Shipyard builds the new version first, then restarts. You can follow the update on the progress screen, which reloads the
        page when the update finishes. The interface is unavailable for a minute or two and you will need to
        sign in again; your applications keep running. If the new version does not start, Shipyard rolls back to {shortSha(v?.commit)}.
      </AlertDialog>
    </Card>
  );
}
