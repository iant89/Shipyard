-- Persistent volumes: container paths (one per line) that keep their data across
-- deployments, rollbacks and restarts. Each path is backed by a named Docker volume.
ALTER TABLE application_services ADD COLUMN volumes TEXT NOT NULL DEFAULT '';
