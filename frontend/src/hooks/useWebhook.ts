import { api } from '../services/api';
import { useResource } from './useResource';

const WEBHOOK_EVENTS = ['WebhookReceived', 'WebhookProcessed', 'DeploymentHealthy', 'DeploymentFailed', 'ApplicationUpdated'] as const;

export function useWebhook(applicationId: string) {
  return useResource(() => api.applications.webhook(applicationId), [applicationId], { refreshOn: WEBHOOK_EVENTS, stream: applicationId });
}

export function useWebhookEvents(applicationId: string) {
  return useResource(() => api.applications.webhookEvents(applicationId, 100), [applicationId], {
    refreshOn: [...WEBHOOK_EVENTS, 'DeploymentStatusChanged'],
    stream: applicationId,
  });
}
