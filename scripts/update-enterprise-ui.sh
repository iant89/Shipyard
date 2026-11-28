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
tarball="$(basename "$(ls "$work"/enterprise-ui-react-*.tgz)")"
cd "$root/frontend"
# The lockfile pins the old tarball's checksum (and file name), so npm would refuse
# the new one with EINTEGRITY. Drop the stale lock entry and install the new tarball;
# npm records its checksum and, if the version changed, the new file name in
# package.json. Every other dependency stays as locked.
node -e '
  const fs = require("fs");
  const lock = JSON.parse(fs.readFileSync("package-lock.json", "utf8"));
  delete lock.packages["node_modules/@enterprise-ui/react"];
  fs.writeFileSync("package-lock.json", JSON.stringify(lock, null, 2) + "\n");
'
npm install --no-audit --no-fund "file:vendor/$tarball"
echo "Updated $(ls vendor/)"
