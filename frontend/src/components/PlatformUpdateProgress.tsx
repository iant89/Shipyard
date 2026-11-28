import { useEffect, useState } from 'react';
import { Button, MessageStrip } from '@enterprise-ui/react';
import { api } from '../services/api';
import { shortSha } from '../services/format';
import type { PlatformUpdateStatus } from '../services/types';

/**
 * The commit an update started from this browser is going to. While it is set,
 * progress is polled even through the restart (which signs everyone out), and
 * the login page shows it too.
 */
const PENDING_KEY = 'shipyard.update-pending';
/** Window event fired when an update starts or finishes, so the header indicator refreshes. */
export const UPDATE_CHANGED_EVENT = 'shipyard:update-changed';

export function markUpdatePending(toSha: string) {
  try { sessionStorage.setItem(PENDING_KEY, toSha); } catch { /* storage unavailable: progress shows on the Settings page only */ }
  window.dispatchEvent(new Event(UPDATE_CHANGED_EVENT));
}

function pendingUpdate(): string | null {
  try { return sessionStorage.getItem(PENDING_KEY); } catch { return null; }
}

function clearPending() {
  try { sessionStorage.removeItem(PENDING_KEY); } catch { /* ignore */ }
}

export interface UpdateProgress {
  status: PlatformUpdateStatus | null;
  /** The API is not answering: Shipyard is restarting. */
  restarting: boolean;
}

/**
 * Polls the update status while an update is running (or was started from
 * this browser and has not reported its outcome yet).
 */
export function useUpdateProgress(initial: PlatformUpdateStatus | null | undefined, enabled = true): UpdateProgress {
  const [status, setStatus] = useState<PlatformUpdateStatus | null>(initial ?? null);
  const [restarting, setRestarting] = useState(false);
  useEffect(() => setStatus(initial ?? null), [initial]);

  const pending = pendingUpdate();
  const waiting = enabled && (status?.state === 'RUNNING' || (!!pending && (status === null || status.toSha !== pending)));

  useEffect(() => {
    if (!waiting) return;
    let stopped = false;
    const poll = async () => {
      try {
        const s = await api.platform.updateStatus();
        if (stopped) return;
        setRestarting(false);
        if (s) {
          setStatus(s);
          if (s.state !== 'RUNNING') window.dispatchEvent(new Event(UPDATE_CHANGED_EVENT));
        }
      } catch {
        if (!stopped) setRestarting(true);
      }
    };
    void poll();
    const t = window.setInterval(poll, 2000);
    return () => { stopped = true; window.clearInterval(t); };
  }, [waiting]);

  return { status, restarting };
}

const TONE = { RUNNING: 'information', SUCCEEDED: 'success', ROLLED_BACK: 'warning', FAILED: 'error' } as const;

/** One strip describing a running or finished update of Shipyard. */
export function PlatformUpdateStrip({ progress, signedIn }: { progress: UpdateProgress; signedIn: boolean }) {
  const { status, restarting } = progress;
  if (!status) return null;
  const to = shortSha(status.toSha);
  const finished = status.state !== 'RUNNING';
  const fromHere = pendingUpdate() === status.toSha;

  const title =
    status.state === 'RUNNING' ? `Updating Shipyard to ${to}`
      : status.state === 'SUCCEEDED' ? `Shipyard was updated to ${to}`
        : status.state === 'ROLLED_BACK' ? `The update to ${to} was rolled back`
          : `The update to ${to} failed`;

  return (
    <MessageStrip
      tone={TONE[status.state]}
      title={title}
      announce="polite"
      actions={finished && fromHere && signedIn ? (
        // The new version serves a new interface: load it.
        <Button size="sm" variant={status.state === 'SUCCEEDED' ? 'primary' : 'secondary'}
          onClick={() => { clearPending(); window.location.reload(); }}>
          {status.state === 'SUCCEEDED' ? 'Reload' : 'Dismiss'}
        </Button>
      ) : undefined}
    >
      {restarting ? 'Shipyard is restarting. This page reconnects when it is back.'
        : status.state === 'SUCCEEDED' ? (fromHere && signedIn ? 'Reload the page to use the new version.' : '')
          : status.message}
      {finished && fromHere && !signedIn && ' Sign in again to continue.'}
      {status.state === 'RUNNING' && <><br />Your applications keep running during the update.</>}
    </MessageStrip>
  );
}

/** On the login page: shows an update started from this browser (the restart signed the user out). */
export function PendingUpdateNotice() {
  const progress = useUpdateProgress(null, !!pendingUpdate());
  if (!pendingUpdate()) return null;
  return <PlatformUpdateStrip progress={progress} signedIn={false} />;
}
