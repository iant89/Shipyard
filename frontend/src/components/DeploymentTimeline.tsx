import { ProcessFlow, type ProcessFlowLane, type ProcessFlowStatus } from '@enterprise-ui/react';
import type { Deployment, DeploymentStatus } from '../services/types';
import { duration, humanize, shortSha } from '../services/format';

const STAGES: { id: string; title: string; statuses: DeploymentStatus[] }[] = [
  { id: 'clone', title: 'Clone', statuses: ['CREATED', 'QUEUED', 'CLONING'] },
  { id: 'detect', title: 'Detect', statuses: ['DETECTING'] },
  { id: 'build', title: 'Build & test', statuses: ['BUILDING', 'TESTING'] },
  { id: 'deploy', title: 'Deploy & verify', statuses: ['DEPLOYING', 'STARTING', 'HEALTH_CHECK'] },
  { id: 'live', title: 'Live', statuses: ['RUNNING', 'STOPPED', 'ROLLED_BACK'] },
];

/**
 * The deployment pipeline as an ENTERPRISE-UI ProcessFlow. For finished
 * deployments the failed stage is inferred from build/runtime status.
 */
export function DeploymentTimeline({ deployment }: { deployment: Deployment }) {
  const current = currentStage(deployment);
  const failed = deployment.status === 'FAILED' || deployment.status === 'CANCELLED';
  const lanes: ProcessFlowLane[] = STAGES.map((s, i) => {
    let status: ProcessFlowStatus = 'pending';
    let statusText = 'Not started';
    if (i < current) {
      status = 'done';
      statusText = 'Done';
    } else if (i === current) {
      if (failed) {
        status = deployment.status === 'CANCELLED' ? 'warning' : 'error';
        statusText = deployment.status === 'CANCELLED' ? 'Cancelled' : 'Failed';
      } else if (s.id === 'live') {
        status = deployment.status === 'RUNNING' ? 'done' : 'warning';
        statusText = deployment.status === 'RUNNING' ? 'Serving traffic' : deployment.status === 'ROLLED_BACK' ? 'Rolled back' : 'Stopped';
      } else {
        status = 'current';
        statusText = PENDING_LABEL[deployment.status] ?? 'In progress';
      }
    }
    return {
      id: s.id,
      title: s.title,
      status,
      nodes: [{ id: `${s.id}-node`, title: stageDetail(s.id, deployment, i <= current), status, statusText }],
    };
  });
  return <ProcessFlow label={`Deployment #${deployment.number} pipeline`} lanes={lanes} />;
}

/** One line of context per stage, once the stage has been reached. */
function stageDetail(stage: string, d: Deployment, reached: boolean): string {
  if (!reached) return '–';
  switch (stage) {
    case 'clone': return d.commitSha ? `${shortSha(d.commitSha)} · ${humanize(d.source)}` : `${d.branch} · ${humanize(d.source)}`;
    case 'detect': return d.framework ?? (d.projectType ? humanize(d.projectType) : 'Inspecting');
    case 'build': return d.imageTag ? 'Image built' : d.projectType === 'STATIC' && d.buildStatus === 'SUCCEEDED' ? 'Static files' : humanize(d.buildStatus);
    case 'deploy': return d.projectType === 'STATIC' ? 'Artifact checked' : d.containerName ? 'Container health-checked' : 'Starting container';
    default: return duration(d.startedAt, d.finishedAt);
  }
}

const PENDING_LABEL: Partial<Record<DeploymentStatus, string>> = {
  CREATED: 'Queued', QUEUED: 'Queued', STARTING: 'Starting', HEALTH_CHECK: 'Health check',
};

function currentStage(d: Deployment): number {
  const direct = STAGES.findIndex((s) => s.statuses.includes(d.status));
  if (direct >= 0) return direct;
  // FAILED / CANCELLED: work out where it stopped.
  if (d.buildStatus === 'SUCCEEDED') return 3;
  if (d.buildStatus === 'FAILED' || d.buildStatus === 'CANCELLED') {
    if (d.projectType || d.framework) return 2;
    return d.commitSha ? 1 : 0;
  }
  return 0;
}
