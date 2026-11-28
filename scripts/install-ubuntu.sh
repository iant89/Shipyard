#!/usr/bin/env bash
# Shipyard installer for Ubuntu (20.04 / 22.04 / 24.04).
#
# Installs every host dependency (Docker 24+ with BuildKit and the Compose
# plugin, git), clones Shipyard, writes .env and starts the stack.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/iant89/Shipyard/main/scripts/install-ubuntu.sh | sudo bash
#   curl -fsSL .../install-ubuntu.sh | sudo bash -s -- --public-url https://shipyard.example.com
#   sudo scripts/install-ubuntu.sh --dev          # also install the dev toolchain
#
# Options:
#   --public-url URL   Base URL of the platform (default: http://<lan-ip>:8080)
#   --host-port PORT   Host port for Caddy (80/443) if different (default: 80/443)
#   --http-port PORT   Host port for UI (SHIPYARD_UI_PORT) if different (default: 8080)
#   --repo-dir DIR     Where to clone Shipyard (default: /opt/shipyard)
#   --dev              Also install dev dependencies: OpenJDK 21, Maven, Node 22
#   --no-start         Install everything but do not build/start the stack
#   --no-https         Serve plain HTTP only (no certificates, no 443 listener)
#   --yes, -y          Non-interactive; accept all defaults
#   --help, -h         Show this help

set -euo pipefail

REPO_URL="https://github.com/iant89/Shipyard.git"
DEFAULT_REPO_DIR="/opt/shipyard"

HOST_PORT=""
HTTP_PORT=""
PUBLIC_URL=""
REPO_DIR=""
DEV=0
START=1
HTTPS=1
ASSUME_YES=0

log()  { printf '\n\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Shipyard installer for Ubuntu (20.04 / 22.04 / 24.04).

Installs every host dependency (Docker 24+ with BuildKit and the Compose
plugin, git), clones Shipyard, writes .env and starts the stack.

Usage:
  curl -fsSL https://raw.githubusercontent.com/iant89/Shipyard/main/scripts/install-ubuntu.sh | sudo bash
  curl -fsSL .../install-ubuntu.sh | sudo bash -s -- --public-url https://shipyard.example.com
  sudo scripts/install-ubuntu.sh --dev          # also install the dev toolchain

Options:
  --public-url URL   Base URL of the platform (default: http://<lan-ip>:8080)
  --host-port PORT   Host port for Caddy (80/443) if different (default: 80/443)
  --http-port PORT   Host port for UI (SHIPYARD_UI_PORT) if different (default: 8080)
  --repo-dir DIR     Where to clone Shipyard (default: /opt/shipyard)
  --dev              Also install dev dependencies: OpenJDK 21, Maven, Node 22
  --no-start         Install everything but do not build/start the stack
  --no-https         Serve plain HTTP only (no certificates, no 443 listener)
  --yes, -y          Non-interactive; accept all defaults
  --help, -h         Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --public-url) PUBLIC_URL="${2:?--public-url needs a value}"; shift 2 ;;
    --public-url=*) PUBLIC_URL="${1#*=}"; shift ;;
    --host-port) HOST_PORT="${2:?--host-port needs a value}"; shift 2 ;;
    --host-port=*) HOST_PORT="${1#*=}"; shift ;;
    --http-port) HTTP_PORT="${2:?--http-port needs a value}"; shift 2 ;;
    --http-port=*) HTTP_PORT="${1#*=}"; shift ;;
    --repo-dir) REPO_DIR="${2:?--repo-dir needs a value}"; shift 2 ;;
    --repo-dir=*) REPO_DIR="${1#*=}"; shift ;;
    --dev) DEV=1; shift ;;
    --no-https) HTTPS=0; shift ;;
    --no-start) START=0; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

# ---------------------------------------------------------------- privileges
if [[ $EUID -ne 0 ]]; then
  # Re-exec through sudo only when we are a real file; when piped on stdin
  # ($0 is "bash") the caller must already be root.
  if [[ "$0" != "bash" && -f "$0" ]]; then
    command -v sudo >/dev/null || die "root privileges required (sudo not found)"
    exec sudo -E bash "$0" "$@"
  fi
  die "run with sudo: curl -fsSL https://raw.githubusercontent.com/iant89/Shipyard/main/scripts/install-ubuntu.sh | sudo bash"
