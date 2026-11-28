#!/usr/bin/env bash
# Moves an existing Shipyard install's application network onto a fixed subnet
# (SHIPYARD_DOCKER_SUBNET in .env, default 172.30.0.0/22), so applications can trust
# their proxy (Caddy) by address range, e.g. CLAWROUTER_TRUSTED_PROXIES=172.30.0.0/22.
#
#   cd /path/to/shipyard && sudo scripts/pin-network-subnet.sh
#
# Applications and the Shipyard UI are unreachable for a minute while the network is
# recreated. Application containers keep running (and keep their volumes); they are
# reconnected to the new network. Containers get new IP addresses.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=network.sh
source scripts/network.sh

log()  { printf '\n\033[1;32m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

[[ -f docker-compose.yml ]] && grep -q '^name: shipyard' docker-compose.yml || die "run this from the Shipyard checkout"
command -v docker >/dev/null || die "docker is not installed"

wanted="$(shipyard_wanted_subnet)"
current="$(shipyard_current_subnet)"
if [[ "$current" == "$wanted" ]]; then
  log "The $SHIPYARD_NETWORK network already uses $wanted; nothing to do."
  exit 0
fi
if [[ -z "$current" ]]; then
  shipyard_ensure_network
  exit 0
fi

# Fail early if the wanted range is taken by another network.
if docker network ls -q | xargs -r docker network inspect --format '{{.Name}} {{range .IPAM.Config}}{{.Subnet}} {{end}}' \
     | awk -v n="$SHIPYARD_NETWORK" -v s="$wanted" '$1 != n { for (i = 2; i <= NF; i++) if ($i == s) found = 1 } END { exit !found }'; then
  die "another Docker network already uses $wanted; set SHIPYARD_DOCKER_SUBNET in .env to a free range"
fi

printf 'Moving the %s network from %s to %s.\n' "$SHIPYARD_NETWORK" "$current" "$wanted"
printf 'Applications and the UI are unreachable for about a minute. Continue? [y/N] '
read -r answer
[[ "$answer" == [yY]* ]] || die "cancelled"

log "Stopping and removing the Shipyard containers (data is kept)"
docker compose rm --stop --force

# Everything else on the network: application containers, running or stopped.
mapfile -t attached < <(docker ps -a --filter "network=$SHIPYARD_NETWORK" --format '{{.Names}}')
log "Disconnecting ${#attached[@]} application container(s)"
for c in "${attached[@]}"; do docker network disconnect --force "$SHIPYARD_NETWORK" "$c" >/dev/null 2>&1 || true; done

log "Recreating the $SHIPYARD_NETWORK network with $wanted"
docker network rm "$SHIPYARD_NETWORK" >/dev/null
if ! shipyard_ensure_network; then
  # Put the old range back so the install keeps working.
  docker network create --driver bridge --subnet "$current" --label dev.shipyard.managed=true "$SHIPYARD_NETWORK" >/dev/null
  for c in "${attached[@]}"; do docker network connect "$SHIPYARD_NETWORK" "$c" >/dev/null 2>&1 || true; done
  docker compose up -d
  die "could not create $wanted; restored $current"
fi

log "Reconnecting application containers"
for c in "${attached[@]}"; do
  docker network connect "$SHIPYARD_NETWORK" "$c" || printf 'warning: could not reconnect %s\n' "$c" >&2
done

log "Starting Shipyard"
docker compose up -d
log "Done. The $SHIPYARD_NETWORK network now uses $wanted."
