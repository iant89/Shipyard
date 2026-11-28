-- Poll the repository every 90 seconds and deploy new commits on the branch (no webhook needed).
ALTER TABLE applications ADD COLUMN auto_update INTEGER NOT NULL DEFAULT 0;
