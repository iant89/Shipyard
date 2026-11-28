import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bar, Button, Card, CopyButton, DescriptionList, EmptyState, Icon, Link, MessageStrip, Page, StatusIndicator, Switch, BusyIndicator } from '@enterprise-ui/react';
import { api } from '../services/api';
import { useResource } from '../hooks/useResource';
import { clearUpdatePending, useUpdateProgress } from '../components/PlatformUpdateProgress';
import { PlatformUpdateTimeline } from '../components/PlatformUpdateTimeline';
import { CommitRef } from '../components/CommitRef';
import { duration, formatDateTime, formatTime, shortSha } from '../services/format';
import { appHref } from '../app/platformMode';
import type { PlatformUpdateStatus } from '../services/types';

/**
 * The platform self-update screen: the update's pipeline, status and live log,
 * laid out like the application deployment page. While the update runs the log
 * fills in every couple of seconds; when the update succeeds the page reloads
 * itself to load the new version's interface.
 */
export function PlatformUpdatePage() {
  const navigate = useNavigate();
  const version = useResource(() => api.platform.version(), []);
  const progress = useUpdateProgress(version.data?.update, true, true);
  const { status, restarting, reloading } = progress;

  const v = version.data;
  const repoUrl = v?.repository ? `https://github.com/${v.repository}` : null;
  const reload = () => {
    clearUpdatePending();
    window.location.reload();
  };

  const settingsCrumb = (e: React.MouseEvent) => { e.preventDefault(); navigate('/settings'); };

  if (version.loading && !status) return <BusyIndicator active text="Loading update…" style={{ minHeight: '50vh' }} />;
  if (!status) {
    return (
      <Page
        title="Shipyard update"
        breadcrumbs={[{ label: 'Settings', href: appHref('/settings'), onClick: settingsCrumb }, { label: 'Shipyard update' }]}
      >
        {restarting ? (
          <MessageStrip tone="information" title="Shipyard is restarting" announce="polite">
            This page reconnects when it is back. Your applications keep running.
          </MessageStrip>
        ) : (
          <EmptyState
            icon="download"
            title="No update has been run"
            description="Updates started from Settings → Shipyard updates show their status and log here."
          />
        )}
      </Page>
    );
  }

  const running = status.state === 'RUNNING';
  return (
    <Page
      title="Shipyard update"
      subtitle={status.fromSha && status.toSha
        ? `${shortSha(status.fromSha)} → ${shortSha(status.toSha)}${v?.branch ? ` on ${v.branch}` : ''}`
        : 'Updating Shipyard to a newer version'}
      titleAddon={<UpdateStateIndicator state={status.state} />}
      breadcrumbs={[{ label: 'Settings', href: appHref('/settings'), onClick: settingsCrumb }, { label: 'Shipyard update' }]}
      actions={
        !running ? (
          <Button icon={<Icon name="synchronize" />} onClick={reload}>
            {status.state === 'SUCCEEDED' ? 'Reload' : 'Reload page'}
          </Button>
        ) : undefined
      }
    >
      <div className="sy-stack">
        {restarting && (
          <MessageStrip tone="information" title="Shipyard is restarting" announce="polite">
            This page reconnects when it is back. Your applications keep running.
          </MessageStrip>
        )}
        {status.state === 'SUCCEEDED' && reloading && (
          <MessageStrip tone="success" title="Update complete" announce="polite">
            Reloading the page to use the new version…
          </MessageStrip>
        )}
        {status.state === 'FAILED' && (
          <MessageStrip tone="error" title="Update failed" announce="polite">{status.message}</MessageStrip>
        )}
        {status.state === 'ROLLED_BACK' && (
          <MessageStrip tone="warning" title="Update rolled back" announce="polite">{status.message}</MessageStrip>
        )}

        <Card title="Pipeline">
          <PlatformUpdateTimeline status={status} />
        </Card>

        <Card title="Details">
          <DescriptionList
            columns={4}
            items={[
              { term: 'From', description: <CommitRef sha={status.fromSha} repositoryUrl={repoUrl} />, span: 2 },
              { term: 'To', description: <CommitRef sha={status.toSha} repositoryUrl={repoUrl} />, span: 2 },
              { term: 'Branch', description: v?.branch ?? '–' },
              { term: 'Repository', description: v?.repository ? <Link href={`https://github.com/${v.repository}`} target="_blank" rel="noreferrer">{v.repository}</Link> : '–' },
              { term: 'Started', description: formatDateTime(status.startedAt) },
              { term: 'Finished', description: formatDateTime(status.finishedAt) },
              { term: 'Duration', description: duration(status.startedAt, status.finishedAt) },
              { term: 'Result', description: <UpdateStateIndicator state={status.state} /> },
              { term: 'Message', description: status.message ?? '–', span: 2 },
            ]}
          />
        </Card>

        <UpdateLogCard log={status.log} live={running} restarting={restarting} toSha={status.toSha} />
      </div>
    </Page>
  );
}

