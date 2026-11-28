#!/usr/bin/env bash
# Updates a docker compose install of Shipyard to a newer commit of its branch.
#
#   scripts/self-update.sh [commit]     # default: the latest commit on the checked-out branch
#
# Shipyard runs this in a short-lived helper container when an administrator
# clicks Update (Settings → Shipyard updates); it can also be run on the host.
#
# Steps: fetch, fast-forward the checkout (local edits such as port changes are
# kept), build the new image while the old version keeps running, back up the
# database, restart, and wait for the health check. If the build fails nothing
# is restarted; if the new version is not healthy, the previous image, database
# and checkout are restored.
#
# Progress goes to <state dir>/update.log and the outcome to <state dir>/status.json,
# where <state dir> is $SHIPYARD_UPDATE_STATE_DIR or <host data dir>/platform-update.
set -euo pipefail

# Run from a copy: the checkout (and this file) changes underneath us.
if [[ -z "${SHIPYARD_SELF_UPDATE_COPY:-}" ]]; then
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  copy="$(mktemp "${TMPDIR:-/tmp}/shipyard-self-update.XXXXXX")"
  cp "${BASH_SOURCE[0]}" "$copy"
  SHIPYARD_SELF_UPDATE_COPY="$copy" SHIPYARD_ROOT="$root" exec bash "$copy" "$@"
fi
trap 'rm -f "$SHIPYARD_SELF_UPDATE_COPY"' EXIT

ROOT="${SHIPYARD_ROOT:?}"
cd "$ROOT"

env_value() { # value of KEY in .env, if set
  [[ -f .env ]] && sed -n "s/^$1=//p" .env | tail -n1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/" || true
}
HOST_DATA="$(env_value SHIPYARD_HOST_DATA)"
HOST_DATA="${HOST_DATA:-/var/lib/shipyard}"
DATA_DIR="${SHIPYARD_UPDATE_DATA_DIR:-$HOST_DATA}"           # the data dir as seen by this script
STATE_DIR="${SHIPYARD_UPDATE_STATE_DIR:-$DATA_DIR/platform-update}"
HEALTH_TIMEOUT="${SHIPYARD_UPDATE_HEALTH_TIMEOUT:-300}"       # seconds
IMAGE="shipyard:latest"
ROLLBACK_IMAGE="shipyard:rollback"

mkdir -p "$STATE_DIR"
LOG="$STATE_DIR/update.log"
: > "$LOG"

git() { command git -c safe.directory="$ROOT" "$@"; }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { printf '[%s] %s\n' "$(now)" "$*" | tee -a "$LOG"; }
json() { local s=${1//\\/\\\\}; s=${s//\"/\\\"}; s=${s//$'\n'/ }; printf '"%s"' "$s"; }

STARTED="$(now)"
FROM=""
TO=""
status() { # state message [finished]
  local tmp="$STATE_DIR/.status.json.$$"
  {
    printf '{"state":%s,"fromSha":%s,"toSha":%s,"message":%s,"startedAt":%s,"finishedAt":%s}\n' \
      "$(json "$1")" "$(json "$FROM")" "$(json "$TO")" "$(json "$2")" "$(json "$STARTED")" \
      "$([[ -n "${3:-}" ]] && json "$(now)" || echo null)"
  } > "$tmp"
  mv "$tmp" "$STATE_DIR/status.json"
}
finish() { # state message
  log "$2"
  status "$1" "$2" done
  exit "$([[ $1 == SUCCEEDED ]] && echo 0 || echo 1)"
}

container() { docker compose ps -q shipyard 2>/dev/null | head -n1; }
wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) id health
  while (( SECONDS < deadline )); do
    id="$(container)"
    if [[ -n "$id" ]]; then
      health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || true)"
      case "$health" in
        healthy) return 0 ;;
        unhealthy|exited|dead) log "Container is $health"; return 1 ;;
      esac
    fi
    sleep 3
  done
  log "Not healthy after ${HEALTH_TIMEOUT}s"
  return 1
}

status RUNNING "Starting update"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[[ "$BRANCH" != HEAD ]] || finish FAILED "The checkout in $ROOT is not on a branch; check out a branch to enable updates."
FROM="$(git rev-parse HEAD)"

