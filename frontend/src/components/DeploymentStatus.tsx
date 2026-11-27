import { StatusIndicator, type Tone } from '@enterprise-ui/react';
import type { DeploymentStatus as Status } from '../services/types';
import { humanize } from '../services/format';

export const IN_PROGRESS: Status[] = ['CLONING', 'DETECTING', 'BUILDING', 'TESTING', 'DEPLOYING', 'STARTING', 'HEALTH_CHECK'];
export const PENDING: Status[] = ['CREATED', 'QUEUED'];

export function deploymentTone(status: Status): Tone {
  if (status === 'RUNNING') return 'success';
  if (status === 'FAILED') return 'error';
  if (status === 'ROLLED_BACK') return 'warning';
  if (IN_PROGRESS.includes(status)) return 'information';
  return 'neutral';
}

export const isActive = (status: Status) => IN_PROGRESS.includes(status) || PENDING.includes(status);

export function DeploymentStatus({ status, size }: { status: Status; size?: 'sm' | 'md' }) {
  return (
    <StatusIndicator tone={deploymentTone(status)} size={size}>
      {status === 'HEALTH_CHECK' ? 'Health check' : humanize(status)}
    </StatusIndicator>
  );
}
