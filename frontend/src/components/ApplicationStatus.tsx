import { StatusIndicator, type Tone } from '@enterprise-ui/react';
import type { ApplicationStatus as Status } from '../services/types';
import { humanize } from '../services/format';

export const applicationTone: Record<Status, Tone> = {
  RUNNING: 'success',
  BUILDING: 'information',
  DEPLOYING: 'information',
  STARTING: 'information',
  ROLLING_BACK: 'warning',
  STOPPED: 'neutral',
  CREATED: 'neutral',
  CRASHED: 'error',
  FAILED: 'error',
};

/** Status of an application, e.g. "● Running". */
export function ApplicationStatus({ status, size }: { status: Status; size?: 'sm' | 'md' }) {
  return (
    <StatusIndicator tone={applicationTone[status] ?? 'neutral'} size={size}>
      {humanize(status)}
    </StatusIndicator>
  );
}