log "Fetching origin/$BRANCH"
git fetch --quiet origin "$BRANCH" >>"$LOG" 2>&1 || finish FAILED "Could not fetch $BRANCH from origin; see the log."
TO="$(git rev-parse --verify --quiet "${1:-FETCH_HEAD}^{commit}" || true)"
[[ -n "$TO" ]] || { TO="${1:-}"; finish FAILED "Commit ${1:-FETCH_HEAD} was not found on origin/$BRANCH."; }
git merge-base --is-ancestor "$TO" FETCH_HEAD || finish FAILED "Commit ${TO:0:7} is not on origin/$BRANCH."
[[ "$TO" != "$FROM" ]] || finish SUCCEEDED "Already running ${TO:0:7}."
git merge-base --is-ancestor "$FROM" "$TO" \
  || finish FAILED "${TO:0:7} does not follow the installed ${FROM:0:7} (history was rewritten or the checkout has local commits); update manually."

status RUNNING "Updating ${FROM:0:7} → ${TO:0:7}"
log "Updating ${FROM:0:7} → ${TO:0:7} ($(git rev-list --count "$FROM..$TO") commits)"

# Fast-forward, keeping local edits (e.g. ports set by the installer).
if ! git merge --quiet --ff-only --autostash "$TO" >>"$LOG" 2>&1 || [[ -n "$(git diff --name-only --diff-filter=U)" ]]; then
  conflicts="$(git diff --name-only --diff-filter=U | paste -sd' ' -)"
  git reset --quiet --hard "$FROM" >>"$LOG" 2>&1 || true
  git stash pop --quiet >>"$LOG" 2>&1 || true
  finish FAILED "Local changes in $ROOT conflict with the update${conflicts:+ ($conflicts)}; nothing was changed."
fi
revert_checkout() { git reset --quiet --keep "$FROM" >>"$LOG" 2>&1 || log "Could not move the checkout back to ${FROM:0:7}"; }

# Build first: if it fails, the running version is untouched.
if docker image inspect "$IMAGE" >/dev/null 2>&1; then docker tag "$IMAGE" "$ROLLBACK_IMAGE"; fi
log "Building the new image (this takes a few minutes)"
if ! SHIPYARD_COMMIT="$TO" docker compose build shipyard >>"$LOG" 2>&1; then
  revert_checkout
  finish FAILED "The build of ${TO:0:7} failed; Shipyard ${FROM:0:7} is still running. See the log."
fi

log "Stopping Shipyard and backing up the database"
docker compose stop shipyard >>"$LOG" 2>&1 || true
BACKUP="$STATE_DIR/backup"
rm -rf "$BACKUP" && mkdir -p "$BACKUP"
if [[ -d "$DATA_DIR/database" ]]; then cp -a "$DATA_DIR/database" "$BACKUP/database"; fi
printf '%s\n' "$FROM" > "$BACKUP/commit"

status RUNNING "Starting Shipyard ${TO:0:7}"
log "Starting Shipyard ${TO:0:7}"
SHIPYARD_COMMIT="$TO" docker compose up -d >>"$LOG" 2>&1 || log "docker compose up reported an error"
if wait_healthy; then
  finish SUCCEEDED "Updated to ${TO:0:7}."
fi

log "Rolling back to ${FROM:0:7}"
status RUNNING "Rolling back to ${FROM:0:7}"
docker compose stop shipyard >>"$LOG" 2>&1 || true
if [[ -d "$BACKUP/database" ]]; then rm -rf "$DATA_DIR/database" && cp -a "$BACKUP/database" "$DATA_DIR/database"; fi
if docker image inspect "$ROLLBACK_IMAGE" >/dev/null 2>&1; then docker tag "$ROLLBACK_IMAGE" "$IMAGE"; fi
revert_checkout
SHIPYARD_COMMIT="$FROM" docker compose up -d >>"$LOG" 2>&1 || true
if wait_healthy; then
  finish ROLLED_BACK "${TO:0:7} did not become healthy; rolled back to ${FROM:0:7}. See the log."
fi
finish FAILED "${TO:0:7} did not become healthy and the rollback to ${FROM:0:7} is not healthy either. Check: docker compose logs shipyard"
