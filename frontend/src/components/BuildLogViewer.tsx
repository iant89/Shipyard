import { useEffect, useMemo, useRef, useState } from 'react';
import { Bar, Button, Card, CopyButton, Icon, Switch, Tab, TabList, TabPanel, Tabs, StatusIndicator } from '@enterprise-ui/react';
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
  const [tab, setTab] = useState('deployment');
  const [follow, setFollow] = useState(true);
  const [wrap, setWrap] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setBuild([]);
    setDeployment([]);
    api.applications.deploymentLogs(applicationId, deploymentId).then(
      (r) => {
        if (cancelled) return;
        // Merge with anything that streamed in while loading.
        setBuild((cur) => mergeLines(r.build, cur));
        setDeployment((cur) => mergeLines(r.deployment, cur));
      },
      (e) => !cancelled && setError(String(e.message ?? e)),
    );
    return () => {
      cancelled = true;
    };
  }, [applicationId, deploymentId]);

  useEffect(() => {
    if (!live) return;
    return subscribe(applicationId, (e) => {
      if (e.deploymentId !== deploymentId) return;
      const d = e.data as { id: number; line: string; level?: LogLine['level'] };
      const line: LogLine = { id: d.id, deploymentId, kind: e.type === 'BuildLog' ? 'build' : 'deployment', level: d.level ?? 'INFO', line: d.line, createdAt: e.timestamp };
      if (e.type === 'BuildLog') setBuild((cur) => mergeLines(cur, [line]));
      if (e.type === 'DeploymentLog') setDeployment((cur) => mergeLines(cur, [line]));
    });
  }, [applicationId, deploymentId, live]);

  const lines = tab === 'build' ? build : deployment;
  const text = useMemo(() => lines.map((l) => l.line).join('\n'), [lines]);

  const download = () => {
    const blob = new Blob([`# Deployment logs\n${deployment.map((l) => l.line).join('\n')}\n\n# Build logs\n${build.map((l) => l.line).join('\n')}\n`], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `deployment-${deploymentId}.log`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <Card title="Logs" headerActions={live ? <StatusIndicator tone="information" size="sm">Streaming</StatusIndicator> : undefined}>
      <Tabs value={tab} onValueChange={setTab}>
        <TabList aria-label="Log type">
          <Tab value="deployment" count={deployment.length}>Deployment</Tab>
          <Tab value="build" count={build.length}>Build output</Tab>
        </TabList>
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
        {(['deployment', 'build'] as const).map((t) => (
          <TabPanel key={t} value={t} flush>
            <LogLines lines={t === 'build' ? build : deployment} follow={follow} wrap={wrap} showLevel={t === 'deployment'}
              empty={error ?? (live ? 'Waiting for output…' : 'No output recorded.')} />
          </TabPanel>
        ))}
      </Tabs>
    </Card>
  );
}

function mergeLines(a: LogLine[], b: LogLine[]): LogLine[] {
  if (b.length === 0) return a;
  const seen = new Set(a.map((l) => l.id));
  const merged = a.concat(b.filter((l) => !seen.has(l.id)));
  merged.sort((x, y) => x.id - y.id);
  return merged;
}

/** Custom log renderer: line numbers, timestamps, level tinting, auto-follow. */
export function LogLines({ lines, follow, wrap, showLevel, empty }: { lines: LogLine[]; follow: boolean; wrap: boolean; showLevel?: boolean; empty: string }) {
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
          <div key={l.id} className="sy-log-line" data-level={showLevel ? l.level : undefined}>
            <span className="sy-log-no">{i + 1}</span>
            <span className="sy-log-time">{formatTime(l.createdAt)}</span>
            <span className="sy-log-text">{l.line}</span>
          </div>
        ))
      )}
    </pre>
  );
}
