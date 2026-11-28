/**
 * Simulated Shipyard backend for the GitHub Pages demo. It implements the
 * same surface as services/api.ts entirely in the browser: seeded
 * applications and history, a deployment pipeline that streams logs,
 * webhooks, rollbacks, live metrics and container logs. Nothing is persisted;
 * reloading the page resets the demo.
 */
import type { api as realApi, CreateApplicationRequest, ServiceSettings, UpdateApplicationRequest } from '../services/api';
import type { EventTransport } from '../services/events';
import { appUrl, isLocalHostname } from '../services/format';
import type {
  ApplicationDetail, ApplicationStatus, ApplicationSummary, AuditEvent, DetectionPreview, DetectionResult, Deployment, DeploymentSource,
  DeploymentStatus, Domain, EnvVar, LogLine, MetricSample, PlatformEvent, ServiceConfig, WebhookEvent, WebhookView, CommitInfo, UpdateCheck, PlatformUpdateStatus, PlatformVersion,
} from '../services/types';

type Api = typeof realApi;
type Listener = (e: PlatformEvent) => void;

// ---------------------------------------------------------------- helpers

const MIN = 60_000;
/** The demo pretends Caddy is published on host port 8081, so local hostnames show the port. */
const DEMO_HTTP_PORT = 8081;
const now = () => Date.now();
const iso = (t: number) => new Date(t).toISOString();
let idSeq = 0;
const uid = (p: string) => `${p}-${(++idSeq).toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
const sha = () => Array.from({ length: 40 }, () => '0123456789abcdef'[Math.floor(Math.random() * 16)]).join('');
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const latency = <T,>(v: T, ms = 120): Promise<T> => sleep(ms).then(() => structuredClone(v));
const pick = <T,>(xs: T[]) => xs[Math.floor(Math.random() * xs.length)];
const slugify = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'app';

class DemoError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

const MESSAGES = [
  'Fix typo in hero section', 'Add pagination to orders endpoint', 'Bump dependencies', 'Improve error handling for webhooks',
  'Refactor auth middleware', 'Add dark mode toggle', 'Cache product images', 'Update README', 'Speed up build',
  'Add health endpoint', 'Tune connection pool', 'New pricing page', 'Fix flaky date formatting', 'Add CSV export',
];
const AUTHORS = ['iant89', 'ada', 'grace', 'linus'];

// ---------------------------------------------------------------- state

interface Profile {
  kind: 'STATIC' | 'SERVER';
  framework: string;
  strategy: string;
  packageManager: string | null;
  install: string | null;
  build: string | null;
  start: string | null;
  output: string | null;
  runtime: string | null;
  port: number | null;
  health: string;
  cpu: number; // baseline cpu %
  mem: number; // baseline MB
}

interface AppState {
  id: string;
  slug: string;
  name: string;
  description: string | null;
  status: ApplicationStatus;
  repo: string; // owner/name
  branch: string;
  privateRepo: boolean;
  autoDeploy: boolean;
  /** Poll the branch every 90 seconds and deploy new commits. */
  autoUpdate: boolean;
  autoRollback: boolean;
  cancelStaleBuilds: boolean;
  profile: Profile;
  service: ServiceConfig;
  currentId: string | null;
  deployments: Deployment[]; // newest first
  /** Commits on the configured branch, newest first (what `git log` on the remote would show). */
  branchCommits: CommitInfo[];
  logs: Map<string, { build: LogLine[]; deployment: LogLine[] }>;
  domains: Domain[];
  env: { name: string; value: string; secret: boolean; updatedAt: string }[];
  webhookEndpoint: string;
  webhookSecret: string;
  deployOnPush: boolean;
  webhookEvents: WebhookEvent[];
  metrics: MetricSample[];
  queue: string[];
  busy: boolean;
  cancelRequested: string | null;
  createdAt: string;
  updatedAt: string;
}

const apps: AppState[] = [];
const audit: AuditEvent[] = [];
const hostHistory: MetricSample[] = [];
const listeners = new Map<string, Set<Listener>>();
const logFollowers = new Map<string, Set<(line: string) => void>>();
let logLineId = 0;

function emit(type: string, applicationId: string | null, deploymentId: string | null, data: Record<string, unknown> = {}) {
  const e: PlatformEvent = { type, applicationId, deploymentId, data, timestamp: iso(now()) };
  if (applicationId) listeners.get(applicationId)?.forEach((l) => l(e));
  if (type !== 'BuildLog' && type !== 'DeploymentLog') listeners.get('*')?.forEach((l) => l(e));
}

function record(action: string, resourceType: string, resourceId: string | null, metadata: Record<string, unknown> = {}, at = now()) {
  audit.unshift({ id: uid('audit'), userId: 'demo', action, resourceType, resourceId, timestamp: iso(at), metadata: JSON.stringify(metadata) });
  if (audit.length > 300) audit.length = 300;
}

function findApp(id: string): AppState {
  const a = apps.find((x) => x.id === id);
  if (!a) throw new DemoError(404, 'Application not found');
  return a;
}

function findDeployment(a: AppState, idOrNumber: string): Deployment {
  const d = a.deployments.find((x) => x.id === idOrNumber || String(x.number) === idOrNumber);
  if (!d) throw new DemoError(404, 'Deployment not found');
  return d;
}

// ---------------------------------------------------------------- profiles & detection

const PROFILES: Record<string, Profile> = {
  vite: { kind: 'STATIC', framework: 'React + Vite', strategy: 'NODE_STATIC', packageManager: 'npm', install: 'npm ci', build: 'npm run build', start: null, output: 'dist', runtime: 'node:lts-alpine', port: null, health: '/', cpu: 0, mem: 0 },
  astro: { kind: 'STATIC', framework: 'Astro', strategy: 'NODE_STATIC', packageManager: 'pnpm', install: 'corepack enable && pnpm install --frozen-lockfile', build: 'pnpm run build', start: null, output: 'dist', runtime: 'node:lts-alpine', port: null, health: '/', cpu: 0, mem: 0 },
  spring: { kind: 'SERVER', framework: 'Spring Boot', strategy: 'MAVEN', packageManager: 'maven', install: null, build: './mvnw -B -DskipTests package', start: 'java -jar /app/app.jar', output: null, runtime: 'eclipse-temurin:21-jre', port: 8080, health: '/actuator/health', cpu: 9, mem: 380 },
  express: { kind: 'SERVER', framework: 'Express', strategy: 'NODE_SERVER', packageManager: 'npm', install: 'npm ci', build: null, start: 'npm start', output: null, runtime: 'node:lts-alpine', port: 3000, health: '/health', cpu: 4, mem: 110 },
  go: { kind: 'SERVER', framework: 'Go', strategy: 'GO', packageManager: 'go', install: null, build: 'CGO_ENABLED=0 go build -o /out/server .', start: '/app/server', output: null, runtime: 'alpine:3', port: 8080, health: '/healthz', cpu: 2, mem: 24 },
  fastapi: { kind: 'SERVER', framework: 'FastAPI', strategy: 'PYTHON', packageManager: 'pip', install: 'pip install --no-cache-dir -r requirements.txt', build: null, start: 'uvicorn main:app --host 0.0.0.0 --port 8000', output: null, runtime: 'python:3.12-slim', port: 8000, health: '/health', cpu: 5, mem: 140 },
  docker: { kind: 'SERVER', framework: 'Dockerfile', strategy: 'DOCKERFILE', packageManager: null, install: null, build: 'docker build', start: null, output: null, runtime: 'Dockerfile', port: 8080, health: '/', cpu: 3, mem: 90 },
};

function profileFor(repo: string): Profile {
  const r = repo.toLowerCase();
  if (/spring|java|orders|billing/.test(r)) return PROFILES.spring;
  if (/astro|docs|blog/.test(r)) return PROFILES.astro;
  if (/\bgo\b|-go|resizer|proxy/.test(r)) return PROFILES.go;
  if (/py|fastapi|ml|data/.test(r)) return PROFILES.fastapi;
  if (/docker|container/.test(r)) return PROFILES.docker;
  if (/api|server|bot|backend|yard/.test(r)) return PROFILES.express;
  return PROFILES.vite;
}

function repoName(input: string): string {
  const s = input.trim().replace(/^https?:\/\//, '').replace(/^github\.com\//, '').replace(/\.git$/, '').replace(/\/$/, '');
  if (!/^[\w.-]+\/[\w.-]+$/.test(s)) throw new DemoError(400, `Unsupported repository: ${input} (use owner/repository)`);
  return s;
}

function detection(p: Profile, s?: ServiceConfig): DetectionResult {
  const explicit = !!s && [s.buildCommand, s.startCommand, s.outputDirectory, s.installCommand, s.projectType].some((x) => x != null);
  return {
    projectType: (s?.projectType as 'STATIC' | 'SERVER' | null) ?? p.kind,
    buildStrategy: s?.buildStrategy ?? p.strategy,
    framework: p.framework,
    packageManager: p.packageManager,
    installCommand: s?.installCommand ?? p.install,
    buildCommand: s?.buildCommand ?? p.build,
    testCommand: s?.testCommand ?? null,
    startCommand: s?.startCommand ?? p.start,
    outputDirectory: s?.outputDirectory ?? p.output,
    runtime: s?.runtimeImage ?? p.runtime,
    port: s?.internalPort ?? p.port,
    healthCheckPath: s?.healthCheckPath ?? p.health,
    healthCheckType: 'HTTP',
    source: explicit ? 'EXPLICIT' : p.strategy === 'DOCKERFILE' ? 'DOCKERFILE' : 'FRAMEWORK',
    notes: p.strategy === 'PYTHON' ? ['Verify the Python start command; it was inferred from the project layout.']
      : p.strategy === 'DOCKERFILE' ? ['The Dockerfile declares VOLUME /data. Add it under Volumes to keep the data across deployments.'] : [],
    volumes: p.strategy === 'DOCKERFILE' ? ['/data'] : [],
  };
}

function serviceFor(appId: string, p: Profile, s?: ServiceSettings): ServiceConfig {
  const t = iso(now());
  return {
    id: uid('svc'), name: 'web', projectType: s?.projectType ?? null, framework: p.framework, buildStrategy: s?.buildStrategy ?? null,
    rootDirectory: s?.rootDirectory ?? '.', installCommand: s?.installCommand ?? null, buildCommand: s?.buildCommand ?? null,
    testCommand: s?.testCommand ?? null, startCommand: s?.startCommand ?? null, outputDirectory: s?.outputDirectory ?? null,
    packageManager: p.packageManager, runtimeImage: s?.runtimeImage ?? null, internalPort: s?.internalPort ?? null,
    healthCheckType: s?.healthCheckType ?? 'HTTP', healthCheckPath: s?.healthCheckPath ?? p.health,
    healthCheckTimeoutSeconds: s?.healthCheckTimeoutSeconds ?? 60, cpuLimit: s?.cpuLimit ?? (p.kind === 'SERVER' ? 1 : 1),
    memoryLimitMb: s?.memoryLimitMb ?? (p.strategy === 'MAVEN' ? 1024 : 512), restartPolicy: s?.restartPolicy ?? 'unless-stopped', volumes: s?.volumes ?? [],
    restartCount: 0, ...{ applicationId: appId, createdAt: t, updatedAt: t },
  } as ServiceConfig;
}

// ---------------------------------------------------------------- seeding

function newDeployment(a: AppState, source: DeploymentSource, at: number, opts: Partial<Deployment> = {}): Deployment {
  const number = (a.deployments[0]?.number ?? 0) + 1;
  return {
    id: uid('dep'), applicationId: a.id, number, commitSha: sha(), commitMessage: pick(MESSAGES), commitAuthor: pick(AUTHORS),
    branch: a.branch, source, status: 'QUEUED', buildStatus: 'PENDING', runtimeStatus: 'PENDING', projectType: a.profile.kind,
    framework: a.profile.framework, imageTag: null, containerName: null, createdAt: iso(at), startedAt: null, finishedAt: null,
    error: null, ...opts,
  };
}

function seedLogs(a: AppState, d: Deployment) {
  const t0 = new Date(d.startedAt ?? d.createdAt).getTime();
  const lines = buildScript(a, d);
  const build: LogLine[] = lines.build.map((l, i) => ({ id: ++logLineId, deploymentId: d.id, kind: 'build', level: 'INFO', line: l, createdAt: iso(t0 + 2000 + i * 400) }));
  const deployment: LogLine[] = lines.deployment
    .filter((l) => d.status !== 'FAILED' || !l.text.startsWith('Traffic') )
    .map((l, i) => ({ id: ++logLineId, deploymentId: d.id, kind: 'deployment', level: l.level, line: l.text, createdAt: iso(t0 + i * 1500) }));
  if (d.status === 'FAILED') {
    deployment.push({ id: ++logLineId, deploymentId: d.id, kind: 'deployment', level: 'ERROR', line: `Deployment failed: ${d.error}`, createdAt: d.finishedAt ?? iso(t0) });
    deployment.push({ id: ++logLineId, deploymentId: d.id, kind: 'deployment', level: 'INFO', line: 'Previous deployment remains online', createdAt: d.finishedAt ?? iso(t0) });
  }
  a.logs.set(d.id, { build, deployment });
}

function seedApp(name: string, repo: string, profile: Profile, count: number, opts: {
  domains: string[]; pendingCommits?: number; failedLatest?: boolean; neverHealthy?: boolean; description?: string; env?: AppState['env']; crashed?: boolean;
}): AppState {
  const created = now() - (count + 3) * 26 * 3600_000;
  // Stable ids so links into the demo survive a reload.
  const id = slugify(name);
  const a: AppState = {
    id, slug: slugify(name), name, description: opts.description ?? null, status: 'RUNNING', repo, branch: 'main', privateRepo: false,
    autoDeploy: true, autoUpdate: false, autoRollback: true, cancelStaleBuilds: true, profile, service: serviceFor(id, profile), currentId: null,
    deployments: [], branchCommits: [], logs: new Map(), domains: [], env: opts.env ?? [], webhookEndpoint: sha().slice(0, 40), webhookSecret: sha().slice(0, 64),
    deployOnPush: true, webhookEvents: [], metrics: [], queue: [], busy: false, cancelRequested: null, createdAt: iso(created), updatedAt: iso(now() - 40 * MIN),
  };
  opts.domains.forEach((h, i) => a.domains.push({ id: uid('dom'), applicationId: id, hostname: h, tlsEnabled: true, primary: i === 0, createdAt: iso(created) }));
  record('USER_CREATED_APPLICATION', 'APPLICATION', id, { name, repository: `https://github.com/${repo}.git` }, created);

  for (let i = 0; i < count; i++) {
    const at = created + (i + 1) * 26 * 3600_000 + Math.random() * 3600_000;
    const source: DeploymentSource = i === 0 ? 'MANUAL' : Math.random() < 0.8 ? 'WEBHOOK' : 'MANUAL';
    const last = i === count - 1;
    const fail = (last && opts.failedLatest) || opts.neverHealthy || (!last && i > 1 && Math.random() < 0.12);
    const d = newDeployment(a, source, at);
    d.id = `${a.id}-${d.number}`;
    d.startedAt = iso(at + 800);
    d.finishedAt = iso(at + (profile.kind === 'SERVER' ? 95_000 : 41_000) + Math.random() * 20_000);
    if (fail) {
      const reason = pick(profile.kind === 'SERVER' ? ['Health check failed', 'docker build failed (exit 1)'] : ['docker build failed (exit 1)']);
      Object.assign(d, { status: 'FAILED', error: reason, buildStatus: reason.startsWith('docker') ? 'FAILED' : 'SUCCEEDED', runtimeStatus: 'STOPPED' });
      if (reason === 'Health check failed') d.imageTag = `shipyard/${a.slug}:deployment-${d.number}`;
      if (last && opts.failedLatest) d.commitMessage = 'Switch to new connection pool (breaks startup)';
    } else {
      Object.assign(d, { status: 'RUNNING', buildStatus: 'SUCCEEDED', runtimeStatus: 'RUNNING' });
      if (profile.kind === 'SERVER') {
        d.imageTag = `shipyard/${a.slug}:deployment-${d.number}`;
        d.containerName = `shipyard-${a.slug}-web-${d.number}`;
      }
      const prev = a.deployments.find((x) => x.id === a.currentId);
      if (prev) Object.assign(prev, { status: 'STOPPED', runtimeStatus: 'STOPPED' });
      a.currentId = d.id;
    }
    a.deployments.unshift(d);
    a.branchCommits.unshift({ sha: d.commitSha!, author: d.commitAuthor!, message: d.commitMessage!, timestamp: d.createdAt });
    seedLogs(a, d);
    if (source === 'WEBHOOK') {
      a.webhookEvents.unshift(webhookEvent(a, d.commitSha!, d.commitAuthor!, d.commitMessage!, fail ? 'FAILED' : 'DEPLOYED', d.id, fail ? d.error : null, at - 500));
    }
    record('DEPLOYMENT_STARTED', 'DEPLOYMENT', d.id, { application: a.slug, number: d.number, source }, at);
    record(fail ? 'DEPLOYMENT_FAILED' : 'DEPLOYMENT_COMPLETED', 'DEPLOYMENT', d.id, { application: a.slug, number: d.number }, new Date(d.finishedAt!).getTime());
  }
  // Pushed but not deployed yet (automatic deployment would normally pick these up).
  for (let i = opts.pendingCommits ?? 0; i > 0; i--) {
    a.branchCommits.unshift({ sha: sha(), author: pick(AUTHORS), message: pick(MESSAGES), timestamp: iso(now() - i * 47 * MIN) });
  }
  // A couple of ignored pushes to other branches
  a.webhookEvents.splice(1, 0, { ...webhookEvent(a, sha(), 'grace', 'WIP: experiment', 'IGNORED', null, `Push to feature/redesign ignored (deployment branch is main)`, now() - 7 * 3600_000), branch: 'feature/redesign' });
  if (opts.neverHealthy) a.status = 'FAILED';
  if (opts.crashed) {
    a.status = 'CRASHED';
    a.service.restartCount = 7;
    const cur = a.deployments.find((x) => x.id === a.currentId);
    if (cur) cur.runtimeStatus = 'CRASHED';
  }
  seedMetrics(a);
  apps.push(a);
  return a;
}

