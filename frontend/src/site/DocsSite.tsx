import { useState } from 'react';
import {
  Bar, Button, Card, DescriptionList, Icon, Link, MessageStrip, ObjectPage, ProcessFlow, SegmentedButton, StatusIndicator, Table, Text,
  ThemeProvider, Title, type TableColumn, type ThemePreference,
} from '@enterprise-ui/react';

const REPO = 'https://github.com/iant89/Shipyard';
const base = import.meta.env.BASE_URL;

interface EnvRow { name: string; def: string; purpose: string }

const ENV: EnvRow[] = [
  { name: 'SHIPYARD_PUBLIC_URL', def: 'http://localhost:8080', purpose: 'Base URL used for webhook URLs' },
  { name: 'SHIPYARD_PLATFORM_DOMAIN', def: '–', purpose: 'Serve the UI through Caddy on this hostname' },
  { name: 'SHIPYARD_BASE_DOMAIN', def: '–', purpose: 'Also serve every app at <slug>.<base domain>' },
  { name: 'SHIPYARD_HTTPS', def: 'true', purpose: 'false serves plain HTTP only (no certificates, no 443)' },
  { name: 'SHIPYARD_ACME_EMAIL', def: '–', purpose: "Let's Encrypt account email" },
  { name: 'SHIPYARD_DATA_DIR', def: '/var/lib/shipyard', purpose: 'Database, repositories, artifacts, Caddyfile' },
  { name: 'SHIPYARD_WORKER_THREADS', def: '2', purpose: 'Applications that may deploy at the same time' },
  { name: 'SHIPYARD_RETAIN_DEPLOYMENTS', def: '10', purpose: 'Artifacts kept per application for rollback' },
  { name: 'SHIPYARD_CRASH_RESTART_THRESHOLD', def: '5', purpose: 'Restarts within 10 minutes before an app is CRASHED' },
  { name: 'SHIPYARD_ADMIN_USERNAME / _PASSWORD', def: '–', purpose: 'Create the first administrator non-interactively' },
];

const FRAMEWORKS: { kind: string; items: string }[] = [
  { kind: 'Static', items: 'HTML, React, Vue, Angular, Vite, Astro, SvelteKit (static), Gatsby, static Next.js export' },
  { kind: 'Server', items: 'Node.js (Express, Fastify, NestJS, Next.js, Nuxt), Bun, Python (FastAPI, Flask, Django), Go, Rust, Spring Boot (Maven/Gradle), .NET, PHP' },
  { kind: 'Anything else', items: 'Bring a Dockerfile; Shipyard builds and runs it as-is' },
];

const code = (s: string) => <pre className="sy-code-block">{s}</pre>;

function Feature({ icon, title, children }: { icon: string; title: string; children: React.ReactNode }) {
  return (
    <Card avatar={<Icon name={icon as never} color="var(--eui-color-primary)" />} title={title}>
      <Text as="p" style={{ margin: 0 }}>{children}</Text>
    </Card>
  );
}

