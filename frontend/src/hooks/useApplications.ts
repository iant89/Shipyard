import { api } from '../services/api';
import { useResource } from './useResource';

const APP_EVENTS = ['ApplicationUpdated', 'ApplicationStarted', 'ApplicationStopped', 'ApplicationCrashed', 'DeploymentStatusChanged', 'DeploymentHealthy', 'DeploymentFailed'] as const;

export function useApplications() {
  return useResource(() => api.applications.list(), [], { refreshOn: [...APP_EVENTS, 'MetricsUpdated'], stream: null, pollMs: 30_000 });
}

export function useApplication(id: string) {
  return useResource(() => api.applications.get(id), [id], { refreshOn: APP_EVENTS, stream: id });
}
