import { useRef } from 'react';
import { ProcessFlow, type ProcessFlowLane, type ProcessFlowStatus } from '@enterprise-ui/react';
import type { PlatformUpdateStatus } from '../services/types';
import { shortSha } from '../services/format';

/**
 * The update stages of scripts/self-update.sh. Each stage is recognised by the
 * log lines it writes, so the pipeline tracks the real progress of the update.
 */
const STAGES: { id: string; title: string; done: string; match: RegExp }[] = [
  { id: 'fetch', title: 'Fetch', done: 'Checkout updated', match: /fetching origin|updating [0-9a-f]+ →/i },
  { id: 'build', title: 'Build image', done: 'Image built', match: /building|docker compose build/i },
  { id: 'backup', title: 'Stop & back up', done: 'Database backed up', match: /stopping shipyard|backing up/i },
  { id: 'restart', title: 'Restart', done: 'Shipyard restarted', match: /starting shipyard|compose up/i },
  { id: 'verify', title: 'Verify & finish', done: 'Healthy', match: /healthy|updated to|rolling back|rolled back|not healthy|did not become/i },
];

/** The furthest stage any log line has reached (the log is a tail, so remember the high-water mark). */
function stageIndex(status: PlatformUpdateStatus | null): number {
  if (!status) return 0;
  if (status.state === 'SUCCEEDED') return STAGES.length - 1;
  const text = [...status.log, status.message ?? ''].join('\n');
  let idx = 0;
  STAGES.forEach((s, i) => { if (s.match.test(text)) idx = i; });
  return idx;
}

/**
 * The Shipyard update as an ENTERPRISE-UI ProcessFlow, mirroring the
 * application deployment pipeline. Finished updates mark every stage done
 * (SUCCEEDED) or stop at the stage that failed (FAILED / ROLLED_BACK).
 */
export function PlatformUpdateTimeline({ status }: { status: PlatformUpdateStatus | null }) {
  const reached = useRef(0);
  reached.current = Math.max(reached.current, stageIndex(status));

  const state = status?.state ?? 'RUNNING';
  const current = state === 'SUCCEEDED' ? STAGES.length - 1 : reached.current;
  const lanes: ProcessFlowLane[] = STAGES.map((s, i) => {
    let laneStatus: ProcessFlowStatus = 'pending';
    let statusText = 'Not started';
    if (state === 'SUCCEEDED' || i < current) {
      laneStatus = 'done';
      statusText = i === STAGES.length - 1 && state === 'SUCCEEDED' ? `Live on ${shortSha(status?.toSha)}` : s.done;
    } else if (i === current) {
      if (state === 'FAILED') {
        laneStatus = 'error';
        statusText = 'Failed';
      } else if (state === 'ROLLED_BACK') {
        laneStatus = 'warning';
        statusText = 'Rolled back';
      } else {
        laneStatus = 'current';
        statusText = status?.message ?? 'In progress';
      }
    }
    return {
      id: s.id,
      title: s.title,
      status: laneStatus,
      nodes: [{ id: `${s.id}-node`, title: nodeDetail(s.id, status), status: laneStatus, statusText }],
    };
  });
  return <ProcessFlow label="Shipyard update pipeline" lanes={lanes} />;
}

/** One line of context per stage, once the stage has been reached. */
function nodeDetail(stage: string, status: PlatformUpdateStatus | null): string {
  if (!status) return '–';
  switch (stage) {
    case 'fetch': return status.fromSha && status.toSha ? `${shortSha(status.fromSha)} → ${shortSha(status.toSha)}` : 'Origin branch';
    case 'build': return 'shipyard:latest';
    case 'backup': return 'Database backup';
    case 'restart': return 'Shipyard container';
    default: return 'Health check';
  }
}
