#!/usr/bin/env bash
# Rebuilds the vendored @enterprise-ui/react tarball from
# https://github.com/iant89/ENTERPRISE-UI (the package is not on npm yet).
#
#   scripts/update-enterprise-ui.sh [git-ref]
set -euo pipefail
ref="${1:-main}"
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git clone --depth 1 --branch "$ref" https://github.com/iant89/ENTERPRISE-UI.git "$work/enterprise-ui"
cd "$work/enterprise-ui"
npm ci --ignore-scripts --no-audit --no-fund
npm run build:lib
npm pack --pack-destination "$work"

rm -f "$root"/frontend/vendor/enterprise-ui-react-*.tgz
cp "$work"/enterprise-ui-react-*.tgz "$root/frontend/vendor/"
cd "$root/frontend"
npm install --no-audit --no-fund
echo "Updated $(ls vendor/)"
