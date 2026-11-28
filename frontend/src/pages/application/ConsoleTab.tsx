import { useEffect, useRef, useState } from 'react';
import { Bar, Button, Card, CopyButton, EmptyState, Icon, Text } from '@enterprise-ui/react';
import { api, errorMessage } from '../../services/api';
import type { ApplicationDetail } from '../../services/types';

interface Entry {
  id: number;
  cwd: string | null;
  command: string;
  output: string;
  exitCode: number | null;
  note?: string;
  running?: boolean;
}

const MAX_ENTRIES = 200;

/**
 * Runs shell commands in the application's running container, one at a time.
 * The working directory carries over between commands, so `cd` works. Not a
 * TTY: interactive programs (vi, less, top) are not supported. Output is masked
 * by the server like the logs, and every command is recorded in Activity.
 */
export function ConsoleTab({ detail }: { detail: ApplicationDetail }) {
  const a = detail.summary;
  const current = a.currentDeployment;
  const [entries, setEntries] = useState<Entry[]>([]);
  const [command, setCommand] = useState('');
  const [cwd, setCwd] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [history, setHistory] = useState<string[]>([]);
  const [historyIndex, setHistoryIndex] = useState<number | null>(null);
  const seq = useRef(0);
  const outputRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // A new deployment is a new container: start over.
  useEffect(() => {
    setEntries([]);
    setCwd(null);
  }, [current?.id]);

  useEffect(() => {
    if (outputRef.current) outputRef.current.scrollTop = outputRef.current.scrollHeight;
  }, [entries]);

  if (!current) return <EmptyState icon="command-line-interfaces" title="Not deployed yet" description="The console runs commands in the application's container once it has been deployed." />;
  if (current.projectType === 'STATIC') {
    return <EmptyState icon="command-line-interfaces" title="Static application" description="Static sites are served directly by Caddy, so there is no container to run commands in." />;
  }
  const stopped = a.status === 'STOPPED';

  const run = async (text: string) => {
    const cmd = text.trim();
    if (!cmd || busy) return;
    if (cmd === 'clear') {
      setEntries([]);
      setCommand('');
      return;
    }
    const id = ++seq.current;
    setEntries((cur) => cur.slice(-(MAX_ENTRIES - 1)).concat({ id, cwd, command: cmd, output: '', exitCode: null, running: true }));
    setHistory((h) => (h[h.length - 1] === cmd ? h : h.concat(cmd).slice(-100)));
    setHistoryIndex(null);
    setCommand('');
    setBusy(true);
    try {
      const r = await api.applications.exec(a.id, cmd, cwd);
      setCwd(r.cwd);
      const note = r.timedOut ? undefined : r.truncated ? 'Output truncated to 256 KB.' : undefined;
      setEntries((cur) => cur.map((e) => (e.id === id ? { ...e, output: r.output, exitCode: r.exitCode, note, running: false } : e)));
    } catch (err) {
      setEntries((cur) => cur.map((e) => (e.id === id ? { ...e, output: errorMessage(err), exitCode: -1, running: false } : e)));
    } finally {
      setBusy(false);
      requestAnimationFrame(() => inputRef.current?.focus());
    }
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      run(command);
    } else if (e.key === 'ArrowUp' && history.length) {
      e.preventDefault();
      const i = historyIndex === null ? history.length - 1 : Math.max(0, historyIndex - 1);
      setHistoryIndex(i);
      setCommand(history[i]);
    } else if (e.key === 'ArrowDown' && historyIndex !== null) {
      e.preventDefault();
      const i = historyIndex + 1;
      if (i >= history.length) {
        setHistoryIndex(null);
        setCommand('');
      } else {
        setHistoryIndex(i);
        setCommand(history[i]);
      }
    } else if (e.key === 'l' && e.ctrlKey) {
      e.preventDefault();
      setEntries([]);
    }
  };

  const transcript = entries.map((e) => `${prompt(e.cwd)} ${e.command}\n${e.output}`).join('\n');

  return (
    <Card
      title="Console"
      subtitle={`Runs commands in ${current.containerName ?? 'the container'} as the image's user. One command at a time; interactive programs (vi, less, top) are not supported.`}
    >
      <Bar
        design="subheader"
        startContent={<Text size="sm" tone="secondary">↑/↓ history · Ctrl+L or <code className="sy-mono">clear</code> clears · commands are recorded in Activity</Text>}
        endContent={
          <div className="sy-row">
            <CopyButton value={transcript} size="sm" label="Copy" />
            <Button size="sm" icon={<Icon name="clear-all" />} onClick={() => setEntries([])} disabled={!entries.length}>Clear</Button>
          </div>
        }
      />
      <div className="sy-console" ref={outputRef} onClick={() => window.getSelection()?.isCollapsed && inputRef.current?.focus()}>
        {entries.length === 0 && (
          <div className="sy-console-hint">
            {stopped ? 'The application is stopped. Start it to run commands.' : 'Type a command, e.g. ls -la or cat /data/setup-token'}
          </div>
        )}
        {entries.map((e) => (
          <div key={e.id} className="sy-console-entry">
            <div className="sy-console-cmd"><span className="sy-console-prompt">{prompt(e.cwd)}</span> {e.command}</div>
            {e.running ? <div className="sy-console-note">Running…</div> : (
              <>
                {e.output && <pre className="sy-console-out" data-error={e.exitCode !== 0 || undefined}>{e.output}</pre>}
                {e.exitCode !== 0 && e.exitCode !== null && <div className="sy-console-note">exit code {e.exitCode}</div>}
                {e.note && <div className="sy-console-note">{e.note}</div>}
              </>
            )}
          </div>
        ))}
        <form className="sy-console-input" onSubmit={(ev) => { ev.preventDefault(); run(command); }}>
          <label htmlFor="sy-console-command" className="sy-console-prompt">{prompt(cwd)}</label>
          <input
            id="sy-console-command"
            ref={inputRef}
            value={command}
            onChange={(ev) => { setCommand(ev.target.value); setHistoryIndex(null); }}
            onKeyDown={onKeyDown}
            disabled={busy || stopped}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            aria-label="Command"
            placeholder={busy ? 'Running…' : ''}
          />
        </form>
      </div>
    </Card>
  );
}

function prompt(cwd: string | null) {
  return `${cwd ?? '~'} $`;
}