function webhookEvent(a: AppState, commit: string, author: string, message: string, status: WebhookEvent['status'], deploymentId: string | null,
  error: string | null, at: number, test = false): WebhookEvent {
  return {
    id: uid('wh'), applicationId: a.id, provider: 'GITHUB', eventType: 'push', deliveryId: test ? null : `${sha().slice(0, 8)}-${sha().slice(0, 4)}`,
    repository: a.repo, branch: a.branch, commitSha: commit, author, commitMessage: message, receivedAt: iso(at), status, deploymentId, error, test,
  };
}

function sample(a: AppState, t: number, i: number): MetricSample {
  const p = a.profile;
  const wave = Math.sin(i / 9) * 0.35 + Math.sin(i / 3.7) * 0.15 + (Math.random() - 0.5) * 0.3;
  return {
    timestamp: iso(t), cpuPercent: Math.max(0.2, p.cpu * (1 + wave)), memoryBytes: Math.max(8, p.mem * (1 + wave * 0.08)) * 1048576,
    memoryLimitBytes: a.service.memoryLimitMb * 1048576, networkRxRate: Math.max(0, 40_000 * (1 + wave)), networkTxRate: Math.max(0, 95_000 * (1 + wave)),
    diskUsedBytes: 0, diskTotalBytes: 0,
  };
}

