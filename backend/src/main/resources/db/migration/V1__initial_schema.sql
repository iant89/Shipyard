-- Shipyard initial schema (SQLite).
-- Timestamps are ISO-8601 UTC strings. IDs are text (UUIDs) unless noted.

CREATE TABLE users (
    id            TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'ADMINISTRATOR',
    created_at    TEXT NOT NULL
);

CREATE TABLE applications (
    id                 TEXT PRIMARY KEY,
    slug               TEXT NOT NULL UNIQUE,
    name               TEXT NOT NULL,
    description        TEXT,
    status             TEXT NOT NULL DEFAULT 'CREATED',
    current_deployment_id TEXT,
    auto_deploy        INTEGER NOT NULL DEFAULT 0,
    auto_rollback      INTEGER NOT NULL DEFAULT 1,
    cancel_stale_builds INTEGER NOT NULL DEFAULT 1,
    build_concurrency  INTEGER NOT NULL DEFAULT 1,
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL
);

-- MVP: exactly one service ("web") per application. The deployment engine is
-- service-aware so multi-service applications can be added later.
CREATE TABLE application_services (
    id                 TEXT PRIMARY KEY,
    application_id     TEXT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    name               TEXT NOT NULL,
    -- Detection / build configuration (NULL = use detection)
    project_type       TEXT,             -- STATIC | SERVER
    framework          TEXT,
    build_strategy     TEXT,             -- DOCKERFILE | STATIC | NODE | ...
    root_directory     TEXT NOT NULL DEFAULT '.',
    install_command    TEXT,
    build_command      TEXT,
    test_command       TEXT,
    start_command      TEXT,
    output_directory   TEXT,
    package_manager    TEXT,
    runtime_image      TEXT,
    internal_port      INTEGER,
    -- Health check
    health_check_type  TEXT NOT NULL DEFAULT 'HTTP',   -- HTTP | TCP | PROCESS | NONE
    health_check_path  TEXT NOT NULL DEFAULT '/',
    health_check_timeout_seconds INTEGER NOT NULL DEFAULT 60,
    -- Resource limits (mandatory, with defaults)
    cpu_limit          REAL NOT NULL DEFAULT 1.0,
    memory_limit_mb    INTEGER NOT NULL DEFAULT 512,
    restart_policy     TEXT NOT NULL DEFAULT 'unless-stopped',
    restart_count      INTEGER NOT NULL DEFAULT 0,
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL,
    UNIQUE (application_id, name)
);

