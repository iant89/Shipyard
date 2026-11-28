/** Types mirroring the Shipyard REST API. */

export type ApplicationStatus =
  | 'CREATED' | 'BUILDING' | 'STARTING' | 'RUNNING' | 'STOPPED' | 'CRASHED' | 'FAILED' | 'DEPLOYING' | 'ROLLING_BACK';

export type DeploymentStatus =
  | 'CREATED' | 'QUEUED' | 'CLONING' | 'DETECTING' | 'BUILDING' | 'TESTING' | 'DEPLOYING' | 'STARTING' | 'HEALTH_CHECK'
  | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'FAILED' | 'ROLLED_BACK' | 'CANCELLED';

export type DeploymentSource = 'MANUAL' | 'WEBHOOK' | 'API' | 'SCHEDULED' | 'SYSTEM';

export interface MetricSample {
  timestamp: string;
  cpuPercent: number;
  memoryBytes: number;
  memoryLimitBytes: number;
  networkRxRate: number;
  networkTxRate: number;
  diskUsedBytes: number;
  diskTotalBytes: number;
}

export interface RepositoryView {
  provider: string;
  url: string;
  fullName: string;
  branch: string;
  privateRepository: boolean;
}

export interface Deployment {
  id: string;
  applicationId: string;
  number: number;
  commitSha: string | null;
  commitMessage: string | null;
  commitAuthor: string | null;
  branch: string;
  source: DeploymentSource;
  status: DeploymentStatus;
  buildStatus: string;
  runtimeStatus: string;
  projectType: 'STATIC' | 'SERVER' | null;
  framework: string | null;
  imageTag: string | null;
  containerName: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
}

export interface ApplicationSummary {
  id: string;
  slug: string;
  name: string;
  description: string | null;
  status: ApplicationStatus;
  repository: RepositoryView | null;
  currentDeployment: Deployment | null;
  latestDeployment: Deployment | null;
  primaryUrl: string | null;
  urls: string[];
  metrics: MetricSample | null;
  projectType: 'STATIC' | 'SERVER' | null;
  framework: string | null;
  autoDeploy: boolean;
  updatedAt: string;
}

export interface ServiceConfig {
  id: string;
  name: string;
  projectType: string | null;
  framework: string | null;
  buildStrategy: string | null;
  rootDirectory: string;
  installCommand: string | null;
  buildCommand: string | null;
  testCommand: string | null;
  startCommand: string | null;
  outputDirectory: string | null;
  packageManager: string | null;
  runtimeImage: string | null;
  internalPort: number | null;
  healthCheckType: string;
  healthCheckPath: string;
  healthCheckTimeoutSeconds: number;
  cpuLimit: number;
  memoryLimitMb: number;
  restartPolicy: string;
  restartCount: number;
}

export interface Domain {
  id: string;
  applicationId: string;
  hostname: string;
  tlsEnabled: boolean;
  primary: boolean;
  createdAt: string;
}

export interface ApplicationDetail {
  summary: ApplicationSummary;
  service: ServiceConfig;
  domains: Domain[];
  autoRollback: boolean;
  cancelStaleBuilds: boolean;
  buildConcurrency: number;
  createdAt: string;
}

export interface DetectionResult {
  projectType: 'STATIC' | 'SERVER' | null;
  buildStrategy: string | null;
  framework: string | null;
  packageManager: string | null;
  installCommand: string | null;
  buildCommand: string | null;
  testCommand: string | null;
  startCommand: string | null;
  outputDirectory: string | null;
  runtime: string | null;
  port: number | null;
  healthCheckPath: string | null;
  healthCheckType: string | null;
  source: string | null;
  notes: string[];
}

export interface DetectionPreview {
  repository: string;
  branch: string;
  commitSha: string;
  branches: string[];
  detection: DetectionResult;
}

export interface EnvVar {
  id: string;
  name: string;
  value: string;
  secret: boolean;
  updatedAt: string;
}

export interface WebhookEvent {
  id: string;
  applicationId: string;
  provider: string;
  eventType: string;
  deliveryId: string | null;
  repository: string | null;
  branch: string | null;
  commitSha: string | null;
  author: string | null;
  commitMessage: string | null;
  receivedAt: string;
  status: 'RECEIVED' | 'IGNORED' | 'QUEUED' | 'PROCESSING' | 'DEPLOYED' | 'FAILED';
  deploymentId: string | null;
  error: string | null;
  test: boolean;
}

export interface WebhookView {
  applicationId: string;
  provider: string;
  endpointUrl: string;
  contentType: string;
  events: string[];
  automaticDeployment: boolean;
  deployOnPush: boolean;
  branch: string;
  repository: string;
  status: 'ACTIVE' | 'WAITING' | 'DISABLED' | 'ERROR';
  lastEvent: WebhookEvent | null;
  lastDeployment: Deployment | null;
}

export interface WebhookTestResult {
  outcome: string;
  eventId: string | null;
  status: string | null;
  message: string;
  deploymentId: string | null;
}

export interface LogLine {
  id: number;
  deploymentId: string;
  kind: 'build' | 'deployment';
  level: 'INFO' | 'WARN' | 'ERROR';
  line: string;
  createdAt: string;
}

export interface AuditEvent {
  id: string;
  userId: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  timestamp: string;
  metadata: string | null;
}

export interface DashboardData {
  applications: number;
  running: number;
  deploying: number;
  failed: number;
  stopped: number;
  activeDeployments: number;
  host: MetricSample;
  hostHistory: MetricSample[];
  recentDeployments: Deployment[];
  recentActivity: AuditEvent[];
}

export interface ContainerInfo {
  id: string;
  name: string;
  image: string;
  status: string;
  running: boolean;
  restartCount: number;
  exitCode: number;
  startedAt: string | null;
  finishedAt: string | null;
  health: string | null;
  ipAddress: string | null;
}

export interface ApplicationMetrics {
  latest: MetricSample | null;
  history: MetricSample[];
  cpuLimit: number;
  memoryLimitMb: number;
  restartCount: number;
  container: ContainerInfo | null;
}

export interface PlatformInfo {
  name: string;
  version: string;
  dockerAvailable: boolean;
  dockerVersion: string | null;
  dockerNetwork: string;
  publicUrl: string;
  https: boolean;
  platformDomain: string | null;
  baseDomain: string | null;
  dataDir: string;
  caddyAdminConfigured: boolean;
  caddyStatus: string;
  workerThreads: number;
  retainDeployments: number;
}

export interface AuthStatus {
  setupRequired: boolean;
  authenticated: boolean;
  username: string | null;
}

export interface PlatformEvent {
  type: string;
  applicationId: string | null;
  deploymentId: string | null;
  data: Record<string, unknown>;
  timestamp: string;
}
