import type {
  ApplicationDetail, ApplicationMetrics, ApplicationSummary, AuditEvent, AuthStatus, DashboardData, Deployment,
  DetectionPreview, DetectionResult, Domain, EnvVar, LogLine, MetricSample, PlatformInfo, PlatformUpdateStatus, PlatformVersion, UpdateCheck, WebhookEvent, WebhookTestResult, WebhookView,
} from './types';

/** Error raised for non-2xx API responses; `message` carries the server's problem detail. */
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export const UNAUTHORIZED_EVENT = 'shipyard:unauthorized';

function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

let csrfPrimed = false;

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  // Spring Security issues the CSRF cookie on the first response; make sure we have one before writing.
  if (method !== 'GET' && !csrfToken() && !csrfPrimed) {
    csrfPrimed = true;
    await fetch('/api/auth/status', { credentials: 'same-origin' });
  }
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = csrfToken();
  if (method !== 'GET' && token) headers['X-XSRF-TOKEN'] = token;
  const res = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401 && !path.startsWith('/api/auth/')) {
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
  }
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const problem = await res.json();
      message = problem.detail || problem.message || message;
    } catch {
      // not JSON
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

const get = <T>(path: string) => request<T>('GET', path);
const post = <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {});
const put = <T>(path: string, body: unknown) => request<T>('PUT', path, body);
const del = <T>(path: string) => request<T>('DELETE', path);

export interface ServiceSettings {
  projectType?: string | null;
  buildStrategy?: string | null;
  rootDirectory?: string | null;
  installCommand?: string | null;
  buildCommand?: string | null;
  testCommand?: string | null;
  startCommand?: string | null;
  outputDirectory?: string | null;
  runtimeImage?: string | null;
  internalPort?: number | null;
  healthCheckType?: string | null;
  healthCheckPath?: string | null;
  healthCheckTimeoutSeconds?: number | null;
  cpuLimit?: number | null;
  memoryLimitMb?: number | null;
  restartPolicy?: string | null;
}

export interface CreateApplicationRequest {
  name: string;
  description?: string;
  repository: string;
  branch: string;
  accessToken?: string;
  autoDeploy: boolean;
  autoUpdate?: boolean;
  domains: string[];
  service: ServiceSettings;
  environment: { name: string; value: string; secret: boolean }[];
  deployNow: boolean;
}

export interface UpdateApplicationRequest {
  name?: string;
  description?: string;
  repository?: string;
  branch?: string;
  accessToken?: string;
  clearAccessToken?: boolean;
  autoDeploy?: boolean;
  autoRollback?: boolean;
  cancelStaleBuilds?: boolean;
  autoUpdate?: boolean;
  service?: ServiceSettings;
}

const app = (id: string) => `/api/applications/${encodeURIComponent(id)}`;

