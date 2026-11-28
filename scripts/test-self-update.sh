#!/usr/bin/env bash
# Tests scripts/self-update.sh against real git repositories and a fake docker
# CLI (no Docker needed): success, a chosen commit, build failure, unhealthy
# rollback (including the database), local edits, conflicts, and a script that
# changes itself during the update.
#
#   scripts/test-self-update.sh
set -uo pipefail
SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
failures=0

# Prints "checkout image running db-at-last-start state compose-ports" (db lines joined with +) after running one scenario.
scenario() {
  local W; W="$(mktemp -d)"
  export FAKE="$W/fake"; mkdir -p "$FAKE" "$W/bin"
  cat > "$W/bin/docker" <<'D'
#!/usr/bin/env bash
case "$1 $2" in
  "compose build") [[ -n "${FAKE_BUILD_FAIL:-}" ]] && exit 1; echo "$SHIPYARD_COMMIT" > "$FAKE/img_latest" ;;
  "compose stop") rm -f "$FAKE/running" ;;
  "compose up") cp "$FAKE/img_latest" "$FAKE/running"; cp "$PWD/.data/database/db" "$FAKE/db_at_start"; echo changed >> "$PWD/.data/database/db" ;;
  "compose ps") [[ -f "$FAKE/running" ]] && echo cid ;;
  "image inspect") [[ -f "$FAKE/img_${3#shipyard:}" ]] ;;
  "tag shipyard:latest") cp "$FAKE/img_latest" "$FAKE/img_rollback" ;;
  "tag shipyard:rollback") cp "$FAKE/img_rollback" "$FAKE/img_latest" ;;
  "inspect -f") if [[ " ${FAKE_UNHEALTHY:-} " == *" $(cat "$FAKE/running") "* ]]; then echo unhealthy; else echo healthy; fi ;;
esac
D
  chmod +x "$W/bin/docker"
  g() { git -C "$1" -c user.email=t@example.com -c user.name=T "${@:2}"; }
  git init -q -b main "$W/origin"
  cp -r "$SCRIPTS" "$W/origin/scripts"
  printf 'name: shipyard\nports: "8080:8080"\n' > "$W/origin/docker-compose.yml"
  g "$W/origin" add -A; g "$W/origin" commit -qm one
  git clone -q --depth 1 "file://$W/origin" "$W/inst" 2>/dev/null
  g "$W/inst" rev-parse HEAD > "$FAKE/img_latest"; cp "$FAKE/img_latest" "$FAKE/running"
  mkdir -p "$W/inst/.data/database"; echo original > "$W/inst/.data/database/db"
  printf 'SHIPYARD_HOST_DATA=%s\n' "$W/inst/.data" > "$W/inst/.env"
  for m in two three; do echo $m > "$W/origin/$m"; g "$W/origin" add -A; g "$W/origin" commit -qm $m; done
  local target=""
  case "$1" in
    middle) target="$(g "$W/origin" rev-parse HEAD~1)" ;;
    buildfail) export FAKE_BUILD_FAIL=1 ;;
    unhealthy) export FAKE_UNHEALTHY="$(g "$W/origin" rev-parse HEAD)" ;;
    localedit) sed -i 's/8080:8080/9090:8080/' "$W/inst/docker-compose.yml" ;;
    conflict) sed -i 's/8080:8080/9090:8080/' "$W/inst/docker-compose.yml"
              sed -i 's/8080:8080/7070:8080/' "$W/origin/docker-compose.yml"; g "$W/origin" commit -qam four ;;
    selfmod) echo 'echo MODIFIED-SCRIPT-RAN' >> "$W/origin/scripts/self-update.sh"; g "$W/origin" commit -qam selfmod ;;
  esac
  (cd "$W/inst" && SHIPYARD_UPDATE_HEALTH_TIMEOUT=6 PATH="$W/bin:$PATH" bash scripts/self-update.sh $target > "$W/out" 2>&1)
  msg() { [[ -s "$1" ]] && git -C "$W/origin" log -1 --format=%s "$(cat "$1")" || echo none; }
  printf '%s %s %s %s %s %s\n' "$(git -C "$W/origin" log -1 --format=%s "$(g "$W/inst" rev-parse HEAD)")" \
    "$(msg "$FAKE/img_latest")" "$(msg "$FAKE/running")" "$( [[ -f "$FAKE/db_at_start" ]] && paste -sd+ "$FAKE/db_at_start" || echo none)" \
    "$(sed -n 's/.*"state":"\([A-Z_]*\)".*/\1/p' "$W/inst/.data/platform-update/status.json")" \
    "$(sed -n 's/.*ports: "\([0-9]*\):.*/\1/p' "$W/inst/docker-compose.yml")$(grep -q MODIFIED-SCRIPT-RAN "$W/out" && echo ' ran-modified-script')"
  rm -rf "$W"
  unset FAKE_BUILD_FAIL FAKE_UNHEALTHY
}

expect() { # scenario expected
  local got; got="$(scenario "$1")"
  if [[ "$got" == "$2" ]]; then echo "ok   $1"; else echo "FAIL $1: expected '$2', got '$got'"; failures=$((failures + 1)); fi
}

#      scenario   checkout image running db-at-start state       ports
expect latest    "three three three original SUCCEEDED 8080"
expect middle    "two two two original SUCCEEDED 8080"
expect buildfail "one one one none FAILED 8080"
expect unhealthy "one one one original ROLLED_BACK 8080"   # rolled back with the database as it was
expect localedit "three three three original SUCCEEDED 9090"
expect conflict  "one one one none FAILED 9090"
expect selfmod   "selfmod selfmod selfmod original SUCCEEDED 8080"

[[ $failures -eq 0 ]] && echo "All self-update scenarios passed." || { echo "$failures scenario(s) failed."; exit 1; }