/** Status pill for the update, mirroring the deployment status indicator. */
export function UpdateStateIndicator({ state }: { state: PlatformUpdateStatus['state'] }) {
  const map = {
    RUNNING: { tone: 'information', label: 'Updating' },
    SUCCEEDED: { tone: 'success', label: 'Updated' },
    FAILED: { tone: 'error', label: 'Failed' },
    ROLLED_BACK: { tone: 'warning', label: 'Rolled back' },
  } as const;
  const s = map[state];
  return <StatusIndicator tone={s.tone}>{s.label}</StatusIndicator>;
}

/**
 * The updater's log: each line the helper writes is `[timestamp] message`, with
 * raw git and docker output appended in between. Lines are polled from the
 * update status, so the view follows the update through the restart.
 */
function UpdateLogCard({ log, live, restarting, toSha }: { log: string[]; live: boolean; restarting: boolean; toSha: string | null }) {
  const [follow, setFollow] = useState(true);
  const [wrap, setWrap] = useState(false);
  const ref = useRef<HTMLPreElement>(null);
  useEffect(() => {
    if (follow && ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, [log, follow]);
  const lines = useMemo(() => log.map(parseLine), [log]);
  const text = log.join('\n');

  const download = () => {
    const blob = new Blob([`${text}\n`], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `shipyard-update-${shortSha(toSha)}.log`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <Card title="Log" headerActions={live ? <StatusIndicator tone="information" size="sm">{restarting ? 'Reconnecting…' : 'Live'}</StatusIndicator> : undefined}>
      <Bar
        design="subheader"
        startContent={
          <div className="sy-row">
            <Switch label="Follow" checked={follow} onChange={setFollow} />
            <Switch label="Wrap lines" checked={wrap} onChange={setWrap} />
          </div>
        }
        endContent={
          <div className="sy-row">
            <CopyButton value={text} size="sm" label="Copy" />
            <Button size="sm" icon={<Icon name="download" />} onClick={download}>Download</Button>
          </div>
        }
      />
      <pre ref={ref} className="sy-log" data-wrap={wrap} tabIndex={0} aria-label="Update log" aria-live="off">
        {lines.length === 0 ? (
          <div className="sy-log-empty">{live ? 'Waiting for output…' : 'No output recorded.'}</div>
        ) : (
          lines.map((l, i) => (
            <div key={i} className="sy-log-line" data-level={l.level}>
              <span className="sy-log-no">{i + 1}</span>
              <span className="sy-log-time">{l.time}</span>
              <span className="sy-log-text">{l.text}</span>
            </div>
          ))
        )}
      </pre>
    </Card>
  );
}

/** Splits `[timestamp] message` lines into a time column and text, and tints problems. */
function parseLine(line: string): { time: string; text: string; level?: 'WARN' | 'ERROR' } {
  const m = /^\[([^\]]+)\]\s?(.*)$/.exec(line);
  const stamp = m?.[1];
  const text = m ? m[2] : line;
  const time = !stamp ? '' : Number.isNaN(Date.parse(stamp)) ? stamp : formatTime(stamp);
  const level = /rolling back|rolled back|warn/i.test(line) ? 'WARN'
    : /error|failed|failure|could not|unhealthy|not healthy|did not|conflict/i.test(line) ? 'ERROR'
      : undefined;
  return { time, text, level };
}
