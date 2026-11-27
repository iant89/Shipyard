import { Timeline, type TimelineItem, type Tone } from '@enterprise-ui/react';
import type { AuditEvent } from '../services/types';
import { formatDateTime, humanize } from '../services/format';

const tone = (action: string): Tone =>
  action.includes('FAILED') ? 'error'
    : action.includes('ROLLED_BACK') ? 'warning'
      : action.includes('COMPLETED') ? 'success'
        : action.includes('STARTED') || action.includes('TRIGGERED') ? 'information'
          : 'neutral';

/** Audit events rendered as an ENTERPRISE-UI Timeline. */
export function ActivityTimeline({ events, applicationNames }: { events: AuditEvent[]; applicationNames?: Record<string, string> }) {
  const items: TimelineItem[] = events.map((e) => {
    let meta: Record<string, unknown> = {};
    try {
      meta = e.metadata ? JSON.parse(e.metadata) : {};
    } catch {
      meta = {};
    }
    const app = (meta.application as string) ?? (e.resourceId && applicationNames?.[e.resourceId]) ?? null;
    const details = Object.entries(meta)
      .filter(([k]) => k !== 'application')
      .map(([k, v]) => `${k}: ${String(v)}`)
      .join(' · ');
    return {
      id: e.id,
      title: `${humanize(e.action)}${app ? ` — ${app}` : ''}`,
      timestamp: formatDateTime(e.timestamp),
      description: [details, e.userId ? `by ${e.userId}` : null].filter(Boolean).join(' · ') || undefined,
      tone: tone(e.action),
    };
  });
  return <Timeline items={items} />;
}
