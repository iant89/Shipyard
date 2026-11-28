import { useEffect, useMemo, useRef, useState } from 'react';
import { Bar, Button, Card, CopyButton, Icon, Switch, StatusIndicator } from '@enterprise-ui/react';
import { api } from '../services/api';
import { subscribe } from '../services/events';
import { formatTime } from '../services/format';
import type { LogLine } from '../services/types';

interface Props {
  applicationId: string;
  deploymentId: string;
  /** Stream new lines while the deployment is in progress. */
  live: boolean;
}

/**
 * Build and deployment logs for one deployment. Lines are masked server-side
 * before they are stored or streamed, so secrets never reach the browser.
 */
export function BuildLogViewer({ applicationId, deploymentId, live }: Props) {
  const [build, setBuild] = useState<LogLine[]>([]);
  const [deployment, setDeployment] = useState<LogLine[]>([]);
  const [follow, setFollow] = useState(true);
  const [wrap, setWrap] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const lastIds = useRef({ build: 0, deployment: 0 });
  lastIds.current = { build: build[build.length - 1]?.id ?? 0, deployment: deployment[deployment.length - 1]?.id ?? 0 };

  // Fetches lines newer than what we already have. Used for the initial load,
  // as a polling fallback while live (in case the event stream is buffered or
  // reconnects and misses lines), and once more when the deployment finishes.
  const fetchNew = useRef<() => Promise<void>>(async () => {});
  useEffect(() => {
    let cancelled = false;
    setBuild([]);
    setDeployment([]);
    lastIds.current = { build: 0, deployment: 0 };
    fetchNew.current = async () => {
      const { build: b, deployment: dp } = lastIds.current;
      try {
        const r = await api.applications.deploymentLogs(applicationId, deploymentId, b, dp);
        if (cancelled) return;
        setError(null);
        setBuild((cur) => mergeLines(cur, r.build));
        setDeployment((cur) => mergeLines(cur, r.deployment));
      } catch (e) {
        if (!cancelled) setError(String((e as Error).message ?? e));
      }
    };
    fetchNew.current();
    return () => {
      cancelled = true;
    };
  }, [applicationId, deploymentId]);

  useEffect(() => {
    if (!live) {
      // Pick up the final lines written as the deployment finished.
      fetchNew.current();
      return;
    }
    const unsubscribe = subscribe(applicationId, (e) => {
      if (e.deploymentId !== deploymentId) return;
      if (e.type !== 'BuildLog' && e.type !== 'DeploymentLog') return;
      const d = e.data as { id: number; line: string; level?: LogLine['level'] };
      const line: LogLine = { id: d.id, deploymentId, kind: e.type === 'BuildLog' ? 'build' : 'deployment', level: d.level ?? 'INFO', line: d.line, createdAt: e.timestamp };
      if (e.type === 'BuildLog') setBuild((cur) => mergeLines(cur, [line]));
      else setDeployment((cur) => mergeLines(cur, [line]));
    });
    const poll = setInterval(() => fetchNew.current(), 3000);
    return () => {
      unsubscribe();
      clearInterval(poll);
    };
  }, [applicationId, deploymentId, live]);

  const lines = useMemo(() => interleave(deployment, build), [deployment, build]);
  const text = useMemo(() => lines.map((l) => `[${l.kind === 'build' ? 'build' : 'deploy'}] ${l.line}`).join('\n'), [lines]);

  const download = () => {
    const blob = new Blob([`${text}\n`], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `deployment-${deploymentId}.log`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <Card title="Logs" headerActions={live ? <StatusIndicator tone="information" size="sm">Streaming</StatusIndicator> : undefined}>
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
      <LogLines lines={lines} follow={follow} wrap={wrap} showLevel showKind
        empty={error ?? (live ? 'Waiting for output…' : 'No output recorded.')} />
    </Card>
  );
}

/**
 * Merges deployment and build lines into one chronological log. Each list
 * keeps its own order (ids are per table); lines are interleaved by timestamp.
 */
function interleave(a: LogLine[], b: LogLine[]): LogLine[] {
  const out: LogLine[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length || j < b.length) {
    if (j >= b.length || (i < a.length && a[i].createdAt <= b[j].createdAt)) out.push(a[i++]);
    else out.push(b[j++]);
  }
  return out;
}

function mergeLines(a: LogLine[], b: LogLine[]): LogLine[] {
  if (b.length === 0) return a;
  const seen = new Set(a.map((l) => l.id));
  const merged = a.concat(b.filter((l) => !seen.has(l.id)));
  merged.sort((x, y) => x.id - y.id);
  return merged;
}

/** Custom log renderer: line numbers, timestamps, level tinting, auto-follow. */
export function LogLines({ lines, follow, wrap, showLevel, showKind, empty }: { lines: LogLine[]; follow: boolean; wrap: boolean; showLevel?: boolean; showKind?: boolean; empty: string }) {
  const ref = useRef<HTMLPreElement>(null);
  useEffect(() => {
    if (follow && ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, [lines, follow]);
  return (
    <pre ref={ref} className="sy-log" data-wrap={wrap} tabIndex={0} aria-label="Log output" aria-live="off">
      {lines.length === 0 ? (
        <div className="sy-log-empty">{empty}</div>
      ) : (
        lines.map((l, i) => (
          <div key={`${l.kind}-${l.id}`} className="sy-log-line" data-level={showLevel && l.kind !== 'build' ? l.level : undefined} data-kind={showKind ? l.kind : undefined}>
            <span className="sy-log-no">{i + 1}</span>
            <span className="sy-log-time">{formatTime(l.createdAt)}</span>
            {showKind && <span className="sy-log-kind">{l.kind === 'build' ? 'build' : 'deploy'}</span>}
            <span className="sy-log-text">{l.line}</span>
          </div>
        ))
      )}
    </pre>
  );
}