/** Project documentation for GitHub Pages, built from ENTERPRISE-UI components. */
export function DocsSite() {
  const [theme, setTheme] = useState<ThemePreference>('system');
  const demo = `${base}demo/`;

  const envColumns: TableColumn<EnvRow>[] = [
    { id: 'name', header: 'Variable', rowHeader: true, cell: (r) => <code className="sy-mono">{r.name}</code> },
    { id: 'def', header: 'Default', cell: (r) => <code className="sy-mono">{r.def}</code> },
    { id: 'purpose', header: 'Purpose', accessor: 'purpose', wrap: true },
  ];

  return (
    <ThemeProvider theme={theme} density="cozy" style={{ minHeight: '100vh' }}>
      <Bar
        design="header"
        startContent={
          <span className="sy-row">
            <Icon name="sap-box" color="var(--eui-color-primary)" />
            <Title level={1} size="md" style={{ margin: 0 }}>Shipyard</Title>
            <Text tone="secondary" size="sm">Self-hosted application platform</Text>
          </span>
        }
        endContent={
          <span className="sy-row">
            <SegmentedButton
              aria-label="Theme"
              size="sm"
              items={[{ value: 'system', label: 'Auto' }, { value: 'light', label: 'Light' }, { value: 'dark', label: 'Dark' }]}
              value={[theme]}
              onChange={(v) => v[0] && setTheme(v[0] as ThemePreference)}
            />
            <Button size="sm" variant="tertiary" onClick={() => window.open(REPO, '_blank', 'noreferrer')}>GitHub</Button>
          </span>
        }
      />
      <ObjectPage
        title="Give it a Git repository and it will host it"
        subtitle="Shipyard detects, builds, deploys and serves static sites and server applications on a single homelab server, and redeploys on every git push."
        status={<StatusIndicator tone="information">MVP · 0.1</StatusIndicator>}
        actions={
          <>
            <Button variant="primary" icon={<Icon name="media-play" />} onClick={() => { window.location.href = demo; }}>Open live demo</Button>
            <Button icon={<Icon name="source-code" />} onClick={() => window.open(REPO, '_blank', 'noreferrer')}>Source code</Button>
          </>
        }
        attributes={[
          { label: 'Backend', value: 'Java 21 · Spring Boot · SQLite' },
          { label: 'Runtime', value: 'Docker · Caddy (automatic HTTPS)' },
          { label: 'Frontend', value: <Link href="https://github.com/iant89/ENTERPRISE-UI" target="_blank" rel="noreferrer">ENTERPRISE-UI</Link> },
          { label: 'Deploys from', value: 'GitHub (webhooks)' },
        ]}
        sections={[
          {
            id: 'overview',
            title: 'Overview',
            content: (
              <div className="sy-stack">
                <MessageStrip tone="information" title="Try it without installing"
                  actions={<Button size="sm" onClick={() => { window.location.href = demo; }}>Open live demo</Button>}>
                  The demo is the real Shipyard interface running against a simulated backend in your browser. Push commits,
                  watch builds stream, break a deployment and roll back.
                </MessageStrip>
                <div className="sy-grid-2">
                  <Feature icon="internet-browser" title="Static and server applications">
                    Static sites are built in a container and served directly by Caddy. Server applications run as resource-limited
                    containers behind Caddy on an internal network, with no published ports.
                  </Feature>
                  <Feature icon="inspection" title="Automatic detection">
                    Recognises frameworks from package.json, pom.xml, go.mod, Cargo.toml, requirements.txt and more. A Dockerfile or
                    platform.yml overrides detection, and every setting can be changed in the UI.
                  </Feature>
                  <Feature icon="connected" title="Deploy on git push">
                    GitHub webhooks with HMAC-SHA256 verification, duplicate-delivery protection, branch filtering and stale-build
                    cancellation, so the newest commit wins.
                  </Feature>
                  <Feature icon="shield" title="Failed deployments never go live">
                    Every deployment pins an exact commit and is health-checked before it receives traffic. The previous deployment keeps
                    serving if anything fails, and rollback restarts a stored artifact without rebuilding.
                  </Feature>
                  <Feature icon="log" title="Logs and metrics">
                    Build, deployment, application and webhook logs stream live, with secrets masked everywhere. CPU, memory and network
                    per application and for the server, plus crash detection.
                  </Feature>
                  <Feature icon="locked" title="Homelab-safe defaults">
                    Repository code only runs inside containers. Secrets are encrypted at rest. The Docker socket and Caddy admin API are
                    never reachable from hosted applications.
                  </Feature>
                </div>
              </div>
            ),
          },
          {
            id: 'how',
            title: 'How it works',
            content: (
              <div className="sy-stack">
                <ProcessFlow
                  label="Deployment pipeline"
                  lanes={[
                    { id: 'trigger', title: 'Trigger', nodes: [{ id: 't', title: 'Push, UI or API', status: 'done', statusText: 'Queued' }] },
                    { id: 'clone', title: 'Clone', nodes: [{ id: 'c', title: 'Exact commit', status: 'done', statusText: 'git archive' }] },
                    { id: 'detect', title: 'Detect', nodes: [{ id: 'd', title: 'Framework', status: 'done', statusText: 'Dockerfile → platform.yml → framework' }] },
                    { id: 'build', title: 'Build & test', nodes: [{ id: 'b', title: 'In a container', status: 'done', statusText: 'docker build' }] },
                    { id: 'verify', title: 'Deploy & verify', nodes: [{ id: 'v', title: 'Health check', status: 'done', statusText: 'HTTP, TCP or process' }] },
                    { id: 'live', title: 'Live', nodes: [{ id: 'l', title: 'Caddy switches traffic', status: 'done', statusText: 'Previous retired' }] },
                  ]}
                />
                <DescriptionList
                  columns={1}
                  bordered
                  items={[
                    { term: 'One pipeline for every trigger', description: 'Manual, webhook and API deployments become a row in a persistent queue that survives restarts. One deployment runs per application at a time; different applications deploy concurrently.' },
                    { term: 'Zero-downtime switch', description: 'A new server deployment starts next to the current one. Only after its health check passes does Shipyard regenerate the Caddy configuration and retire the old container.' },
                    { term: 'Immutable artifacts', description: 'Images are tagged shipyard/<app>:deployment-<n>; static output lives in deployments/<app>/<n>/dist. The newest ones are kept for instant rollback.' },
                    { term: 'Webhooks never build', description: 'The endpoint verifies the signature over the raw body, records the event, queues a deployment and answers 202 straight away.' },
                  ]}
                />
                {FRAMEWORKS.map((f) => (
                  <Text key={f.kind} as="p" style={{ margin: 0 }}><strong>{f.kind}:</strong> {f.items}</Text>
                ))}
              </div>
            ),
          },
          {
            id: 'quickstart',
            title: 'Quick start',
            content: (
              <div className="sy-stack">
                <Text as="p" style={{ margin: 0 }}>
                  You need a Linux server with Docker 24 or later, with ports 80 and 443 reachable for the domains you want to serve.
                </Text>
                {code(`git clone ${REPO}.git && cd Shipyard
cp config/shipyard.env.example .env      # set SHIPYARD_PUBLIC_URL at least
docker compose up -d --build`)}
                <DescriptionList
                  columns={1}
                  bordered
                  items={[
                    { term: '1. Create the administrator', description: 'Open http://<server>:8080 (or your SHIPYARD_PLATFORM_DOMAIN) and create the admin account.' },
                    { term: '2. Add an application', description: 'Enter the repository and branch, review what was detected, add a domain and select Deploy.' },
                    { term: '3. Connect the webhook', description: 'In GitHub, open Settings → Webhooks → Add webhook and paste the payload URL and secret from the application\'s Webhook tab (content type application/json, push events).' },
                    { term: '4. git push', description: 'Every push to the deployment branch builds, tests, health-checks and goes live automatically.' },
                  ]}
                />
                <Title level={3} size="sm">Optional platform.yml</Title>
                {code(`build:
  install: npm ci
  command: npm run build
  test: npm test
runtime:
  command: npm start
  port: 3000
healthcheck:
  path: /health`)}
              </div>
            ),
          },
          {
            id: 'configuration',
            title: 'Configuration',
            content: (
              <div className="sy-stack">
                <Table caption="Environment variables" hideCaption columns={envColumns} data={ENV} getRowId={(r) => r.name} density="compact" responsive="stack" />
                <MessageStrip tone="warning" title="Backups">
                  Back up the data directory. The essential files are database/shipyard.db and database/secret.key; without the key,
                  stored secrets cannot be decrypted.
                </MessageStrip>
              </div>
            ),
          },
          {
            id: 'security',
            title: 'Security',
            content: (
              <DescriptionList
                columns={2}
                bordered
                items={[
                  { term: 'Isolation', description: 'Builds and applications run only in containers with CPU, memory and PID limits and no-new-privileges. Mounting the Docker socket into an application is refused.' },
                  { term: 'Secrets', description: 'Environment variables, webhook secrets and tokens are encrypted with AES-256-GCM, masked in every log and never returned by the API.' },
                  { term: 'Webhooks', description: 'X-Hub-Signature-256 is verified over the raw body; invalid signatures get 401. Endpoint IDs are random and deliveries are de-duplicated.' },
                  { term: 'Proxy', description: "Caddy's admin API listens on a Unix socket shared only with Shipyard, and Caddy only sees the deployments directory." },
                  { term: 'Access', description: 'Single administrator, Argon2id password hashing, session cookies with CSRF protection, and an audit log of every change.' },
                  { term: 'Scope', description: 'Built for one trusted operator on a single server. Kubernetes, multi-node orchestration and multi-tenancy are deliberately out of scope.' },
                ]}
              />
            ),
          },
          {
            id: 'architecture',
            title: 'Architecture',
            content: (
              <div className="sy-stack">
                {code(`                 Internet
                    │
               ┌────▼────┐   admin API (Unix socket)   ┌──────────────────────────────┐
               │  Caddy  │◄────────────────────────────│ Shipyard                      │
               └─┬─────┬─┘                             │  REST API + SSE               │
   static files  │     │ reverse_proxy                 │  Webhook handler              │
 deployments/…   │     ▼                               │  Deployment queue + workers   │
                 │  app containers ◄── docker CLI ─────│  Runtime monitor, metrics     │
                 │  (shipyard network)                 │  SQLite                       │
                 └─────────────────────────────────────┴──────────────────────────────┘`)}
                <Text as="p" style={{ margin: 0 }}>
                  Read the <Link href={`${REPO}/blob/main/docs/architecture.md`} target="_blank" rel="noreferrer">architecture notes</Link> and
                  the <Link href={`${REPO}#readme`} target="_blank" rel="noreferrer">README</Link> for package layout, data model and the full deployment flow.
                </Text>
              </div>
            ),
          },
        ]}
      />
    </ThemeProvider>
  );
}