function seedMetrics(a: AppState) {
  if (a.profile.kind !== 'SERVER') return;
  for (let i = 0; i < 120; i++) a.metrics.push(sample(a, now() - (120 - i) * 5000, i));
}

function hostSample(t: number, i: number): MetricSample {
  const wave = Math.sin(i / 11) * 0.3 + (Math.random() - 0.5) * 0.25;
  return {
    timestamp: iso(t), cpuPercent: 14 * (1 + wave), memoryBytes: (5.6 + wave * 0.3) * 1073741824, memoryLimitBytes: 32 * 1073741824,
    networkRxRate: 310_000 * (1 + wave), networkTxRate: 520_000 * (1 + wave), diskUsedBytes: 182 * 1073741824, diskTotalBytes: 931 * 1073741824,
  };
}

function seed() {
  if (apps.length) return;
  seedApp('Marketing Site', 'iant89/marketing-site', PROFILES.vite, 14, {
    domains: ['www.example.com', 'example.com'], description: 'Public website', pendingCommits: 1,
    env: [{ name: 'VITE_API_URL', value: 'https://api.example.com', secret: false, updatedAt: iso(now() - 9 * 86400_000) }],
  });
  seedApp('Orders API', 'iant89/orders-api', PROFILES.spring, 22, {
    domains: ['api.example.com'], description: 'Order management REST API', failedLatest: true,
    env: [
      { name: 'DATABASE_URL', value: 'postgres://orders:s3cr3t@db/orders', secret: true, updatedAt: iso(now() - 20 * 86400_000) },
      { name: 'JWT_SECRET', value: 'x'.repeat(32), secret: true, updatedAt: iso(now() - 20 * 86400_000) },
      { name: 'SPRING_PROFILES_ACTIVE', value: 'prod', secret: false, updatedAt: iso(now() - 30 * 86400_000) },
    ],
  });
  seedApp('Devyard', 'iant89/devyard', PROFILES.express, 9, { domains: ['devyard.example.com'], description: 'Developer portal', pendingCommits: 3 });
  seedApp('Docs', 'iant89/docs', PROFILES.astro, 6, { domains: ['docs.example.com'] });
  seedApp('Image Resizer', 'iant89/image-resizer', PROFILES.go, 5, { domains: ['img.example.com'], crashed: true });
  seedApp('Legacy Billing', 'iant89/legacy-billing', PROFILES.docker, 2, { domains: [], neverHealthy: true, description: 'Migrating from the old VM' });
  for (let i = 0; i < 120; i++) hostHistory.push(hostSample(now() - (120 - i) * 5000, i));
  audit.sort((x, y) => y.timestamp.localeCompare(x.timestamp));

  // Automatic updates: same rules as the server's CommitPoller.
  setInterval(() => {
    for (const a of apps) {
      if (!a.autoUpdate || !a.currentId || a.status === 'STOPPED' || a.busy || a.queue.length) continue;
      const head = a.branchCommits[0];
      if (head && !a.deployments.some((d) => d.commitSha === head.sha)) requestDeployment(a, 'SCHEDULED', head.sha);
    }
  }, 90_000);

  let tick = 120;
  setInterval(() => {
    tick++;
    hostHistory.push(hostSample(now(), tick));
    hostHistory.splice(0, Math.max(0, hostHistory.length - 180));
    const perApp: Record<string, MetricSample> = {};
    for (const a of apps) {
      if (a.profile.kind !== 'SERVER' || !a.currentId || a.status === 'STOPPED' || a.status === 'FAILED') continue;
      const s = sample(a, now(), tick);
      a.metrics.push(s);
      a.metrics.splice(0, Math.max(0, a.metrics.length - 180));
      perApp[a.id] = s;
    }
    emit('MetricsUpdated', null, null, { host: hostHistory[hostHistory.length - 1], applications: perApp });
  }, 5000);

  // Request logs for running server applications
  setInterval(() => {
    for (const a of apps) {
      const followers = logFollowers.get(a.id);
      if (!followers?.size || a.status !== 'RUNNING' || a.profile.kind !== 'SERVER') continue;
      followers.forEach((f) => f(requestLogLine(a)));
    }
  }, 1300);
}

function requestLogLine(a: AppState): string {
  const paths = a.profile.framework === 'Spring Boot' ? ['/api/orders', '/api/orders/4711', '/api/customers', '/actuator/health']
    : a.profile.framework === 'Go' ? ['/resize?w=640', '/resize?w=1280', '/healthz'] : ['/', '/api/projects', '/login', '/health'];
  const p = pick(paths);
  const status = Math.random() < 0.06 ? 500 : Math.random() < 0.1 ? 404 : 200;
  const ms = Math.round(3 + Math.random() * (status === 500 ? 900 : 60));
  const line = status === 500
    ? `ERROR request failed: GET ${p} -> 500 (${ms}ms) java.sql.SQLTransientConnectionException: pool timeout`
    : `INFO  GET ${p} ${status} ${ms}ms`;
  return `${iso(now())} ${line}`;
}

// ---------------------------------------------------------------- views

function summary(a: AppState): ApplicationSummary {
  const current = a.deployments.find((d) => d.id === a.currentId) ?? null;
  const urls = a.domains.map((d) => appUrl(d.hostname, d.tlsEnabled, DEMO_HTTP_PORT));
  const primary = a.domains.find((d) => d.primary);
  return {
    id: a.id, slug: a.slug, name: a.name, description: a.description, status: a.status,
    repository: { provider: 'GITHUB', url: `https://github.com/${a.repo}.git`, fullName: a.repo, branch: a.branch, privateRepository: a.privateRepo },
    currentDeployment: current, latestDeployment: a.deployments[0] ?? null,
    primaryUrl: primary ? appUrl(primary.hostname, primary.tlsEnabled, DEMO_HTTP_PORT) : urls[0] ?? null, urls,
    metrics: a.profile.kind === 'SERVER' && a.currentId && a.status !== 'STOPPED' ? a.metrics[a.metrics.length - 1] ?? null : null,
    projectType: a.profile.kind, framework: a.profile.framework, autoDeploy: a.autoDeploy, autoUpdate: a.autoUpdate, updatedAt: a.updatedAt,
  };
}

function detail(a: AppState): ApplicationDetail {
  return { summary: summary(a), service: a.service, domains: a.domains, autoRollback: a.autoRollback, cancelStaleBuilds: a.cancelStaleBuilds, buildConcurrency: 1, createdAt: a.createdAt };
}

function envView(a: AppState): EnvVar[] {
  return a.env.map((e) => ({ id: `${a.id}-${e.name}`, name: e.name, value: e.secret ? '********' : e.value, secret: e.secret, updatedAt: e.updatedAt }))
    .sort((x, y) => x.name.localeCompare(y.name));
}