export const api = {
  auth: {
    status: () => get<AuthStatus>('/api/auth/status'),
    login: (username: string, password: string) => post<{ username: string }>('/api/auth/login', { username, password }),
    setup: (username: string, password: string) => post<{ username: string }>('/api/auth/setup', { username, password }),
    logout: () => post<void>('/api/auth/logout'),
    changePassword: (currentPassword: string, newPassword: string) => post<void>('/api/auth/password', { currentPassword, newPassword }),
  },
  dashboard: () => get<DashboardData>('/api/dashboard'),
  platform: {
    info: () => get<PlatformInfo>('/api/platform/info'),
    caddy: () => get<{ config: string; status: string }>('/api/platform/caddy'),
    metrics: () => get<{ latest: MetricSample; history: MetricSample[] }>('/api/metrics/host'),
    reloadCaddy: () => post<{ applied: boolean; message: string }>('/api/platform/caddy/reload'),
    version: (refresh = false) => get<PlatformVersion>(`/api/platform/update${refresh ? '?refresh=true' : ''}`),
    /** Undefined when Shipyard was never updated from the UI. Works without a session (the update restarts Shipyard). */
    updateStatus: () => get<PlatformUpdateStatus | undefined>('/api/platform/update/status'),
    startUpdate: (commit: string) => post<PlatformUpdateStatus>('/api/platform/update', { commit }),
    activity: (limit = 100) => get<AuditEvent[]>(`/api/activity?limit=${limit}`),
    deployments: (limit = 100) => get<Deployment[]>(`/api/deployments?limit=${limit}`),
    webhookEvents: (limit = 50) => get<WebhookEvent[]>(`/api/webhook-events?limit=${limit}`),
  },
  detect: (repository: string, branch: string, accessToken?: string, rootDirectory?: string) =>
    post<DetectionPreview>('/api/detect', { repository, branch, accessToken, rootDirectory }),
  branches: (repository: string, accessToken?: string) => post<string[]>('/api/repositories/branches', { repository, accessToken }),
  applications: {
    list: () => get<ApplicationSummary[]>('/api/applications'),
    get: (id: string) => get<ApplicationDetail>(app(id)),
    create: (body: CreateApplicationRequest) => post<ApplicationDetail>('/api/applications', body),
    update: (id: string, body: UpdateApplicationRequest) => put<ApplicationDetail>(app(id), body),
    remove: (id: string) => del<void>(app(id)),
    deploy: (id: string, ref?: string) => post<Deployment>(`${app(id)}/deploy`, ref ? { ref } : {}),
    restart: (id: string) => post<void>(`${app(id)}/restart`),
    stop: (id: string) => post<void>(`${app(id)}/stop`),
    start: (id: string) => post<void>(`${app(id)}/start`),
    rollback: (id: string, deploymentId?: string) => post<Deployment>(`${app(id)}/rollback`, deploymentId ? { deploymentId } : {}),
    detect: (id: string) => post<DetectionResult>(`${app(id)}/detect`),
    branches: (id: string) => get<string[]>(`${app(id)}/branches`),
    updates: (id: string, refresh = false) => get<UpdateCheck>(`${app(id)}/updates${refresh ? '?refresh=true' : ''}`),
    deployments: (id: string, limit = 50) => get<Deployment[]>(`${app(id)}/deployments?limit=${limit}`),
    deployment: (id: string, deploymentId: string) => get<Deployment>(`${app(id)}/deployments/${encodeURIComponent(deploymentId)}`),
    cancelDeployment: (id: string, deploymentId: string) => post<void>(`${app(id)}/deployments/${encodeURIComponent(deploymentId)}/cancel`),
    redeploy: (id: string, deploymentId: string) => post<Deployment>(`${app(id)}/deployments/${encodeURIComponent(deploymentId)}/redeploy`),
    deploymentLogs: (id: string, deploymentId: string, afterBuild = 0, afterDeployment = 0) =>
      get<{ build: LogLine[]; deployment: LogLine[] }>(
        `${app(id)}/deployments/${encodeURIComponent(deploymentId)}/logs?afterBuild=${afterBuild}&afterDeployment=${afterDeployment}`),
    logs: (id: string, tail = 500) => get<{ lines: string[]; container: string }>(`${app(id)}/logs?tail=${tail}`),
    metrics: (id: string) => get<ApplicationMetrics>(`${app(id)}/metrics`),
    environment: (id: string) => get<EnvVar[]>(`${app(id)}/environment`),
    setEnv: (id: string, name: string, value: string | null, secret: boolean) => put<EnvVar[]>(`${app(id)}/environment`, { name, value, secret }),
    deleteEnv: (id: string, name: string) => del<EnvVar[]>(`${app(id)}/environment/${encodeURIComponent(name)}`),
    domains: (id: string) => get<Domain[]>(`${app(id)}/domains`),
    addDomain: (id: string, hostname: string, tlsEnabled: boolean, primary: boolean) =>
      post<Domain>(`${app(id)}/domains`, { hostname, tlsEnabled, primary }),
    updateDomain: (id: string, domainId: string, patch: { tlsEnabled?: boolean; primary?: boolean }) =>
      put<Domain>(`${app(id)}/domains/${domainId}`, patch),
    removeDomain: (id: string, domainId: string) => del<void>(`${app(id)}/domains/${domainId}`),
    webhook: (id: string) => get<WebhookView>(`${app(id)}/webhook`),
    updateWebhook: (id: string, patch: { deployOnPush?: boolean; automaticDeployment?: boolean }) => put<WebhookView>(`${app(id)}/webhook`, patch),
    webhookEvents: (id: string, limit = 50) => get<WebhookEvent[]>(`${app(id)}/webhook/events?limit=${limit}`),
    revealWebhookSecret: (id: string) => post<{ secret: string }>(`${app(id)}/webhook/secret/reveal`),
    regenerateWebhookSecret: (id: string) => post<{ secret: string }>(`${app(id)}/webhook/secret`),
    testWebhook: (id: string) => post<WebhookTestResult>(`${app(id)}/webhook/test`),
    activity: (id: string, limit = 50) => get<AuditEvent[]>(`${app(id)}/activity?limit=${limit}`),
  },
};

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}
