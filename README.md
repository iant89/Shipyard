# Shipyard

A small self-hosted application platform for a homelab: give it a GitHub
repository and it detects, builds, deploys and serves the application, then
redeploys on every `git push`.

- **Static sites** (HTML, React, Vue, Angular, Vite, Astro, Svelte, static Next.js, …)
  are built in a container and served directly by Caddy.
- **Server applications** (Node.js, Bun, Python, Go, Rust, Spring Boot / Maven / Gradle,
  .NET, PHP, or anything with a `Dockerfile`) run as resource-limited containers
  behind Caddy.
- **GitHub webhooks** with HMAC-SHA256 verification, duplicate-delivery protection,
  branch filtering and stale-build cancellation.
- **Safe deployments**: every deployment pins an exact commit, is health-checked
  before it receives traffic, and a failed deployment never replaces a healthy one.
  Rollback re-activates a stored artifact without rebuilding.
- **Management UI** built entirely with [ENTERPRISE-UI](https://github.com/iant89/ENTERPRISE-UI):
  dashboard, application object pages, an Add Application wizard, live build and
  application logs, metrics, webhook history and settings, in light, dark and
  high-contrast themes on desktop, tablet and phone.

Stack: Java 21 + Spring Boot, SQLite, Docker, Caddy, React + TypeScript + ENTERPRISE-UI.

## Quick start

Requirements: a Linux server with Docker 24+ (BuildKit is used for builds) and
ports 80/443 reachable for the domains you want to serve.

```bash
git clone https://github.com/iant89/Shipyard.git && cd Shipyard
cp config/shipyard.env.example .env      # set SHIPYARD_PUBLIC_URL at least
docker compose up -d --build
```

Open `http://<server>:8080` (or `https://$SHIPYARD_PLATFORM_DOMAIN`), create the
administrator account, then **Add application**:

1. Enter a name and the repository (`owner/repo`, `github.com/owner/repo` or an
   https URL). Private repositories take a GitHub token with read access to contents.
2. Review what was detected and override anything you need.
3. Add a domain, choose automatic deployment, health check and limits.
4. Review and **Deploy**.

To deploy on push, open the application's **Webhook** tab and add a webhook in
GitHub (*Settings → Webhooks → Add webhook*) with the payload URL and secret shown
there, content type `application/json`, and the *push* event. Use **Test webhook**
to check the configuration without deploying.

Point each domain's DNS at the server. Caddy obtains and renews certificates
automatically; switch HTTPS off for LAN-only names.

## How an application is built

Detection order (highest priority first):

1. Settings stored on the application (the *Settings* tab)
2. `Dockerfile` in the repository (or root directory) — built as-is
3. [`platform.yml`](platform.example.yml) — commands, output directory, port, health check
4. Framework detection from `package.json`, `pom.xml`, `build.gradle`, `go.mod`,
   `Cargo.toml`, `requirements.txt`/`pyproject.toml`, `*.csproj`, `composer.json`,
   `index.html`
5. Otherwise the deployment fails asking for manual configuration

Repository code never runs on the host. Shipyard exports the exact commit with
`git archive`, then runs install, build and test steps inside `docker build`:

- static sites use a generated multi-stage Dockerfile whose last stage contains
  only the output directory, exported with `--output type=local` into
  `deployments/<app>/<n>/dist`;
- server applications become an immutable image `shipyard/<app>:deployment-<n>`
  started with CPU, memory and PID limits, `no-new-privileges`, on the internal
  `shipyard` network, with no published ports.

The generated Dockerfile is saved next to each deployment's artifact.

## Deployment pipeline

```
CREATED → QUEUED → CLONING → DETECTING → BUILDING → TESTING → DEPLOYING → STARTING → HEALTH_CHECK → RUNNING
                                                                                         └→ FAILED (previous stays online)
```

Manual, webhook and API deployments all go through the same persistent queue
and pipeline. One deployment runs per application at a time; different
applications deploy concurrently (`SHIPYARD_WORKER_THREADS`). With *cancel stale
builds* on, a newer push cancels deployments that are still queued, so the newest
commit wins.

A new server deployment starts in its own container next to the current one. Only
after its health check passes (HTTP 2xx/3xx on the configured path, TCP, or
process stability) does Shipyard regenerate the Caddy configuration and retire the
previous container. Its image and static artifacts are kept
(`SHIPYARD_RETAIN_DEPLOYMENTS`) for instant rollback.

## Configuration

All settings are environment variables (see [`config/shipyard.env.example`](config/shipyard.env.example)):

| Variable | Default | Purpose |
| --- | --- | --- |
| `SHIPYARD_PUBLIC_URL` | `http://localhost:8080` | Base URL used for webhook URLs |
| `SHIPYARD_PLATFORM_DOMAIN` | – | Serve the UI through Caddy on this hostname |
| `SHIPYARD_BASE_DOMAIN` | – | Also serve every app at `<slug>.<base domain>` |
| `SHIPYARD_ACME_EMAIL` | – | Let's Encrypt account email |
| `SHIPYARD_DATA_DIR` | `/var/lib/shipyard` | Database, repositories, artifacts, Caddyfile |
| `SHIPYARD_CADDY_ADMIN_URL` | – | `unix:/path/admin.sock` or `http://caddy:2019` |
| `SHIPYARD_DOCKER_NETWORK` | `shipyard` | Network shared by Caddy and applications |
| `SHIPYARD_WORKER_THREADS` | `2` | Concurrent deployments across applications |
| `SHIPYARD_RETAIN_DEPLOYMENTS` | `10` | Artifacts kept per application |
| `SHIPYARD_CRASH_RESTART_THRESHOLD` | `5` | Restarts before an app is marked CRASHED |
| `SHIPYARD_ADMIN_USERNAME` / `_PASSWORD` | – | Create the first admin non-interactively |
| `SHIPYARD_ALLOW_LOCAL_REPOSITORIES` | `false` | Allow `file://` repositories (development) |

### Backup

Back up the data directory. The essential files are
`database/shipyard.db` (SQLite) and `database/secret.key` (encrypts environment
variables, webhook secrets and access tokens — without it they cannot be read).

## Security model

- Builds and applications run only in containers, with memory, CPU and PID limits.
- The Docker socket is mounted into the Shipyard container only; mounting it into
  an application is refused by the runtime layer.
- Caddy's admin API listens on a Unix socket shared only with Shipyard, so
  applications on the `shipyard` network cannot reconfigure routing. Caddy sees only
  the `deployments/` directory, never the database or keys.
- Secrets are encrypted at rest (AES-256-GCM), masked in build, deployment and
  application logs, and never returned by the API. Non-secret variables are also
  passed to builds as build arguments; secrets are runtime-only.
- Webhooks require a valid `X-Hub-Signature-256` over the raw body (invalid → 401),
  endpoint IDs are random 160-bit tokens, and deliveries are de-duplicated by
  `X-GitHub-Delivery`.
- Single administrator account, Argon2id password hashing, session cookies with
  CSRF protection, audit log of every change.

## Development

```bash
scripts/dev.sh                 # API on :8080, UI on http://localhost:5173
cd backend && mvn test         # unit + end-to-end API tests
cd frontend && npm run build   # type-check and build the UI
```

`@enterprise-ui/react` is not published to npm yet, so the frontend depends on a
tarball in `frontend/vendor/`. Refresh it from the ENTERPRISE-UI repository with
`scripts/update-enterprise-ui.sh [git-ref]`.

More detail: [docs/architecture.md](docs/architecture.md).

## Not in the MVP

Multi-service applications (the data model and pipeline are already service-aware),
GitHub App integration and automatic webhook creation, GitLab/Gitea/Bitbucket,
Podman, build caching, preview environments, notifications, multiple users and RBAC,
historical metrics, PostgreSQL. Kubernetes and multi-node orchestration are
deliberately out of scope.
