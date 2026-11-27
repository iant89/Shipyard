import { useEffect, useRef, useState } from 'react';
import { Bar, Button, Card, CopyButton, EmptyState, Icon, SearchField, StatusIndicator, Switch } from '@enterprise-ui/react';
import { api, errorMessage } from '../../services/api';
import type { ApplicationDetail, LogLine } from '../../services/types';
import { LogLines } from '../../components/BuildLogViewer';

const MAX_LINES = 5000;

/** Live application (container) logs, streamed over SSE. Secret values are masked by the server. */
export function LogsTab({ detail }: { detail: ApplicationDetail }) {
  const a = detail.summary;
  const current = a.currentDeployment;
  const [lines, setLines] = useState<LogLine[]>([]);
  const [live, setLive] = useState(true);
  const [follow, setFollow] = useState(true);
  const [wrap, setWrap] = useState(true);
  const [filter, setFilter] = useState('');
  const [status, setStatus] = useState<'connecting' | 'streaming' | 'paused' | 'error'>('connecting');
  const [error, setError] = useState<string | null>(null);
  const seq = useRef(0);

  const toLine = (text: string): LogLine => {
    // docker --timestamps prefix: 2026-11-27T09:14:32.123456789Z message
    const m = text.match(/^(\d{4}-\d{2}-\d{2}T\S+)\s(.*)$/);
    const body = m ? m[2] : text;
    return {
      id: ++seq.current, deploymentId: current?.id ?? '', kind: 'deployment', createdAt: m ? m[1] : new Date().toISOString(), line: body,
      level: /\b(error|exception|fatal|panic)\b/i.test(body) ? 'ERROR' : /\bwarn(ing)?\b/i.test(body) ? 'WARN' : 'INFO',
    };
  };

  useEffect(() => {
    if (!current || current.projectType === 'STATIC') return;
    if (!live) {
      setStatus('paused');
      return;
    }
    setLines([]);
    setStatus('connecting');
    const source = new EventSource(`/api/applications/${a.id}/logs/stream?tail=300`, { withCredentials: true });
    source.addEventListener('log', (e) => {
      setStatus('streaming');
      const line = toLine((e as MessageEvent).data);
      setLines((cur) => (cur.length >= MAX_LINES ? cur.slice(cur.length - MAX_LINES + 1) : cur).concat(line));
    });
    source.onopen = () => setStatus('streaming');
    source.onerror = () => setStatus(source.readyState === EventSource.CLOSED ? 'error' : 'connecting');
    return () => source.close();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [a.id, current?.id, live]);

  const loadSnapshot = async () => {
    try {
      const r = await api.applications.logs(a.id, 1000);
      setLines(r.lines.map(toLine));
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  if (!current) return <EmptyState icon="log" title="No logs yet" description="Logs appear once the application has been deployed." />;
  if (current.projectType === 'STATIC') {
    return <EmptyState icon="log" title="Static application" description="Static sites are served directly by Caddy, so there is no application process producing logs. Build and deployment logs are on each deployment." />;
  }

  const q = filter.trim().toLowerCase();
  const shown = q ? lines.filter((l) => l.line.toLowerCase().includes(q)) : lines;

  return (
    <Card
      title={`Application logs · ${current.containerName ?? ''}`}
      headerActions={
        <StatusIndicator size="sm" tone={status === 'streaming' ? 'success' : status === 'error' ? 'error' : status === 'paused' ? 'neutral' : 'information'}>
          {status === 'streaming' ? 'Live' : status === 'paused' ? 'Paused' : status === 'error' ? 'Disconnected' : 'Connecting…'}
        </StatusIndicator>
      }
    >
      <Bar
        design="subheader"
        startContent={
          <div className="sy-row">
            <Switch label="Live" checked={live} onChange={setLive} />
            <Switch label="Follow" checked={follow} onChange={setFollow} />
            <Switch label="Wrap lines" checked={wrap} onChange={setWrap} />
            <SearchField aria-label="Filter log lines" placeholder="Filter" size="sm" value={filter} onChange={setFilter} onClear={() => setFilter('')} />
          </div>
        }
        endContent={
          <div className="sy-row">
            {!live && <Button size="sm" icon={<Icon name="refresh" />} onClick={loadSnapshot}>Refresh</Button>}
            <CopyButton size="sm" label="Copy" value={shown.map((l) => l.line).join('\n')} />
            <Button size="sm" variant="tertiary" onClick={() => setLines([])}>Clear</Button>
          </div>
        }
      />
      <LogLines lines={shown} follow={follow} wrap={wrap} showLevel empty={error ?? (q ? 'No lines match the filter.' : 'Waiting for output…')} />
    </Card>
  );
}