fi

export DEBIAN_FRONTEND=noninteractive

# ---------------------------------------------------------------- os check
if [[ -r /etc/os-release ]]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  [[ "${ID:-}" == "ubuntu" ]] || warn "this installer targets Ubuntu; detected ${ID:-unknown} — continuing anyway"
else
  warn "/etc/os-release not found; cannot verify the OS"
fi

# ---------------------------------------------------------------- helpers
# Prompt on the terminal (works even when the script itself is piped on stdin).
# usage: prompt VAR "label" "default"
prompt() {
  local __var="$1" __label="$2" __default="$3" __reply=""
  if [[ $ASSUME_YES -eq 0 && -r /dev/tty ]]; then
    read -r -p "$__label [$__default]: " __reply </dev/tty || __reply=""
  fi
  printf -v "$__var" '%s' "${__reply:-$__default}"
}

# set_env KEY VALUE — set (or append) a key in the project .env
set_env() {
  local key="$1" value="$2"
  value="${value//&/\\&}"
  if grep -q "^${key}=" .env; then
    sed -i "s|^${key}=.*|${key}=${value}|" .env
  else
    printf '%s=%s\n' "$key" "$value" >>.env
  fi
}

get_env() {
  grep -E "^$1=" .env | tail -n1 | cut -d= -f2- || true
}

lan_ip() {
  local out=""
  out="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<NF;i++) if ($i=="src") print $(i+1)}' | head -n1)" || out=""
  if [[ -z "$out" ]]; then
    out="$(hostname -I 2>/dev/null | awk '{print $1}')" || out=""
  fi
  echo "${out:-localhost}"
}

# ---------------------------------------------------------------- base packages
log "Installing base packages (git, curl, ca-certificates, gnupg)"
apt-get update -y -qq
apt-get install -y -qq ca-certificates curl gnupg git >/dev/null

# ---------------------------------------------------------------- docker
docker_major() {
  docker --version 2>/dev/null | grep -oE '[0-9]+' | head -n1 || echo 0
}

if command -v docker >/dev/null && docker compose version >/dev/null 2>&1 && [[ "$(docker_major)" -ge 24 ]]; then
  log "Docker $(docker --version | grep -oE '[0-9]+(\.[0-9]+)+' | head -n1) with Compose plugin already installed"
else
  log "Installing Docker Engine, BuildKit and the Compose plugin"
  # Remove distro-packaged Docker so docker-ce can take over (docs.docker.com).
  apt-get remove -y -qq docker.io docker-doc docker-compose podman-docker containerd runc >/dev/null 2>&1 || true
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  codename="$(. /etc/os-release && echo "$VERSION_CODENAME")"
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${codename} stable" \
    >/etc/apt/sources.list.d/docker.list
  apt-get update -y -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin >/dev/null
fi

systemctl enable --now docker >/dev/null 2>&1 || warn "could not enable the docker service (no systemd?)"
docker info >/dev/null 2>&1 || die "the Docker daemon is not running; check: systemctl status docker"

# Allow the invoking user to run docker without sudo (takes effect at next login).
if [[ -n "${SUDO_USER:-}" && "${SUDO_USER}" != "root" ]]; then
  usermod -aG docker "$SUDO_USER" 2>/dev/null || true
fi

# ---------------------------------------------------------------- dev toolchain
if [[ $DEV -eq 1 ]]; then
  log "Installing dev dependencies (OpenJDK 21, Maven, Node 22)"
  apt-get install -y -qq openjdk-21-jdk maven >/dev/null \
    || warn "could not install openjdk-21-jdk from apt; install Temurin 21 manually for backend development"
  if ! command -v node >/dev/null || [[ "$(node --version | tr -d v | cut -d. -f1)" -lt 22 ]]; then
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null 2>&1 \
      && apt-get install -y -qq nodejs >/dev/null \
      || warn "could not install Node 22 from NodeSource; install it manually (frontend needs >= 22)"
  fi
fi