function webhookView(a: AppState): WebhookView {
  const last = a.webhookEvents.find((e) => !e.test) ?? null;
  return {
    applicationId: a.id, provider: 'GITHUB', endpointUrl: `https://shipyard.example.com/api/webhooks/github/${a.webhookEndpoint}`,
    contentType: 'application/json', events: ['push'], automaticDeployment: a.autoDeploy, deployOnPush: a.deployOnPush, branch: a.branch,
    repository: a.repo, status: !a.autoDeploy || !a.deployOnPush ? 'DISABLED' : !last ? 'WAITING' : last.error === 'Invalid signature' ? 'ERROR' : 'ACTIVE',
    lastEvent: last, lastDeployment: a.deployments.find((d) => d.source === 'WEBHOOK') ?? null,
  };
}

function caddyfile(): string {
  let f = '# Generated by Shipyard. Do not edit: changes are overwritten on every deployment.\n{\n\tadmin unix//run/caddy-admin/admin.sock\n}\n\n'
    + '(shipyard_common) {\n\tencode zstd gzip\n\theader -Server\n}\n\nshipyard.example.com {\n\timport shipyard_common\n\treverse_proxy shipyard:8080 {\n\t\tflush_interval -1\n\t}\n}\n\n';
  for (const a of apps) {
    if (!a.domains.length) continue;
    const hosts = a.domains.map((d) => (d.tlsEnabled ? d.hostname : `http://${d.hostname}`)).join(', ');
    const cur = a.deployments.find((d) => d.id === a.currentId);
    f += `# application: ${a.slug}\n${hosts} {\n\timport shipyard_common\n`;
    if (!cur) f += '\trespond "Application has not been deployed yet" 503\n';
    else if (a.status === 'STOPPED') f += '\trespond "Application is stopped" 503\n';
    else if (cur.projectType === 'STATIC') f += `\troot * /var/lib/shipyard/deployments/${a.id}/${String(cur.number).padStart(3, '0')}/dist\n\ttry_files {path} {path}/ /index.html\n\tfile_server\n`;
    else f += `\treverse_proxy ${cur.containerName}:${a.service.internalPort ?? a.profile.port}\n`;
    f += '}\n\n';
  }
  return f;
}

// ---------------------------------------------------------------- pipeline

function buildScript(a: AppState, d: Deployment) {
  const p = a.profile;
  const tag = `shipyard/${a.slug}:deployment-${d.number}`;
  const build: string[] = [
    `Cloning github.com/${a.repo}`, 'Build strategy: ' + p.strategy + ` (${p.framework})`,
    '#0 building with "default" instance using docker driver', '#1 [internal] load build definition from Dockerfile.shipyard',
    `#2 [internal] load metadata for docker.io/library/${p.runtime ?? 'node:lts-alpine'}`, '#3 [internal] load build context', '#3 transferring context: 1.84MB done',
  ];
  if (p.install) build.push(`#5 [3/6] RUN ${p.install}`, '#5 12.4 added 412 packages, and audited 413 packages in 11s', '#5 DONE 12.9s');
  if (p.build) {
    build.push(`#6 [4/6] RUN ${p.build}`);
    if (p.framework.includes('Vite')) build.push('#6 1.02 vite v6.0.3 building for production...', '#6 3.40 ✓ 1482 modules transformed.', '#6 3.71 dist/index.html  0.62 kB', '#6 3.71 dist/assets/index-BdQ3.js  512.08 kB │ gzip: 157.31 kB', '#6 3.72 ✓ built in 2.71s');
    else if (p.strategy === 'MAVEN') build.push('#6 4.1 [INFO] Building orders-api 1.4.0', '#6 38.2 [INFO] BUILD SUCCESS', '#6 38.2 [INFO] Total time:  34.012 s');
    else if (p.strategy === 'GO') build.push('#6 6.3 go: downloading github.com/disintegration/imaging v1.6.2');
    else if (p.framework === 'Astro') build.push('#6 2.4 building client (vite)', '#6 5.9 ✓ Completed in 3.12s.', '#6 6.0 18 page(s) built in 5.43s');
    build.push('#6 DONE ' + (p.strategy === 'MAVEN' ? '38.9s' : '4.1s'));
  }
  if (p.kind === 'STATIC') build.push('#7 exporting to client directory', '#7 copying files 2.31MB 0.1s done', `Static artifact exported to /var/lib/shipyard/deployments/${a.id}/${String(d.number).padStart(3, '0')}/dist`);
  else build.push('#8 exporting to image', '#8 exporting layers 0.6s done', `#8 naming to docker.io/${tag} done`, `Built image ${tag}`);
  const deployment: { level: LogLine['level']; text: string }[] = [
    { level: 'INFO', text: `Deployment #${d.number} of ${a.name} started (source ${d.source})` },
    { level: 'INFO', text: `Checking out commit ${d.commitSha?.slice(0, 12)} — ${d.commitMessage}` },
    { level: 'INFO', text: `Detected ${p.kind} application: ${p.framework} via ${p.strategy === 'DOCKERFILE' ? 'DOCKERFILE' : 'FRAMEWORK'}${p.port ? `, port ${p.port}` : ''}` },
  ];
  if (p.kind === 'SERVER') {
    for (const v of a.service.volumes) deployment.push({ level: 'INFO', text: `Volume shipyard-${a.slug}-${v.replace(/^\/+/, '').replace(/[^a-z0-9_.-]+/gi, '-').toLowerCase()} mounted at ${v}` });
    deployment.push({ level: 'INFO', text: `Started container shipyard-${a.slug}-web-${d.number} (${a.service.cpuLimit} CPU, ${a.service.memoryLimitMb} MB)` });
    deployment.push({ level: 'INFO', text: `Health check: HTTP ${a.service.healthCheckPath} on port ${p.port} (timeout ${a.service.healthCheckTimeoutSeconds}s)` });
    deployment.push({ level: 'INFO', text: `HTTP 200 from ${a.service.healthCheckPath}` });
  } else {
    deployment.push({ level: 'INFO', text: 'Static artifact check passed' });
  }
  deployment.push({ level: 'INFO', text: `Traffic switched to #${d.number} (Caddy updated)` });
  return { build, deployment };
}

function setStatus(a: AppState, d: Deployment, status: DeploymentStatus) {
  d.status = status;
  emit('DeploymentStatusChanged', a.id, d.id, { status, number: d.number });
}

function setAppStatus(a: AppState, status: ApplicationStatus) {
  a.status = status;
  a.updatedAt = iso(now());
  emit('ApplicationUpdated', a.id, null, { status });
}

function log(a: AppState, d: Deployment, kind: 'build' | 'deployment', text: string, level: LogLine['level'] = 'INFO') {
  const entry: LogLine = { id: ++logLineId, deploymentId: d.id, kind, level, line: text, createdAt: iso(now()) };
  const l = a.logs.get(d.id) ?? { build: [], deployment: [] };
  l[kind].push(entry);
  a.logs.set(d.id, l);
  emit(kind === 'build' ? 'BuildLog' : 'DeploymentLog', a.id, d.id, { id: entry.id, line: text, level });
}

function enqueue(a: AppState, d: Deployment) {
  if (a.cancelStaleBuilds) {
    for (const qid of a.queue) {
      const q = a.deployments.find((x) => x.id === qid);
      if (q && q.status === 'QUEUED') {
        Object.assign(q, { status: 'CANCELLED', error: 'Superseded by a newer deployment', finishedAt: iso(now()) });
        a.webhookEvents.filter((w) => w.deploymentId === q.id).forEach((w) => Object.assign(w, { status: 'FAILED', error: 'Superseded by a newer deployment' }));
        emit('DeploymentStatusChanged', a.id, q.id, { status: 'CANCELLED' });
      }
    }
    a.queue = [];
  }
  a.queue.push(d.id);
  void drain(a);
}

async function drain(a: AppState) {
  if (a.busy) return;
  a.busy = true;
  try {
    while (a.queue.length) {
      const id = a.queue.shift()!;
      const d = a.deployments.find((x) => x.id === id);
      if (!d || d.status !== 'QUEUED') continue;
      await (d.rollbackTarget ? runRollback(a, d) : runDeployment(a, d));
    }
  } finally {
    a.busy = false;
  }
}

declare module '../services/types' {
  interface Deployment {
    /** demo only: marks a queued rollback */
    rollbackTarget?: boolean;
  }
}

class Cancelled extends Error {}

async function step(a: AppState, d: Deployment, ms: number) {
  await sleep(ms);
  if (a.cancelRequested === d.id) {
    a.cancelRequested = null;
    throw new Cancelled();
  }
}

