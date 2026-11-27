import { api } from '../services/api';
import { useResource } from './useResource';

const DEPLOYMENT_EVENTS = ['DeploymentRequested', 'DeploymentStatusChanged', 'DeploymentStarted', 'DeploymentHealthy', 'DeploymentFailed', 'DeploymentRolledBack', 'BuildCompleted'] as const;

export function useDeployments(applicationId: string, limit = 50) {
  return useResource(() => api.applications.deployments(applicationId, limit), [applicationId, limit], {
    refreshOn: DEPLOYMENT_EVENTS,
    stream: applicationId,
  });
}

export function useDeployment(applicationId: string, deploymentId: string) {
  return useResource(() => api.applications.deployment(applicationId, deploymentId), [applicationId, deploymentId], {
    refreshOn: DEPLOYMENT_EVENTS,
    stream: applicationId,
  });
}

export function useRecentDeployments(limit = 100) {
  return useResource(() => api.platform.deployments(limit), [limit], { refreshOn: DEPLOYMENT_EVENTS, stream: null });
}
