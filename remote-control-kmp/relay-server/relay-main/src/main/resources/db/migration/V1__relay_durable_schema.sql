CREATE TABLE relay_identities (
    relay_device_id TEXT PRIMARY KEY,
    device_id TEXT UNIQUE,
    algorithm TEXT,
    public_key BYTEA,
    key_generation BIGINT,
    security_level TEXT,
    status TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    token_expires_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    record_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE relay_key_generations (
    device_id TEXT NOT NULL,
    generation BIGINT NOT NULL,
    algorithm TEXT NOT NULL,
    public_key BYTEA NOT NULL,
    first_observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (device_id, generation)
);

CREATE TABLE relay_sessions (
    session_id TEXT PRIMARY KEY,
    source_relay_device_id TEXT NOT NULL,
    target_relay_device_id TEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    status TEXT NOT NULL,
    record_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX relay_sessions_expiry_idx ON relay_sessions (expires_at);
CREATE INDEX relay_sessions_source_idx ON relay_sessions (source_relay_device_id);
CREATE INDEX relay_sessions_target_idx ON relay_sessions (target_relay_device_id);

CREATE TABLE relay_revocations (
    device_id TEXT PRIMARY KEY,
    revoked_at BIGINT NOT NULL,
    reason TEXT NOT NULL
);

CREATE TABLE relay_audit_events (
    id BIGSERIAL PRIMARY KEY,
    event_type TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    device_count INTEGER NOT NULL DEFAULT 0,
    session_count INTEGER NOT NULL DEFAULT 0
);