async function runDeployment(a: AppState, d: Deployment) {
  const failing = /break|broken|fail/i.test(d.commitMessage ?? '');
  const statusBefore = a.status;
  const script = buildScript(a, d);
  d.startedAt = iso(now());
  a.webhookEvents.filter((w) => w.deploymentId === d.id).forEach((w) => (w.status = 'PROCESSING'));
  emit('DeploymentStarted', a.id, d.id, { number: d.number });
  record('DEPLOYMENT_STARTED', 'DEPLOYMENT', d.id, { application: a.slug, number: d.number, source: d.source });
  setAppStatus(a, a.currentId ? 'DEPLOYING' : 'BUILDING');
  try {
    log(a, d, 'deployment', script.deployment[0].text);
    setStatus(a, d, 'CLONING');
    log(a, d, 'build', script.build[0]);
    await step(a, d, 900);
    log(a, d, 'deployment', script.deployment[1].text);
    setStatus(a, d, 'DETECTING');
    await step(a, d, 700);
    log(a, d, 'deployment', script.deployment[2].text);
    setStatus(a, d, 'BUILDING');
    d.buildStatus = 'RUNNING';
    emit('BuildStarted', a.id, d.id, { number: d.number });
    for (const line of script.build.slice(1)) {
      log(a, d, 'build', line);
      await step(a, d, 260);
    }
    if (a.service.testCommand) {
      setStatus(a, d, 'TESTING');
      log(a, d, 'build', `#7 RUN ${a.service.testCommand}`);
      await step(a, d, 900);
      log(a, d, 'build', '#7 4.2 Tests: 128 passed, 128 total');
      log(a, d, 'deployment', `Tests passed (${a.service.testCommand})`);
    }
    d.buildStatus = 'SUCCEEDED';
    emit('BuildCompleted', a.id, d.id, { number: d.number, success: true });
    setStatus(a, d, 'DEPLOYING');
    await step(a, d, 600);
    if (a.profile.kind === 'SERVER') {
      d.imageTag = `shipyard/${a.slug}:deployment-${d.number}`;
      d.containerName = `shipyard-${a.slug}-web-${d.number}`;
      setStatus(a, d, 'STARTING');
      if (!a.currentId) setAppStatus(a, 'STARTING');
      log(a, d, 'deployment', script.deployment[3].text);
      await step(a, d, 1200);
      setStatus(a, d, 'HEALTH_CHECK');
      log(a, d, 'deployment', script.deployment[4].text);
      await step(a, d, failing ? 4000 : 1500);
      if (failing) {
        log(a, d, 'deployment', 'Health check timed out (last error: HTTP 503)');
        log(a, d, 'build', '[app] Caused by: org.postgresql.util.PSQLException: Connection refused');
        throw new Error('Health check failed');
      }
      log(a, d, 'deployment', script.deployment[5].text);
    } else {
      setStatus(a, d, 'HEALTH_CHECK');
      await step(a, d, 500);
      if (failing) {
        log(a, d, 'build', '#6 3.1 error during build: [vite]: Rollup failed to resolve import "./components/Hero"');
        d.buildStatus = 'FAILED';
        throw new Error('docker build failed (exit 1)');
      }
      log(a, d, 'deployment', script.deployment[3].text);
    }
    // switch traffic
    const prev = a.deployments.find((x) => x.id === a.currentId);
    a.currentId = d.id;
    d.status = 'RUNNING';
    d.runtimeStatus = 'RUNNING';
    d.finishedAt = iso(now());
    a.service.restartCount = 0;
    log(a, d, 'deployment', script.deployment[script.deployment.length - 1].text);
    if (prev && prev.id !== d.id) {
      if (prev.status === 'RUNNING') prev.status = 'STOPPED';
      prev.runtimeStatus = 'STOPPED';
      log(a, d, 'deployment', `Previous deployment #${prev.number} stopped (artifact kept for rollback)`);
      emit('DeploymentStatusChanged', a.id, prev.id, { status: prev.status });
    }
    log(a, d, 'deployment', `Deployment #${d.number} is RUNNING`);
    emit('DeploymentStatusChanged', a.id, d.id, { status: 'RUNNING' });
    setAppStatus(a, 'RUNNING');
    a.webhookEvents.filter((w) => w.deploymentId === d.id).forEach((w) => (w.status = 'DEPLOYED'));
    record('DEPLOYMENT_COMPLETED', 'DEPLOYMENT', d.id, { application: a.slug, number: d.number });
    emit('DeploymentHealthy', a.id, d.id, { number: d.number });
    emit('ApplicationStarted', a.id, d.id, { number: d.number });
  } catch (e) {
    const cancelled = e instanceof Cancelled;
    const error = cancelled ? 'Cancelled' : (e as Error).message;
    if (d.buildStatus === 'RUNNING' || d.buildStatus === 'PENDING') {
      d.buildStatus = cancelled ? 'CANCELLED' : 'FAILED';
      emit('BuildCompleted', a.id, d.id, { number: d.number, success: false });
    }
    log(a, d, 'deployment', cancelled ? 'Deployment cancelled' : `Deployment failed: ${error}`, cancelled ? 'WARN' : 'ERROR');
    if (d.containerName) log(a, d, 'deployment', `Removed failed container ${d.containerName}`);
    d.containerName = null;
    Object.assign(d, { status: cancelled ? 'CANCELLED' : 'FAILED', error, runtimeStatus: 'STOPPED', finishedAt: iso(now()) });
    const cur = a.deployments.find((x) => x.id === a.currentId);
    if (cur) log(a, d, 'deployment', `Previous deployment #${cur.number} remains online`);
    setAppStatus(a, !a.currentId ? 'FAILED' : statusBefore === 'STOPPED' || statusBefore === 'CRASHED' ? statusBefore : 'RUNNING');
    a.webhookEvents.filter((w) => w.deploymentId === d.id).forEach((w) => Object.assign(w, { status: 'FAILED', error }));
    if (!cancelled) {
      record('DEPLOYMENT_FAILED', 'DEPLOYMENT', d.id, { application: a.slug, number: d.number, error });
      emit('DeploymentFailed', a.id, d.id, { number: d.number, error });
    }
    emit('DeploymentStatusChanged', a.id, d.id, { status: d.status });
  }
}

async function runRollback(a: AppState, marker: Deployment) {
  // `marker` is a transient queue entry pointing at the real target
  const target = a.deployments.find((x) => x.id === (marker as Deployment & { targetId?: string }).targetId)!;
  a.deployments = a.deployments.filter((x) => x.id !== marker.id);
  const prevStatus = target.status;
  setAppStatus(a, 'ROLLING_BACK');
  log(a, target, 'deployment', `Rolling back to deployment #${target.number}`);
  if (a.profile.kind === 'SERVER') {
    setStatus(a, target, 'STARTING');
    target.containerName = `shipyard-${a.slug}-web-${target.number}-r${Math.floor(now() / 1000)}`;
    log(a, target, 'deployment', `Started container ${target.containerName} (${a.service.cpuLimit} CPU, ${a.service.memoryLimitMb} MB)`);
    await sleep(1200);
  }
  setStatus(a, target, 'HEALTH_CHECK');
  await sleep(1200);
  log(a, target, 'deployment', a.profile.kind === 'SERVER' ? `HTTP 200 from ${a.service.healthCheckPath}` : 'Static artifact check passed');
  const prev = a.deployments.find((x) => x.id === a.currentId);
  a.currentId = target.id;
  Object.assign(target, { status: 'RUNNING', runtimeStatus: 'RUNNING' });
  if (prev && prev.id !== target.id) {
    Object.assign(prev, { status: 'ROLLED_BACK', runtimeStatus: 'STOPPED' });
    emit('DeploymentStatusChanged', a.id, prev.id, { status: 'ROLLED_BACK' });
  }
  log(a, target, 'deployment', `Rollback complete: deployment #${target.number} is RUNNING (was ${prevStatus.toLowerCase()})`);
  setAppStatus(a, 'RUNNING');
  record('DEPLOYMENT_ROLLED_BACK', 'DEPLOYMENT', target.id, { application: a.slug, number: target.number });
  emit('DeploymentRolledBack', a.id, target.id, { number: target.number });
  emit('DeploymentStatusChanged', a.id, target.id, { status: 'RUNNING' });
}

function requestDeployment(a: AppState, source: DeploymentSource, ref?: string, message?: string, author?: string): Deployment {
  const d = newDeployment(a, source, now());
  const isSha = !!ref && /^[0-9a-f]{7,40}$/i.test(ref);
  if (isSha) d.commitSha = ref.padEnd(40, '0');
  else if (ref) d.branch = ref;
  // The branch head, or the named commit, with its real message and author.
  const known = !ref ? a.branchCommits[0] : isSha ? a.branchCommits.find((c) => c.sha.startsWith(ref.toLowerCase())) : undefined;
  if (known) Object.assign(d, { commitSha: known.sha, commitMessage: known.message, commitAuthor: known.author });
  if (message) d.commitMessage = message;
  if (author) d.commitAuthor = author;
  d.framework = a.profile.framework;
  a.deployments.unshift(d);
  a.logs.set(d.id, { build: [], deployment: [] });
  emit('DeploymentRequested', a.id, d.id, { number: d.number, source });
  emit('DeploymentStatusChanged', a.id, d.id, { status: 'QUEUED' });
  enqueue(a, d);
  return d;
}

