import { Badge, type Tone } from '@enterprise-ui/react';
import type { DeploymentSource } from '../services/types';

const tones: Record<DeploymentSource, Tone> = {
  WEBHOOK: 'information',
  MANUAL: 'neutral',
  API: 'neutral',
  SCHEDULED: 'neutral',
  SYSTEM: 'warning',
};

export function SourceBadge({ source }: { source: DeploymentSource }) {
  return <Badge tone={tones[source]}>{source}</Badge>;
}
