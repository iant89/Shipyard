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

**[Documentation and live demo →](https://iant89.github.io/Shipyard/)** — the demo is the real
interface running against a simulated backend in your browser.

## Quick start

Requirements: a Linux server with Docker 24+ (BuildKit is used for builds) and
ports 80/443 reachable for the domains you want to serve.

On Ubuntu, one command installs Docker, clones Shipyard, writes `.env` and
starts the stack:

```bash
curl -fsSL https://raw.githubusercontent.com/iant89/Shipyard/main/scripts/install-ubuntu.sh | sudo bash
```

Pass options after `-s --`, e.g.
`... | sudo bash -s -- --public-url https://shipyard.example.com`, or `--dev`
to also install OpenJDK 21, Maven and Node 22 for development
(`scripts/install-ubuntu.sh --help` for all options).

Or do it manually on any Linux host:

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

No webhook (for example, a server GitHub can't reach)? Turn on **Automatic updates**
in the application's *Settings*: Shipyard checks the branch every 90 seconds and
deploys new commits. A commit is tried once, so a failing commit is not rebuilt
over and over; the next push is picked up as usual. A stopped application is left
stopped.

`.local` hostnames (`devstick.local`, …) of running applications are published
on your network with mDNS, so any computer on the LAN resolves them to this
server with no DNS setup (macOS and iOS natively, Windows 10+, Linux with
`avahi`/`systemd-resolved`). A name is announced when its application starts and
withdrawn when it is stopped or deleted. The `mdns` compose service answers; it
runs on the host network and shares UDP port 5353 with `avahi-daemon` if the
server runs one. Other local names (`.lan`, `.home`, …) are not mDNS names; point
them at the server in your router's DNS. Turn it off with `SHIPYARD_MDNS=false`.

Point each domain's DNS at the server. Caddy obtains and renews certificates
automatically; switch HTTPS off for a single LAN-only name, or set
`SHIPYARD_HTTPS=false` to serve the whole platform over plain HTTP (no
certificates, no 443 listener) — for example behind another TLS proxy.

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

### Persistent volumes

Containers are replaced on every deployment, so anything an application writes
inside its container is lost unless the path is a **volume** (Settings → Volumes,
or the Deployment step of Add Application, which suggests the Dockerfile's
`VOLUME` paths). Each container path, e.g. `/data`, is backed by a named Docker
volume, `shipyard-<slug>-data`, that every deployment, rollback and restart mounts.
A new Docker volume starts with the image's files and ownership at that path, so
images that run as an unprivileged user (like `oven/bun`) can write to it.
Deleting the application deletes its volumes. Back them up with e.g.
`docker run --rm -v shipyard-myapp-data:/v -v "$PWD":/b alpine tar czf /b/myapp-data.tgz -C /v .`

During a deployment the new container starts while the current one still runs,
so both mount the volume for a few seconds. SQLite and most databases handle that;
software that takes an exclusive lock on its data directory may fail the health
check until the previous container stops.

### Console

An application's **Console** tab runs shell commands in its running container
(`docker exec … sh -c`), as the image's user, e.g. `cat /data/setup-token`. The
working directory carries over between commands, so `cd` works; each command may run
for 60 seconds and returns up to 256 KB of output. It is not a TTY, so interactive
programs (vi, less, top) don't work. Secret environment values are masked in the
output like in the logs, and every command is recorded in Activity. Images without
`/bin/sh` (distroless, scratch) can't use it. Output keeps its ANSI colors; ↑/↓ recall
earlier commands, Ctrl+C stops waiting for a command (it still runs until it finishes
or reaches the 60-second limit), and Ctrl+L or `clear` clears the screen. The
transcript can be copied or downloaded.

### Application network

Caddy and every application share the `shipyard` Docker network. Its subnet is fixed
(`SHIPYARD_DOCKER_SUBNET`, default `172.30.0.0/22`) so applications can trust Caddy as
their reverse proxy by range, for example `CLAWROUTER_TRUSTED_PROXIES=172.30.0.0/22`.
Infrastructure shows the subnet in use. The network is created by `scripts/network.sh`
(the installer and updater call it) and is `external` in `docker-compose.yml`. Installs
created before the subnet was pinned keep their Docker-chosen range until you run
`sudo scripts/pin-network-subnet.sh` from the checkout (about a minute of downtime).

## Configuration

All settings are environment variables (see [`config/shipyard.env.example`](config/shipyard.env.example)):

| Variable | Default | Purpose |
| --- | --- | --- |
| `SHIPYARD_PUBLIC_URL` | `http://localhost:8080` | Base URL used for webhook URLs |
| `SHIPYARD_PLATFORM_DOMAIN` | – | Serve the UI through Caddy on this hostname |
| `SHIPYARD_BASE_DOMAIN` | – | Also serve every app at `<slug>.<base domain>` |
| `SHIPYARD_HTTPS` | `false` | Plain HTTP by default; `true` enables automatic TLS certificates on port 443 |
| `SHIPYARD_ACME_EMAIL` | – | Let's Encrypt account email |
| `SHIPYARD_DATA_DIR` | `/var/lib/shipyard` | Database, repositories, artifacts, Caddyfile |
| `SHIPYARD_CADDY_ADMIN_URL` | – | `unix:/path/admin.sock` or `http://caddy:2019` |
| `SHIPYARD_DOCKER_NETWORK` | `shipyard` | Network shared by Caddy and applications |
| `SHIPYARD_DOCKER_SUBNET` | `172.30.0.0/22` | Subnet of that network when it is created (see *Application network*) |
| `SHIPYARD_WORKER_THREADS` | `2` | Concurrent deployments across applications |
| `SHIPYARD_RETAIN_DEPLOYMENTS` | `10` | Artifacts kept per application |
| `SHIPYARD_CRASH_RESTART_THRESHOLD` | `5` | Restarts before an app is marked CRASHED |
| `SHIPYARD_ADMIN_USERNAME` / `_PASSWORD` | – | Create the first admin non-interactively |
| `SHIPYARD_ALLOW_LOCAL_REPOSITORIES` | `false` | Allow `file://` repositories (development) |
| `SHIPYARD_MDNS` | `true` | Publish `.local` hostnames of running applications on the LAN with mDNS (see below) |
| `SHIPYARD_MDNS_ADDRESS` | – | Address mDNS answers with (default: the server's address on the asking client's network) |
| `SHIPYARD_HTTP_PORT` | detected | Host port Caddy's HTTP listener is published on. Local hostnames (`devstick.local`, `localhost`, `.lan`, IPs…) get it in their URLs when it isn't 80. Read from the Caddy container when unset |

### Backup

Back up the data directory. The essential files are
`database/shipyard.db` (SQLite) and `database/secret.key` (encrypts environment
variables, webhook secrets and access tokens — without it they cannot be read).
Application volumes are Docker volumes named `shipyard-<slug>-…` (see *Persistent volumes*).

### Updating Shipyard

Shipyard follows the branch it was installed from (normally `main`). It checks
that branch for newer commits; when there are some, the header shows **Update
available** and *Settings → Shipyard updates* lists them. Pick a commit (the newest
is preselected) and click **Update**. Nothing updates on its own.

The update runs in a short-lived `shipyard-updater` container, using
[`scripts/self-update.sh`](scripts/self-update.sh) in your checkout:

1. Fast-forwards the checkout to the chosen commit. Local edits, such as ports set by
   the installer, are kept. If they conflict with the update, it stops and changes nothing.
2. Builds the new image while the current version keeps running. If the build fails,
   nothing is restarted.
3. Stops Shipyard, backs up `database/` to `platform-update/backup/` in the data
   directory, and starts the new version.
4. If the new version is not healthy within 5 minutes, restores the previous image,
   database and checkout.

Your applications keep running throughout. The Shipyard UI is unavailable for a
minute or two and you sign in again afterwards; the result and log are on the
same Settings card. Updating is refused while deployments are queued or running.

You can also update from the server: `scripts/self-update.sh [commit]` in the
Shipyard directory (defaults to the latest commit).

Installs from before self-update need one manual update to get it:
`git pull && SHIPYARD_COMMIT=$(git rev-parse HEAD) docker compose up -d --build`.

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
scripts/test-self-update.sh    # self-update scenarios against a fake docker CLI
```

The GitHub Pages site (`frontend/site/`) is the documentation page plus the same UI at `/demo/`
running against `src/demo/demoBackend.ts`, a simulated backend. Build it with
`npm run build:pages` (output in `frontend/dist-pages/`, preview with `npm run dev:pages`);
`.github/workflows/pages.yml` publishes it on every push to `main` that touches `frontend/`.
Enable it once under *Settings → Pages → Source: GitHub Actions*.

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
