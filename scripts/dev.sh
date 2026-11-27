#!/usr/bin/env bash
# Local development: API on :8080, Vite dev server on :5173 (proxies /api).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
export SHIPYARD_DATA_DIR="${SHIPYARD_DATA_DIR:-$root/data}"
export SHIPYARD_ALLOW_LOCAL_REPOSITORIES="${SHIPYARD_ALLOW_LOCAL_REPOSITORIES:-true}"
(cd "$root/backend" && mvn -q spring-boot:run) &
api=$!
trap 'kill $api' EXIT
cd "$root/frontend" && npm install --no-audit --no-fund && npm run dev
