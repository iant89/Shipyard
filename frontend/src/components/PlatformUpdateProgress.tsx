import { useEffect, useRef, useState } from 'react';
import { Button, MessageStrip } from '@enterprise-ui/react';
import { api } from '../services/api';
import { shortSha } from '../services/format';
import type { PlatformUpdateStatus } from '../services/types';

/**
 * The commit an update started from this browser is going to. While it is set,
 * progress is polled even through the restart (which signs everyone out), the
 * login page shows it too, and sign-out flips are held back so the update
 * screen can stay up until the update finishes.
 */
const PENDING_KEY = 'shipyard.update-pending';
/** Window event fired when an update starts or finishes, so the header indicator refreshes. */
export const UPDATE_CHANGED_EVENT = 'shipyard:update-changed';

/** Last state observed for the followed update (module-level: survives the sign-in bounce). */
let lastState: PlatformUpdateStatus['state'] | null = null;
/** True once an automatic reload has been scheduled for a successful update. */
let reloadArmed = false;

export function markUpdatePending(toSha: string) {
  try { sessionStorage.setItem(PENDING_KEY, toSha); } catch { /* storage unavailable: progress shows on the Settings page only */ }
  lastState = 'RUNNING';
  window.dispatchEvent(new Event(UPDATE_CHANGED_EVENT));
}

/** The commit an update started from this browser is going to, if any. */
export function updatePendingSha(): string | null {
  try { return sessionStorage.getItem(PENDING_KEY); } catch { return null; }
}

export function clearUpdatePending() {
  try { sessionStorage.removeItem(PENDING_KEY); } catch { /* ignore */ }
}

/**
 * Whether an update started from this browser is still in flight. While true,
 * 401 responses do not sign the user out: the restart invalidates the session
 * on purpose, and the update screen needs no session to keep showing progress.
 */
export function updateInProgress(): boolean {
  if (reloadArmed) return true;
  return !!updatePendingSha() && lastState !== 'FAILED' && lastState !== 'ROLLED_BACK';
}

export interface UpdateProgress {
  status: PlatformUpdateStatus | null;
  /** The API is not answering: Shipyard is restarting. */
  restarting: boolean;
  /** The update succeeded and the page is about to reload into the new version. */
  reloading: boolean;
}

/**
 * Polls the update status while an update is running (or was started from
 * this browser and has not reported its outcome yet). When the update this
 * page is watching succeeds, the page reloads itself so the new version's
 * interface loads. Pass `untilKnown` to keep polling at least until the first
 * status response arrives (for pages that open mid-restart).
 */
export function useUpdateProgress(initial: PlatformUpdateStatus | null | undefined, enabled = true, untilKnown = false): UpdateProgress {
  const [status, setStatus] = useState<PlatformUpdateStatus | null>(initial ?? null);
  const [restarting, setRestarting] = useState(false);
  const [reloadingFlag, setReloadingFlag] = useState(false);
  const [known, setKnown] = useState(initial != null);
  const seenActive = useRef(initial?.state === 'RUNNING');
  useEffect(() => {
    setStatus(initial ?? null);
    if (initial != null) setKnown(true);
    if (initial?.state === 'RUNNING') seenActive.current = true;
  }, [initial]);

  const pending = updatePendingSha();
  const waiting = enabled && (
    status?.state === 'RUNNING' ||
    (untilKnown && !known) ||
    (!!pending && (status === null || status.toSha !== pending))
  );

  useEffect(() => {
    if (!waiting) return;
    let stopped = false;
    const poll = async () => {
      try {
        const s = await api.platform.updateStatus();
        if (stopped) return;
        setRestarting(false);
        setKnown(true);
        if (s) {
          setStatus(s);
          lastState = s.state;
          if (s.state === 'RUNNING') {
            seenActive.current = true;
          } else {
            window.dispatchEvent(new Event(UPDATE_CHANGED_EVENT));
            if (s.state === 'SUCCEEDED') {
              // The new version serves a new interface: load it. The marker is
              // cleared first so the reloaded page does not follow it again.
              clearUpdatePending();
              if (seenActive.current && !reloadArmed) {
                reloadArmed = true;
                setReloadingFlag(true);
                window.setTimeout(() => window.location.reload(), 2500);
              }
            }
          }
        }
      } catch {
        if (!stopped) setRestarting(true);
      }
    };
    void poll();
    const t = window.setInterval(poll, 2000);
    return () => { stopped = true; window.clearInterval(t); };
  }, [waiting]);

  return { status, restarting, reloading: reloadingFlag || reloadArmed };
}

const TONE = { RUNNING: 'information', SUCCEEDED: 'success', ROLLED_BACK: 'warning', FAILED: 'error' } as const;

/** One strip describing a running or finished update of Shipyard. */
export function PlatformUpdateStrip({ progress, signedIn }: { progress: UpdateProgress; signedIn: boolean }) {
  const { status, restarting, reloading } = progress;
  if (!status) return null;
  const to = shortSha(status.toSha);
  const finished = status.state !== 'RUNNING';
  const fromHere = updatePendingSha() === status.toSha;

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
      actions={finished && (fromHere || reloading) && signedIn ? (
        // The new version serves a new interface (or the old one restarted): load it.
        <Button size="sm" variant={status.state === 'SUCCEEDED' ? 'primary' : 'secondary'}
          onClick={() => { clearUpdatePending(); window.location.reload(); }}>
          {reloading ? 'Reload now' : status.state === 'SUCCEEDED' ? 'Reload' : 'Dismiss'}
        </Button>
      ) : undefined}
    >
      {restarting ? 'Shipyard is restarting. This page reconnects when it is back.'
        : status.state === 'SUCCEEDED' ? (reloading ? 'Reloading the page to use the new version…' : fromHere && signedIn ? 'Reload the page to use the new version.' : '')
          : status.message}
      {finished && fromHere && !signedIn && ' Sign in again to continue.'}
      {status.state === 'RUNNING' && <><br />Your applications keep running during the update.</>}
    </MessageStrip>
  );
}

/** On the login page: shows an update started from this browser (the restart signed the user out). */
export function PendingUpdateNotice() {
  const progress = useUpdateProgress(null, !!updatePendingSha());
  if (!updatePendingSha()) return null;
  return <PlatformUpdateStrip progress={progress} signedIn={false} />;
}