# ---------------------------------------------------------------- source
SHIPYARD_ROOT=""
# 1) already inside a checkout (run from the repo root or scripts/)
for candidate in "$PWD" "${BASH_SOURCE[0]:+$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd)/..}"; do
  if [[ -n "$candidate" && -f "$candidate/docker-compose.yml" ]] && grep -q '^name: shipyard' "$candidate/docker-compose.yml" 2>/dev/null; then
    SHIPYARD_ROOT="$(cd "$candidate" && pwd)"
    break
  fi
done

if [[ -n "$SHIPYARD_ROOT" ]]; then
  log "Using existing checkout: $SHIPYARD_ROOT"
else
  REPO_DIR="${REPO_DIR:-$DEFAULT_REPO_DIR}"
  if [[ -d "$REPO_DIR/.git" ]]; then
    log "Updating $REPO_DIR"
    git -C "$REPO_DIR" pull --ff-only --quiet
  elif [[ -e "$REPO_DIR" ]] && [[ -n "$(ls -A "$REPO_DIR" 2>/dev/null)" ]]; then
    die "$REPO_DIR exists and is not a Shipyard checkout; remove it or pass --repo-dir"
  else
    log "Cloning Shipyard into $REPO_DIR"
    git clone --depth 1 "$REPO_URL" "$REPO_DIR"
  fi
  SHIPYARD_ROOT="$REPO_DIR"
fi

cd "$SHIPYARD_ROOT"

# ---------------------------------------------------------------- .env
if [[ ! -f .env ]]; then
  cp config/shipyard.env.example .env
  log "Created .env from config/shipyard.env.example"
fi

# Override SHIPYARD_UI_PORT if --http-port is specified
if [[ -n "$HTTP_PORT" ]]; then
  set_env SHIPYARD_UI_PORT "$HTTP_PORT"
  log "Set SHIPYARD_UI_PORT to $HTTP_PORT"
fi

ui_port_default="$(get_env SHIPYARD_UI_PORT)"
ui_port_default="${ui_port_default:-8080}"

# HTTPS: on by default. --no-https serves plain HTTP and decouples PUBLIC_URL.
if [[ $HTTPS -eq 0 ]]; then
  set_env SHIPYARD_HTTPS false
  log "HTTPS disabled: Caddy serves plain HTTP on port 80 only"
elif [[ "$(get_env SHIPYARD_HTTPS)" == "false" ]]; then
  HTTPS=0
  log "SHIPYARD_HTTPS=false in .env: Caddy serves plain HTTP on port 80 only"
fi

if [[ -z "$PUBLIC_URL" ]]; then
  prompt PUBLIC_URL "Public URL of the platform (used for webhook URLs)" "http://$(lan_ip):${ui_port_default}"
fi
set_env SHIPYARD_PUBLIC_URL "$PUBLIC_URL"

