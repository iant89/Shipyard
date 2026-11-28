import { useEffect, useState } from 'react';
import { Card, EmptyState, Terminal, Text, type TerminalCommandResult } from '@enterprise-ui/react';
import { api, errorMessage } from '../../services/api';
import type { ApplicationDetail } from '../../services/types';

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
  const [cwd, setCwd] = useState<string | null>(null);
  const [history, setHistory] = useState<string[]>([]);

  // A new deployment is a new container: start over (the Terminal is keyed by deployment).
  useEffect(() => {
    setCwd(null);
  }, [current?.id]);

  if (!current) return <EmptyState icon="command-line-interfaces" title="Not deployed yet" description="The console runs commands in the application's container once it has been deployed." />;
  if (current.projectType === 'STATIC') {
    return <EmptyState icon="command-line-interfaces" title="Static application" description="Static sites are served directly by Caddy, so there is no container to run commands in." />;
  }
  const stopped = a.status === 'STOPPED';

  // The exec API has no cancellation: Ctrl+C stops waiting, and the server's own timeout ends the command.
  const run = async (command: string): Promise<TerminalCommandResult> => {
    try {
      const r = await api.applications.exec(a.id, command, cwd);
      setCwd(r.cwd);
      return { output: r.output, exitCode: r.exitCode, note: !r.timedOut && r.truncated ? 'Output truncated to 256 KB.' : undefined };
    } catch (err) {
      return { output: errorMessage(err), exitCode: -1, tone: 'error' };
    }
  };

  return (
    <Card
      title="Console"
      subtitle={`Runs commands in ${current.containerName ?? 'the container'} as the image's user. One command at a time; interactive programs (vi, less, top) are not supported.`}
    >
      <Terminal
        key={current.id}
        className="sy-terminal"
        prompt={`${cwd ?? '~'} $`}
        onCommand={run}
        history={history}
        onHistoryChange={setHistory}
        disabled={stopped}
        emptyMessage={stopped ? 'The application is stopped. Start it to run commands.' : <>Type a command, e.g. <code>ls -la</code> or <code>cat /data/setup-token</code>.</>}
        actions={<Text size="sm" tone="secondary" className="sy-terminal-hint">↑/↓ history · Ctrl+C cancels · Ctrl+L or <code className="sy-mono">clear</code> clears · recorded in Activity</Text>}
        downloadFileName={`${a.name}-console.log`}
        maxEntries={MAX_ENTRIES}
        height="min(65vh, 32rem)"
      />
    </Card>
  );
}