/** Demo-only: simulates `git push` arriving through the GitHub webhook. */
export function simulatePush(applicationId: string, failing = false): Deployment | null {
  const a = findApp(applicationId);
  const message = failing ? 'Refactor startup (this one will break the health check)' : pick(MESSAGES);
  const author = pick(AUTHORS);
  const commit = sha();
  a.branchCommits.unshift({ sha: commit, author, message, timestamp: iso(now()) });
  const event = webhookEvent(a, commit, author, message, 'RECEIVED', null, null, now());
  a.webhookEvents.unshift(event);
  emit('WebhookReceived', a.id, null, { eventId: event.id, type: 'push' });
  if (!a.autoDeploy || !a.deployOnPush) {
    Object.assign(event, { status: 'IGNORED', error: 'Automatic deployment is disabled' });
    emit('WebhookProcessed', a.id, null, { eventId: event.id, status: 'IGNORED' });
    return null;
  }
  const d = requestDeployment(a, 'WEBHOOK', commit, message, author);
  Object.assign(event, { status: 'QUEUED', deploymentId: d.id });
  record('WEBHOOK_TRIGGERED', 'APPLICATION', a.id, { commit: commit.slice(0, 7), deployment: d.number });
  emit('WebhookProcessed', a.id, null, { eventId: event.id, status: 'QUEUED' });
  return d;
}

export function demoApplications() {
  seed();
  return apps.map((a) => ({ id: a.id, name: a.name, kind: a.profile.kind }));
}

// ---------------------------------------------------------------- Shipyard self-update (simulated)

const platform = {
  commit: sha(),
  branch: 'main',
  newer: [] as CommitInfo[], // newest first
  update: null as PlatformUpdateStatus | null,
  seeded: false,
};
function seedPlatform() {
  if (platform.seeded) return;
  platform.seeded = true;
  platform.newer = [
    { sha: sha(), author: 'iant89', message: 'Show a banner when newer commits are available', timestamp: iso(now() - 2 * 3600_000) },
    { sha: sha(), author: 'iant89', message: 'Fix overflow menus crashing on open', timestamp: iso(now() - 26 * 3600_000) },
  ];
}
function platformVersion(): PlatformVersion {
  seedPlatform();
  return {
    commit: platform.commit, branch: platform.branch, repository: 'iant89/shipyard', canUpdate: true, reason: null, update: platform.update,
    check: {
      status: platform.newer.length ? 'BEHIND' : 'UP_TO_DATE', branch: platform.branch, currentSha: platform.commit,
      latestSha: platform.newer[0]?.sha ?? platform.commit, behind: platform.newer.length, commits: platform.newer, checkedAt: iso(now()), message: null,
    },
  };
}
function startPlatformUpdate(commit: string): PlatformUpdateStatus {
  seedPlatform();
  const idx = platform.newer.findIndex((c) => c.sha === commit);
  if (idx < 0) throw new DemoError(400, `Commit ${commit.slice(0, 7)} is not a newer commit on main`);
  if (platform.update?.state === 'RUNNING') throw new DemoError(409, 'An update is already running');
  const from = platform.commit;
  const u: PlatformUpdateStatus = { state: 'RUNNING', fromSha: from, toSha: commit, message: 'Starting the updater', startedAt: iso(now()), finishedAt: null, log: [] };
  platform.update = u;
  const steps: [number, string][] = [
    [800, `Fetching origin/main`], [1600, `Updating ${from.slice(0, 7)} → ${commit.slice(0, 7)} (${platform.newer.length - idx} commits)`],
    [2600, 'Building the new image (this takes a few minutes)'], [6500, 'Stopping Shipyard and backing up the database'],
    [7500, `Starting Shipyard ${commit.slice(0, 7)}`],
  ];
  for (const [ms, line] of steps) {
    setTimeout(() => { u.message = line; u.log = [...u.log, `[${iso(now())}] ${line}`]; }, ms);
  }
  setTimeout(() => {
    const done = `Updated to ${commit.slice(0, 7)}.`;
    Object.assign(u, { state: 'SUCCEEDED', message: done, finishedAt: iso(now()), log: [...u.log, `[${iso(now())}] ${done}`] });
    platform.commit = commit;
    platform.newer = platform.newer.slice(0, idx);
  }, 10_000);
  return { ...u };
}

// ---------------------------------------------------------------- API implementation

function wrap<T>(fn: () => T, ms?: number): Promise<T> {
  try {
    return latency(fn(), ms);
  } catch (e) {
    return sleep(80).then(() => Promise.reject(e));
  }
}