CREATE TABLE repositories (
    id              TEXT PRIMARY KEY,
    application_id  TEXT NOT NULL UNIQUE REFERENCES applications(id) ON DELETE CASCADE,
    provider        TEXT NOT NULL DEFAULT 'GITHUB',
    url             TEXT NOT NULL,
    branch          TEXT NOT NULL DEFAULT 'main',
    -- Encrypted personal access token for private repositories (nullable)
    access_token_enc TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE TABLE deployments (
    id              TEXT PRIMARY KEY,
    application_id  TEXT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    service_id      TEXT NOT NULL REFERENCES application_services(id) ON DELETE CASCADE,
    number          INTEGER NOT NULL,
    commit_sha      TEXT,
    commit_message  TEXT,
    commit_author   TEXT,
    branch          TEXT NOT NULL,
    source          TEXT NOT NULL,       -- MANUAL | WEBHOOK | API | SCHEDULED | SYSTEM
    status          TEXT NOT NULL,       -- see DeploymentStatus
    build_status    TEXT NOT NULL DEFAULT 'PENDING',
    runtime_status  TEXT NOT NULL DEFAULT 'PENDING',
    project_type    TEXT,
    framework       TEXT,
    image_tag       TEXT,
    artifact_path   TEXT,
    container_name  TEXT,
    internal_port   INTEGER,
    rollback_of     TEXT,                -- set when this deployment is a rollback re-activation
    triggered_by    TEXT,
    created_at      TEXT NOT NULL,
    started_at      TEXT,
    finished_at     TEXT,
    error           TEXT,
    UNIQUE (application_id, number)
);
CREATE INDEX idx_deployments_app ON deployments(application_id, number DESC);

CREATE TABLE deployment_queue (
    id              TEXT PRIMARY KEY,
    application_id  TEXT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    deployment_id   TEXT NOT NULL REFERENCES deployments(id) ON DELETE CASCADE,
    kind            TEXT NOT NULL DEFAULT 'DEPLOY',  -- DEPLOY | ROLLBACK
    source          TEXT NOT NULL,
    commit_sha      TEXT,
    branch          TEXT NOT NULL,
    priority        INTEGER NOT NULL DEFAULT 0,
    status          TEXT NOT NULL,       -- QUEUED | RUNNING | COMPLETED | FAILED | CANCELLED
    created_at      TEXT NOT NULL,
    started_at      TEXT,
    finished_at     TEXT
);
CREATE INDEX idx_queue_status ON deployment_queue(status, priority DESC, created_at);

CREATE TABLE environment_variables (
    id              TEXT PRIMARY KEY,
    application_id  TEXT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    value_enc       TEXT NOT NULL,       -- AES-GCM encrypted
    secret          INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    UNIQUE (application_id, name)
);

CREATE TABLE domains (
    id              TEXT PRIMARY KEY,
    application_id  TEXT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    hostname        TEXT NOT NULL UNIQUE,
    tls_enabled     INTEGER NOT NULL DEFAULT 1,
    is_primary      INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL
);

CREATE TABLE webhooks (
    id              TEXT PRIMARY KEY,
    application_id  TEXT NOT NULL UNIQUE REFERENCES applications(id) ON DELETE CASCADE,
    provider        TEXT NOT NULL DEFAULT 'GITHUB',
    endpoint_id     TEXT NOT NULL UNIQUE,  -- random, unpredictable path segment
    secret_enc      TEXT NOT NULL,
    deploy_on_push  INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE TABLE webhook_events (
    id              TEXT PRIMARY KEY,
    application_id  TEXT REFERENCES applications(id) ON DELETE CASCADE,
    provider        TEXT NOT NULL,
    event_type      TEXT NOT NULL,
    delivery_id     TEXT,
    repository      TEXT,
    branch          TEXT,
    commit_sha      TEXT,
    previous_sha    TEXT,
    author          TEXT,
    commit_message  TEXT,
    received_at     TEXT NOT NULL,
    status          TEXT NOT NULL,       -- RECEIVED | IGNORED | QUEUED | PROCESSING | DEPLOYED | FAILED
    deployment_id   TEXT,
    error           TEXT,
    test            INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX idx_webhook_delivery ON webhook_events(provider, delivery_id) WHERE delivery_id IS NOT NULL;
CREATE INDEX idx_webhook_events_app ON webhook_events(application_id, received_at DESC);

CREATE TABLE build_logs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    deployment_id   TEXT NOT NULL REFERENCES deployments(id) ON DELETE CASCADE,
    stream          TEXT NOT NULL DEFAULT 'stdout',
    line            TEXT NOT NULL,
    created_at      TEXT NOT NULL
);
CREATE INDEX idx_build_logs_dep ON build_logs(deployment_id, id);

CREATE TABLE deployment_logs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    deployment_id   TEXT NOT NULL REFERENCES deployments(id) ON DELETE CASCADE,
    level           TEXT NOT NULL DEFAULT 'INFO',
    line            TEXT NOT NULL,
    created_at      TEXT NOT NULL
);
CREATE INDEX idx_deployment_logs_dep ON deployment_logs(deployment_id, id);

CREATE TABLE audit_events (
    id              TEXT PRIMARY KEY,
    user_id         TEXT,
    action          TEXT NOT NULL,
    resource_type   TEXT NOT NULL,
    resource_id     TEXT,
    timestamp       TEXT NOT NULL,
    metadata        TEXT
);
CREATE INDEX idx_audit_time ON audit_events(timestamp DESC);

CREATE TABLE platform_settings (
    key             TEXT PRIMARY KEY,
    value           TEXT NOT NULL
);
