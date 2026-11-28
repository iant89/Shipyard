# shellcheck shell=bash
# Shared by install-ubuntu.sh, self-update.sh and pin-network-subnet.sh (source it from the
# Shipyard checkout). The application network is created outside docker compose (it is
# `external` in docker-compose.yml) so compose never tries to recreate it while
# application containers are attached.

SHIPYARD_NETWORK="${SHIPYARD_NETWORK:-shipyard}"
SHIPYARD_DEFAULT_SUBNET="172.30.0.0/22"

# Subnet from .env (SHIPYARD_DOCKER_SUBNET), else the default. Run from the checkout.
shipyard_wanted_subnet() {
  local v=""
  [[ -f .env ]] && v="$(sed -n 's/^SHIPYARD_DOCKER_SUBNET=//p' .env | tail -n1 | sed -e 's/[[:space:]]*#.*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/" | tr -d '[:space:]')"
  printf '%s\n' "${v:-$SHIPYARD_DEFAULT_SUBNET}"
}

# Current subnet of the network (empty if it does not exist).
shipyard_current_subnet() {
  docker network inspect "$SHIPYARD_NETWORK" --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' 2>/dev/null | awk '{print $1}'
}

# Creates the network with the wanted subnet unless it exists. Never changes an existing
# network: an install whose network predates pinning keeps it until
# scripts/pin-network-subnet.sh moves it.
shipyard_ensure_network() {
  local wanted current
  wanted="$(shipyard_wanted_subnet)"
  current="$(shipyard_current_subnet)"
  if docker network inspect "$SHIPYARD_NETWORK" >/dev/null 2>&1; then
    if [[ -n "$current" && "$current" != "$wanted" ]]; then
      printf 'note: the %s network uses %s, not %s; run scripts/pin-network-subnet.sh to move it.\n' "$SHIPYARD_NETWORK" "$current" "$wanted" >&2
    fi
    return 0
  fi
  if ! docker network create --driver bridge --subnet "$wanted" --label dev.shipyard.managed=true "$SHIPYARD_NETWORK" >/dev/null; then
    printf 'error: could not create the %s network with subnet %s (it may overlap another network);\n' "$SHIPYARD_NETWORK" "$wanted" >&2
    printf '       set SHIPYARD_DOCKER_SUBNET in .env to a free range and try again.\n' >&2
    return 1
  fi
  printf 'Created the %s network (%s)\n' "$SHIPYARD_NETWORK" "$wanted"
}