export function createDemoApi(): Api {
  seed();
  const appApi: Api['applications'] = {
    list: () => wrap(() => apps.map(summary)),
    get: (id) => wrap(() => detail(findApp(id))),
    create: (body: CreateApplicationRequest) => wrap(() => {
      const repo = repoName(body.repository);
      const p = profileFor(repo);
      const id = uid('app');
      const base = slugify(body.name || repo.split('/')[1]);
      let slug = base;
      for (let i = 2; apps.some((x) => x.slug === slug); i++) slug = `${base}-${i}`;
      const t = iso(now());
      const a: AppState = {
        id, slug, name: body.name || repo.split('/')[1], description: body.description ?? null, status: 'CREATED', repo, branch: body.branch,
        privateRepo: !!body.accessToken, autoDeploy: body.autoDeploy, autoUpdate: !!body.autoUpdate, autoRollback: true, cancelStaleBuilds: true, profile: p,
        service: serviceFor(id, p, body.service), currentId: null, deployments: [], logs: new Map(),
        branchCommits: [{ sha: sha(), author: pick(AUTHORS), message: pick(MESSAGES), timestamp: t }],
        domains: body.domains.map((h, i) => ({ id: uid('dom'), applicationId: id, hostname: h.toLowerCase(), tlsEnabled: !isLocalHostname(h), primary: i === 0, createdAt: t })),
        env: body.environment.map((e) => ({ ...e, updatedAt: t })), webhookEndpoint: sha(), webhookSecret: sha() + sha().slice(0, 24),
        deployOnPush: true, webhookEvents: [], metrics: [], queue: [], busy: false, cancelRequested: null, createdAt: t, updatedAt: t,
      };
      apps.push(a);
      record('USER_CREATED_APPLICATION', 'APPLICATION', id, { name: a.name, repository: `https://github.com/${repo}.git` });
      emit('ApplicationUpdated', id, null, { status: 'CREATED', created: true });
      if (body.deployNow) requestDeployment(a, 'MANUAL');
      return detail(a);
    }),
    update: (id, body: UpdateApplicationRequest) => wrap(() => {
      const a = findApp(id);
      if (body.name) a.name = body.name;
      if (body.description !== undefined) a.description = body.description || null;
      if (body.repository) a.repo = repoName(body.repository);
      if (body.branch) a.branch = body.branch;
      if (body.accessToken) a.privateRepo = true;
      if (body.clearAccessToken) a.privateRepo = false;
      if (body.autoDeploy !== undefined) a.autoDeploy = body.autoDeploy;
      if (body.autoUpdate !== undefined) a.autoUpdate = body.autoUpdate;
      if (body.autoRollback !== undefined) a.autoRollback = body.autoRollback;
      if (body.cancelStaleBuilds !== undefined) a.cancelStaleBuilds = body.cancelStaleBuilds;
      if (body.service) {
        const s = body.service;
        Object.assign(a.service, {
          projectType: s.projectType ?? null, buildStrategy: s.buildStrategy ?? null, rootDirectory: s.rootDirectory ?? '.',
          installCommand: s.installCommand ?? null, buildCommand: s.buildCommand ?? null, testCommand: s.testCommand ?? null,
          startCommand: s.startCommand ?? null, outputDirectory: s.outputDirectory ?? null, runtimeImage: s.runtimeImage ?? null,
          internalPort: s.internalPort ?? null, healthCheckType: s.healthCheckType ?? a.service.healthCheckType,
          healthCheckPath: s.healthCheckPath ?? a.service.healthCheckPath, healthCheckTimeoutSeconds: s.healthCheckTimeoutSeconds ?? a.service.healthCheckTimeoutSeconds,
          cpuLimit: s.cpuLimit ?? a.service.cpuLimit, memoryLimitMb: s.memoryLimitMb ?? a.service.memoryLimitMb, restartPolicy: s.restartPolicy ?? a.service.restartPolicy, volumes: s.volumes ?? a.service.volumes,
        });
      }
      a.updatedAt = iso(now());
      record('APPLICATION_UPDATED', 'APPLICATION', id);
      emit('ApplicationUpdated', id, null, { status: a.status });
      return detail(a);
    }),
    remove: (id) => wrap(() => {
      const a = findApp(id);
      apps.splice(apps.indexOf(a), 1);
      record('APPLICATION_DELETED', 'APPLICATION', id, { name: a.name });
      emit('ApplicationUpdated', id, null, { deleted: true });
      return undefined as void;
    }),
    deploy: (id, ref) => wrap(() => requestDeployment(findApp(id), 'MANUAL', ref)),
    exec: (id, command, cwd) => wrap(() => {
      const a = findApp(id);
      const cur = a.deployments.find((d) => d.id === a.currentId);
      if (!cur) throw new DemoError(409, 'The application has not been deployed yet');
      if (a.status === 'STOPPED') throw new DemoError(409, `The container ${cur.containerName} is not running`);
      record('CONTAINER_COMMAND', 'APPLICATION', id, { container: cur.containerName ?? '', command });
      return demoExec(command, cwd ?? '/app', cur.containerName ?? '');
    }, 250),
    restart: (id) => wrap(() => {
      const a = findApp(id);
      if (!a.currentId) throw new DemoError(409, 'Application has no active deployment');
      a.service.restartCount = 0;
      setAppStatus(a, 'RUNNING');
      const cur = a.deployments.find((d) => d.id === a.currentId)!;
      Object.assign(cur, { status: 'RUNNING', runtimeStatus: 'RUNNING' });
      record('APPLICATION_UPDATED', 'APPLICATION', id, { action: 'restart' });
      emit('ApplicationStarted', id, cur.id, { action: 'restart' });
      return undefined as void;
    }, 600),
    stop: (id) => wrap(() => {
      const a = findApp(id);
      const cur = a.deployments.find((d) => d.id === a.currentId);
      if (!cur) throw new DemoError(409, 'Application has no active deployment');
      Object.assign(cur, { status: 'STOPPED', runtimeStatus: 'STOPPED' });
      setAppStatus(a, 'STOPPED');
      record('APPLICATION_UPDATED', 'APPLICATION', id, { action: 'stop' });
      emit('ApplicationStopped', id, cur.id);
      return undefined as void;
    }, 500),
    start: (id) => wrap(() => {
      const a = findApp(id);
      const cur = a.deployments.find((d) => d.id === a.currentId);
      if (!cur) throw new DemoError(409, 'Application has no active deployment');
      Object.assign(cur, { status: 'RUNNING', runtimeStatus: 'RUNNING' });
      setAppStatus(a, 'RUNNING');
      record('APPLICATION_UPDATED', 'APPLICATION', id, { action: 'start' });
      emit('ApplicationStarted', id, cur.id, { action: 'start' });
      return undefined as void;
    }, 500),
    rollback: (id, deploymentId) => wrap(() => {
      const a = findApp(id);
      const target = deploymentId ? findDeployment(a, deploymentId)
        : a.deployments.find((d) => d.id !== a.currentId && (d.status === 'STOPPED' || d.status === 'ROLLED_BACK'));
      if (!target) throw new DemoError(400, 'No previous deployment to roll back to');
      if (target.id === a.currentId && a.status === 'RUNNING') throw new DemoError(409, `Deployment #${target.number} is already active`);
      const marker = { ...newDeployment(a, 'MANUAL', now()), id: uid('rb'), rollbackTarget: true, targetId: target.id, number: -1 };
      a.deployments.push(marker); // hidden at the end; removed when processed
      a.queue.push(marker.id);
      void drain(a);
      return target;
    }),
    detect: (id) => wrap(() => { const a = findApp(id); return detection(a.profile, a.service); }, 900),
    branches: () => wrap(() => ['main', 'develop', 'feature/redesign']),
    updates: (id) => wrap(() => {
      const a = findApp(id);
      const cur = a.deployments.find((d) => d.id === a.currentId);
      const base = { branch: a.branch, checkedAt: iso(now()), message: null };
      if (!cur?.commitSha) return { ...base, status: 'NOT_DEPLOYED', currentSha: null, latestSha: null, behind: 0, commits: [] } satisfies UpdateCheck;
      const idx = a.branchCommits.findIndex((c) => c.sha === cur.commitSha);
      const newer = idx === -1 ? a.branchCommits : a.branchCommits.slice(0, idx);
      return {
        ...base, status: newer.length ? 'BEHIND' : 'UP_TO_DATE', currentSha: cur.commitSha, latestSha: a.branchCommits[0]?.sha ?? cur.commitSha,
        behind: newer.length, commits: newer.slice(0, 20),
      } satisfies UpdateCheck;
    }, 500),
    deployments: (id, limit = 50) => wrap(() => findApp(id).deployments.filter((d) => d.number > 0).slice(0, limit)),
    deployment: (id, dep) => wrap(() => findDeployment(findApp(id), dep)),
    cancelDeployment: (id, dep) => wrap(() => {
      const a = findApp(id);
      const d = findDeployment(a, dep);
      if (d.status === 'QUEUED') {
        a.queue = a.queue.filter((q) => q !== d.id);
        Object.assign(d, { status: 'CANCELLED', error: 'Cancelled by user', finishedAt: iso(now()) });
        emit('DeploymentStatusChanged', id, d.id, { status: 'CANCELLED' });
      } else if (['CLONING', 'DETECTING', 'BUILDING', 'TESTING', 'DEPLOYING', 'STARTING', 'HEALTH_CHECK'].includes(d.status)) {
        a.cancelRequested = d.id;
      } else {
        throw new DemoError(409, `Deployment #${d.number} is not queued or running`);
      }
      return undefined as void;
    }),
    redeploy: (id, dep) => wrap(() => {
      const a = findApp(id);
      const old = findDeployment(a, dep);
      return requestDeployment(a, 'MANUAL', old.commitSha ?? undefined, old.commitMessage ?? undefined, old.commitAuthor ?? undefined);
    }),
    deploymentLogs: (id, dep) => wrap(() => {
      const a = findApp(id);
      const d = findDeployment(a, dep);
      return a.logs.get(d.id) ?? { build: [], deployment: [] };
    }),
    logs: (id, tail = 500) => wrap(() => {
      const a = findApp(id);
      const lines = Array.from({ length: Math.min(tail, 60) }, () => requestLogLine(a));
      return { lines, container: a.deployments.find((d) => d.id === a.currentId)?.containerName ?? '' };
    }),
    metrics: (id) => wrap(() => {
      const a = findApp(id);
      const cur = a.deployments.find((d) => d.id === a.currentId);
      const up = a.status !== 'STOPPED' && !!cur;
      return {
        latest: up ? a.metrics[a.metrics.length - 1] ?? null : null, history: up ? a.metrics : [], cpuLimit: a.service.cpuLimit,
        memoryLimitMb: a.service.memoryLimitMb, restartCount: a.service.restartCount,
        container: cur?.containerName ? {
          id: sha().slice(0, 12), name: cur.containerName, image: cur.imageTag ?? '', status: a.status === 'STOPPED' ? 'exited' : a.status === 'CRASHED' ? 'restarting' : 'running',
          running: a.status === 'RUNNING', restartCount: a.service.restartCount, exitCode: 0, startedAt: cur.finishedAt, finishedAt: null, health: null,
          ipAddress: `172.18.0.${(cur.number % 200) + 3}`,
        } : null,
      };
    }),
    environment: (id) => wrap(() => envView(findApp(id))),
    setEnv: (id, name, value, secret) => wrap(() => {
      const a = findApp(id);
      if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) throw new DemoError(400, `Invalid variable name: ${name}`);
      const e = a.env.find((x) => x.name === name);
      if (e) Object.assign(e, { value: value ?? e.value, secret, updatedAt: iso(now()) });
      else a.env.push({ name, value: value ?? '', secret, updatedAt: iso(now()) });
      record('ENVIRONMENT_VARIABLE_CHANGED', 'APPLICATION', id, { name, change: 'set' });
      return envView(a);
    }),
    deleteEnv: (id, name) => wrap(() => {
      const a = findApp(id);
      a.env = a.env.filter((x) => x.name !== name);
      record('ENVIRONMENT_VARIABLE_CHANGED', 'APPLICATION', id, { name, change: 'deleted' });
      return envView(a);
    }),
    domains: (id) => wrap(() => findApp(id).domains),
    addDomain: (id, hostname, tlsEnabled, primary) => wrap(() => {
      const a = findApp(id);
      const h = hostname.trim().toLowerCase().replace(/^https?:\/\//, '');
      if (!/^[a-z0-9.-]+\.[a-z0-9-]+$/.test(h)) throw new DemoError(400, `Invalid hostname: ${hostname}`);
      if (apps.some((x) => x.domains.some((d) => d.hostname === h))) throw new DemoError(409, `Domain already in use: ${h}`);
      const first = a.domains.length === 0;
      if (primary || first) a.domains.forEach((d) => (d.primary = false));
      const d: Domain = { id: uid('dom'), applicationId: id, hostname: h, tlsEnabled, primary: primary || first, createdAt: iso(now()) };
      a.domains.push(d);
      record('DOMAIN_CHANGED', 'APPLICATION', id, { added: h });
      emit('ApplicationUpdated', id, null, { status: a.status });
      return d;
    }),
    updateDomain: (id, domainId, patch) => wrap(() => {
      const a = findApp(id);
      const d = a.domains.find((x) => x.id === domainId);
      if (!d) throw new DemoError(404, 'Domain not found');
      if (patch.primary) a.domains.forEach((x) => (x.primary = false));
      Object.assign(d, patch);
      record('DOMAIN_CHANGED', 'APPLICATION', id, { updated: d.hostname });
      return d;
    }),
    removeDomain: (id, domainId) => wrap(() => {
      const a = findApp(id);
      const d = a.domains.find((x) => x.id === domainId);
      a.domains = a.domains.filter((x) => x.id !== domainId);
      record('DOMAIN_CHANGED', 'APPLICATION', id, { removed: d?.hostname });
      return undefined as void;
    }),
    webhook: (id) => wrap(() => webhookView(findApp(id))),
    updateWebhook: (id, patch) => wrap(() => {
      const a = findApp(id);
      if (patch.deployOnPush !== undefined) a.deployOnPush = patch.deployOnPush;
      if (patch.automaticDeployment !== undefined) a.autoDeploy = patch.automaticDeployment;
      record('WEBHOOK_UPDATED', 'APPLICATION', id, patch);
      emit('ApplicationUpdated', id, null, { status: a.status });
      return webhookView(a);
    }),
    webhookEvents: (id, limit = 50) => wrap(() => findApp(id).webhookEvents.slice(0, limit)),
    revealWebhookSecret: (id) => wrap(() => ({ secret: findApp(id).webhookSecret })),
    regenerateWebhookSecret: (id) => wrap(() => {
      const a = findApp(id);
      a.webhookSecret = sha() + sha().slice(0, 24);
      record('WEBHOOK_UPDATED', 'APPLICATION', id, { change: 'secret regenerated' });
      return { secret: a.webhookSecret };
    }),
    testWebhook: (id) => wrap(() => {
      const a = findApp(id);
      const e = webhookEvent(a, '0'.repeat(39) + '1', 'shipyard-test', 'Shipyard webhook test', 'IGNORED', null,
        'Test event: signature and configuration valid; no deployment triggered', now(), true);
      a.webhookEvents.unshift(e);
      emit('WebhookProcessed', id, null, { eventId: e.id, status: 'IGNORED' });
      return { outcome: 'IGNORED', eventId: e.id, status: 'IGNORED', message: e.error!, deploymentId: null };
    }, 500),
    activity: (id, limit = 50) => wrap(() => audit.filter((x) => x.resourceId === id).slice(0, limit)),
  };

  return {
    auth: {
      status: () => wrap(() => ({ setupRequired: false, authenticated: true, username: 'demo' })),
      login: () => wrap(() => ({ username: 'demo' })),
      setup: () => wrap(() => ({ username: 'demo' })),
      logout: () => wrap(() => undefined as void),
      changePassword: () => wrap(() => undefined as void, 400),
    },
    dashboard: () => wrap(() => {
      const count = (...s: ApplicationStatus[]) => apps.filter((a) => s.includes(a.status)).length;
      const all = apps.flatMap((a) => a.deployments.filter((d) => d.number > 0));
      return {
        applications: apps.length, running: count('RUNNING'), deploying: count('DEPLOYING', 'BUILDING', 'STARTING', 'ROLLING_BACK'),
        failed: count('FAILED', 'CRASHED'), stopped: count('STOPPED'),
        activeDeployments: all.filter((d) => !['RUNNING', 'STOPPED', 'FAILED', 'CANCELLED', 'ROLLED_BACK'].includes(d.status)).length,
        host: hostHistory[hostHistory.length - 1], hostHistory,
        recentDeployments: all.sort((x, y) => y.createdAt.localeCompare(x.createdAt)).slice(0, 10), recentActivity: audit.slice(0, 15),
      };
    }),
    platform: {
      info: () => wrap(() => ({
        name: 'Shipyard', version: 'demo', dockerAvailable: true, dockerVersion: '27.3.1', dockerNetwork: 'shipyard', dockerSubnet: '172.30.0.0/22',
        publicUrl: 'https://shipyard.example.com', https: true, platformDomain: 'shipyard.example.com', baseDomain: null, dataDir: '/var/lib/shipyard',
        httpPort: DEMO_HTTP_PORT, mdns: { enabled: true, running: true, addresses: ['eth0 192.168.1.20'] }, caddyAdminConfigured: true, caddyStatus: `Applied at ${iso(now() - 4 * MIN)}`, workerThreads: 2, retainDeployments: 10,
      })),
      caddy: () => wrap(() => ({ config: caddyfile(), status: `Applied at ${iso(now() - 4 * MIN)}` })),
      metrics: () => wrap(() => ({ latest: hostHistory[hostHistory.length - 1], history: hostHistory })),
      reloadCaddy: () => wrap(() => ({ applied: true, message: 'Caddy configuration applied' }), 500),
      version: () => wrap(platformVersion, 700),
      updateStatus: () => wrap(() => (platform.update ? { ...platform.update } : undefined)),
      startUpdate: (commit: string) => wrap(() => startPlatformUpdate(commit), 600),
      activity: (limit = 100) => wrap(() => audit.slice(0, limit)),
      deployments: (limit = 100) => wrap(() => apps.flatMap((a) => a.deployments.filter((d) => d.number > 0))
        .sort((x, y) => y.createdAt.localeCompare(x.createdAt)).slice(0, limit)),
      webhookEvents: (limit = 50) => wrap(() => apps.flatMap((a) => a.webhookEvents).sort((x, y) => y.receivedAt.localeCompare(x.receivedAt)).slice(0, limit)),
    },
    detect: (repository: string, branch: string) => wrap((): DetectionPreview => {
      const repo = repoName(repository);
      return { repository: repo, branch: branch || 'main', commitSha: sha(), branches: ['main', 'develop'], detection: detection(profileFor(repo)) };
    }, 1400),
    branches: (repository: string) => wrap(() => {
      repoName(repository);
      return ['main', 'develop', 'feature/redesign'];
    }, 500),
    applications: appApi,
  };
}

