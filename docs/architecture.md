# Architecture

Shipyard is one process (Spring Boot) plus Caddy, both running next to the
Docker daemon on a single server.

```
                 Internet
                    │
               ┌────▼────┐   admin API (Unix socket)   ┌──────────────────────────────┐
               │  Caddy  │◄────────────────────────────│ Shipyard                      │
               └─┬─────┬─┘                             │  REST API + SSE               │
   static files  │     │ reverse_proxy                 │  Webhook handler              │
 deployments/…   │     ▼                               │  Deployment queue + workers   │
                 │  app containers ◄── docker CLI ─────│  Runtime monitor, metrics     │
                 │  (shipyard network)                 │  SQLite                       │
                 └─────────────────────────────────────┴──────────────────────────────┘
```

## Backend packages (`backend/src/main/java/dev/shipyard`)

| Package | Responsibility |
| --- | --- |
| `applications` | Application and service model (MVP: one `web` service per application) |
| `repositories` | `RepositoryManager`: clone/fetch, resolve branch heads, export an exact commit with `git archive` |
| `detection` | `ProjectDetector` (framework → platform.yml → Dockerfile → explicit settings), detection preview |
| `builds` | `BuildEngine` and `DockerfileGenerator`: static exports and images, always inside `docker build` |
| `runtime` | `ContainerRuntime` abstraction and `DockerRuntime` (docker CLI). Nothing else touches Docker |
| `deployments` | `DeploymentService` (single entry for every trigger), persistent `DeploymentQueue`, `DeploymentWorker`, `DeploymentPipeline`, `HealthChecker`, cleanup |
| `webhooks` | `WebhookProvider` abstraction, `GitHubWebhookProvider` (HMAC-SHA256), routing, history |
| `caddy` | Caddyfile generation and live reload through the admin API |
| `domains`, `environment` | Domains; encrypted environment variables and log masking |
| `logging` | Build and deployment logs in SQLite, streamed live |
| `metrics` | Host and container sampling (in-memory history), crash detection |
| `events` | In-process event bus and Server-Sent Events streams |
| `auth`, `audit`, `security` | Admin login (Argon2id, sessions, CSRF), audit log, AES-GCM secret cipher |

## Data

SQLite with WAL, schema in `backend/src/main/resources/db/migration`. Tables
follow the specification: `users`, `applications`, `application_services`,
`repositories`, `deployments`, `deployment_queue`, `environment_variables`,
`domains`, `webhooks`, `webhook_events`, `build_logs`, `deployment_logs`,
`audit_events`.

Filesystem (`SHIPYARD_DATA_DIR`):

```
repositories/<app-id>/              git clone used to fetch and export commits
builds/<app-id>/<n>/                temporary build context (deleted after each build)
deployments/<app-id>/<n>/dist/      immutable static artifact
deployments/<app-id>/<n>/Dockerfile.generated
deployments/<app-id>/current -> <n>
caddy/Caddyfile                     last generated proxy configuration
database/shipyard.db, secret.key
```

## Deployment flow

1. A trigger (UI, API, webhook) calls `DeploymentService.request`, which creates a
   `deployments` row and a `deployment_queue` item in one transaction, cancelling
   still-queued items for the same application when *cancel stale builds* is on.
2. `DeploymentWorker` claims runnable items (at most one per application) and runs
   `DeploymentPipeline`.
3. The pipeline fetches, resolves the exact commit, exports it, detects, builds
   (tests run inside the build), starts a new container or prepares the static
   artifact, and health-checks it.
4. Only then is traffic switched: `current` symlink, application row, Caddy reload,
   and the previous container is stopped and removed (its image is kept).
5. On any failure the new container is removed (or kept stopped when automatic
   rollback is off), the deployment is marked `FAILED`, and the current deployment
   keeps serving.

Rollback is a queue item of kind `ROLLBACK` that starts a fresh container from a
stored image (or repoints to a stored static artifact), health-checks it and
switches traffic the same way.

Queue items left `RUNNING` by a crash are marked failed at startup; queued items
survive restarts.

## Webhooks

`POST /api/webhooks/github/{endpoint-id}` → verify signature over the raw bytes
(401 if invalid) → de-duplicate by delivery ID → record → filter (event type,
repository, branch, branch deletion, automatic deployment, already-deployed
commit) → queue → `202 Accepted`. The request never waits for a build. Webhook
event status follows the deployment (`QUEUED → PROCESSING → DEPLOYED/FAILED`).

## Frontend (`frontend/src`)

React + TypeScript, with ENTERPRISE-UI as the only component library and design
system (`@enterprise-ui/react`, vendored tarball):

- `layouts/PlatformShell` — `AppShell`, `ApplicationHeader` (system status,
  notifications, user menu), `SideNavigation`
- `pages/` — Dashboard (`Dashboard`, `MetricCard`), Applications (`DataGrid`,
  `FilterBar`), Add Application (`Wizard`), application object page (`Page`,
  route-bound `Tabs`, `DescriptionList`), deployment details (`ProcessFlow`),
  Webhook, Infrastructure, Activity (`Timeline`), Settings (`ThemeProvider`
  theme and density)
- `components/` — platform components built from ENTERPRISE-UI parts and
  `--eui-*` tokens: `BuildLogViewer`, `DeploymentTimeline`, `ResourceMetrics`,
  `ApplicationStatus`, `DeploymentStatus`
- `services/api.ts` (REST + CSRF), `services/events.ts` (shared SSE streams),
  `hooks/` (server state that refreshes on events), `state/` (UI state: session,
  theme, density)

`styles/platform.css` only lays out the custom log viewer and a few grids; every
colour, font and spacing value is an ENTERPRISE-UI token.