if [[ $HTTPS -eq 0 && "$PUBLIC_URL" == https://* ]]; then
  warn "HTTPS is disabled but SHIPYARD_PUBLIC_URL is $PUBLIC_URL; webhook URLs will point at a scheme this server does not serve"
fi

if [[ $HTTPS -eq 1 && "$PUBLIC_URL" == https://* && -z "$(get_env SHIPYARD_ACME_EMAIL)" ]]; then
  ACME_EMAIL=""
  prompt ACME_EMAIL "Email for Let's Encrypt (empty to skip)" ""
  if [[ -n "$ACME_EMAIL" ]]; then
    set_env SHIPYARD_ACME_EMAIL "$ACME_EMAIL"
  fi
fi

chmod 600 .env

# ---------------------------------------------------------------- firewall
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q '^Status: active'; then
  allowed="${HOST_PORT:-80}/tcp, ${HTTP_PORT:-${ui_port_default}}/tcp"
  if [[ $HTTPS -eq 1 ]]; then allowed="${HOST_PORT:-80}/tcp, ${HOST_PORT:-443}/tcp, ${HTTP_PORT:-${ui_port_default}}/tcp"; fi
  log "ufw is active — allowing $allowed"
  ufw allow "${HOST_PORT:-80}/tcp" >/dev/null
  if [[ $HTTPS -eq 1 ]]; then ufw allow "${HOST_PORT:-443}/tcp" >/dev/null; fi
  ufw allow "${HTTP_PORT:-${ui_port_default}}/tcp" >/dev/null
fi

# ---------------------------------------------------------------- start
if [[ $START -eq 1 ]]; then
  if command -v ss >/dev/null && ss -lnt 2>/dev/null | grep -qE ':(${HOST_PORT:-80}|${HOST_PORT:-443}) '; then
    warn "ports ${HOST_PORT:-80}/${HOST_PORT:-443} already have listeners — the Caddy service may fail to start"
  fi
  if [[ -n "$HTTP_PORT" ]] && command -v ss >/dev/null && ss -lnt 2>/dev/null | grep -qE ":${HTTP_PORT} "; then
    warn "port ${HTTP_PORT} already has a listener — the Shipyard UI service may fail to start"
  fi

  # Update docker-compose.yml to use HOST_PORT and HTTP_PORT if specified
  if [[ -n "$HOST_PORT" ]]; then
    log "Adjusting docker-compose.yml to use host port $HOST_PORT for Caddy"
    sed -i "s|\"80:80\"|\"${HOST_PORT}:80\"|g" docker-compose.yml
    sed -i "s|\"443:443\"|\"${HOST_PORT}:443\"|g" docker-compose.yml
    sed -i "s|\"443:443/udp\"|\"${HOST_PORT}:443/udp\"|g" docker-compose.yml
  fi

  if [[ -n "$HTTP_PORT" ]]; then
    log "Adjusting docker-compose.yml to use host port $HTTP_PORT for Shipyard UI"
    # Replace ${SHIPYARD_UI_PORT:-8080}:8080 with ${HTTP_PORT}:8080
    sed -i 's/"\${SHIPYARD_UI_PORT:-8080}:8080"/"'${HTTP_PORT}':8080"/g' docker-compose.yml
    # Also replace literal 8080:8080 if the template was changed or hardcoded elsewhere
    sed -i "s/\"8080:8080\"/\"${HTTP_PORT}:8080\"/g" docker-compose.yml
  fi

  log "Building and starting Shipyard (the first build takes a few minutes)..."
  # A Caddyfile generated while HTTPS was on would keep Caddy on TLS; start clean.
  if [[ $HTTPS -eq 0 && -f "${SHIPYARD_HOST_DATA_DIR:-/var/lib/shipyard}/caddy/Caddyfile" ]]; then
    log "Removing stale generated Caddyfile (HTTPS disabled)"
    rm -f "${SHIPYARD_HOST_DATA_DIR:-/var/lib/shipyard}/caddy/Caddyfile"
  fi
  docker compose up -d --build

  log "Waiting for the API to become healthy..."
  healthy=0
  for _ in $(seq 1 60); do
    if curl -fsS "http://localhost:${HTTP_PORT:-${ui_port_default}}/actuator/health" >/dev/null 2>&1; then
      healthy=1
      break
    fi
    sleep 3
  done
  [[ $healthy -eq 1 ]] || warn "health check still failing; see: docker compose logs -f shipyard"
else
  log "Skipping start (--no-start); run 'docker compose up -d --build' in $SHIPYARD_ROOT when ready"
fi

# ---------------------------------------------------------------- summary
printf '\n\033[1;32mShipyard installed.\033[0m\n'
printf '  Location : %s\n' "$SHIPYARD_ROOT"
printf '  UI       : %s\n' "$PUBLIC_URL"
printf '  Next     : open the URL and create the administrator account\n'
printf '  Logs     : cd %s && docker compose logs -f shipyard\n' "$SHIPYARD_ROOT"
printf '  Stop     : cd %s && docker compose down\n' "$SHIPYARD_ROOT"
backup_dir="$(get_env SHIPYARD_HOST_DATA)"
printf '  Backup   : %s/database/ (shipyard.db + secret.key)\n' "${backup_dir:-/var/lib/shipyard}"
if [[ $HTTPS -eq 0 ]]; then
  printf '  HTTPS    : disabled (plain HTTP only; set SHIPYARD_HTTPS=true in .env to enable)\n'
fi
if [[ $DEV -eq 1 ]]; then
  printf '  Dev      : java 21 + maven + node installed; run scripts/dev.sh\n'
fi
printf '\n'