export const demoTransport: EventTransport = {
  subscribe(applicationId, listener) {
    const key = applicationId ?? '*';
    let set = listeners.get(key);
    if (!set) listeners.set(key, (set = new Set()));
    set.add(listener);
    return () => set!.delete(listener);
  },
  streamLogs(applicationId, tail, onLine) {
    const a = apps.find((x) => x.id === applicationId);
    if (a) Array.from({ length: Math.min(tail, 40) }, () => requestLogLine(a)).forEach(onLine);
    let set = logFollowers.get(applicationId);
    if (!set) logFollowers.set(applicationId, (set = new Set()));
    set.add(onLine);
    return () => set!.delete(onLine);
  },
};

export { DemoError };

/** A tiny pretend shell for the demo console. */
function demoExec(command: string, cwd: string, container: string) {
  const files: Record<string, string> = {
    '/data/setup-token': 'demo-setup-7Q2KX-91F4M-ZT8RP\n',
    '/app/package.json': '{\n  "name": "demo",\n  "private": true\n}\n',
  };
  const dirs: Record<string, string[]> = { '/': ['app', 'data', 'etc', 'tmp', 'usr'], '/app': ['main.js', 'package.json'], '/data': ['clawrouter.db', 'setup-token'] };
  const abs = (p: string) => (p === '.' || p === '' ? cwd : (p.startsWith('/') ? p : `${cwd === '/' ? '' : cwd}/${p}`).replace(/\/+$/, '') || '/');
  const [name, ...args] = command.trim().split(/\s+/);
  let output = '';
  let exitCode = 0;
  let nextCwd = cwd;
  if (name === 'cd') {
    const target = abs(args[0] ?? '/');
    if (dirs[target]) nextCwd = target;
    else { output = `sh: cd: can't cd to ${args[0]}: No such file or directory\n`; exitCode = 2; }
  } else if (name === 'pwd') output = `${cwd}\n`;
  else if (name === 'ls') {
    const target = abs(args.find((x) => !x.startsWith('-')) ?? '.');
    if (dirs[target]) output = `${dirs[target].join('\n')}\n`;
    else { output = `ls: ${target}: No such file or directory\n`; exitCode = 1; }
  } else if (name === 'cat') {
    const f = files[abs(args[0] ?? '')];
    if (f) output = f;
    else { output = `cat: can't open '${args[0] ?? ''}': No such file or directory\n`; exitCode = 1; }
  } else if (name === 'echo') output = `${args.join(' ')}\n`;
  else if (name === 'whoami') output = 'bun\n';
  else if (name === 'hostname') output = `${container}\n`;
  else { output = `sh: ${name}: not found (the demo console knows cd, pwd, ls, cat, echo, whoami, hostname)\n`; exitCode = 127; }
  return { exitCode, output, cwd: nextCwd, truncated: false, timedOut: false, durationMs: 40, container };
}
