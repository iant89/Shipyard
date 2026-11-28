import type {
  ApplicationDetail, ApplicationMetrics, ApplicationSummary, AuditEvent, AuthStatus, DashboardData, Deployment,
  DetectionPreview, DetectionResult, Domain, EnvVar, LogLine, MetricSample, PlatformInfo, PlatformUpdateStatus, PlatformVersion, UpdateCheck, WebhookEvent, WebhookTestResult, WebhookView,
  CommitInfo, WorkspaceBuildResult, WorkspaceCommitDiff, WorkspaceCommitFiles, WorkspaceDirectory,
  WorkspaceFile, WorkspaceFileIndex, WorkspaceInfo, WorkspaceSearchResponse,
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

async function uploadRequest(path: string, file: File): Promise<void> {
  if (!csrfToken() && !csrfPrimed) {
    csrfPrimed = true;
    await fetch('/api/auth/status', { credentials: 'same-origin' });
  }
  const headers: Record<string, string> = { Accept: 'application/json', 'Content-Type': 'application/octet-stream' };
  const token = csrfToken();
  if (token) headers['X-XSRF-TOKEN'] = token;
  const res = await fetch(path, { method: 'POST', headers, credentials: 'same-origin', body: file });
  if (res.status === 401) window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try { const problem = await res.json(); message = problem.detail || problem.message || message; } catch { /* not JSON */ }
    throw new ApiError(res.status, message);
  }
}

async function downloadRequest(path: string): Promise<Blob> {
  const res = await fetch(path, { credentials: 'same-origin' });
  if (res.status === 401) window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try { const problem = await res.json(); message = problem.detail || problem.message || message; } catch { /* not JSON */ }
    throw new ApiError(res.status, message);
  }
  return res.blob();
}

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
  /** Container paths kept across deployments; omit to leave unchanged. */
  volumes?: string[] | null;
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
    /** Console: runs one shell command in the running container. */
    exec: (id: string, command: string, cwd: string | null) => post<ExecResult>(`${app(id)}/exec`, { command, cwd }),
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
  workspace: {
    info: (id: string) => get<WorkspaceInfo>(`${app(id)}/workspace`),
    fetch: (id: string) => post<WorkspaceInfo>(`${app(id)}/workspace/fetch`),
    history: (id: string, limit = 50) => get<CommitInfo[]>(`${app(id)}/workspace/history?limit=${limit}`),
    commitFiles: (id: string, sha: string) => get<WorkspaceCommitFiles>(`${app(id)}/workspace/history/${encodeURIComponent(sha)}/files`),
    commitDiff: (id: string, sha: string, path: string) => get<WorkspaceCommitDiff>(
      `${app(id)}/workspace/history/${encodeURIComponent(sha)}/diff?path=${encodeURIComponent(path)}`),
    files: (id: string, path = '') => get<WorkspaceDirectory>(`${app(id)}/workspace/files?path=${encodeURIComponent(path)}`),
    allFiles: (id: string) => get<WorkspaceFileIndex>(`${app(id)}/workspace/all-files`),
    read: (id: string, path: string) => get<WorkspaceFile>(`${app(id)}/workspace/file?path=${encodeURIComponent(path)}`),
    createFile: (id: string, path: string) => post<void>(`${app(id)}/workspace/file`, { path }),
    createDirectory: (id: string, path: string) => post<void>(`${app(id)}/workspace/directory`, { path }),
    write: (id: string, path: string, content: string) => put<void>(`${app(id)}/workspace/file`, { path, content }),
    rename: (id: string, path: string, name: string) => post<void>(`${app(id)}/workspace/rename`, { path, name }),
    copy: (id: string, source: string, destination: string) => post<void>(`${app(id)}/workspace/copy`, { source, destination }),
    move: (id: string, source: string, destination: string) => post<void>(`${app(id)}/workspace/move`, { source, destination }),
    remove: (id: string, path: string) => del<void>(`${app(id)}/workspace/file?path=${encodeURIComponent(path)}`),
    search: (id: string, query: string) => get<WorkspaceSearchResponse>(`${app(id)}/workspace/search?q=${encodeURIComponent(query)}`),
    upload: (id: string, directory: string, file: File) => uploadRequest(
      `${app(id)}/workspace/upload?directory=${encodeURIComponent(directory)}&name=${encodeURIComponent(file.name)}`, file),
    download: (id: string, path: string) => downloadRequest(`${app(id)}/workspace/download?path=${encodeURIComponent(path)}`),
    checkout: (id: string, branch: string) => post<WorkspaceInfo>(`${app(id)}/workspace/branch`, { branch }),
    commit: (id: string, message: string) => post<WorkspaceInfo>(`${app(id)}/workspace/commit`, { message }),
    push: (id: string) => post<WorkspaceInfo>(`${app(id)}/workspace/push`),
    build: (id: string) => post<WorkspaceBuildResult>(`${app(id)}/workspace/build`),
    deploy: (id: string, message?: string) => post<Deployment>(`${app(id)}/workspace/deploy`, { message }),
  },
};

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}

export interface ExecResult {
  exitCode: number;
  output: string;
  /** Working directory after the command (send it back with the next one). */
  cwd: string;
  truncated: boolean;
  timedOut: boolean;
  durationMs: number;
  container: string;
}
