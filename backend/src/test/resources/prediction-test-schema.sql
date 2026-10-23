-- Isolated H2 fixture only. Does not connect to or migrate the business MySQL database.
CREATE TABLE prediction_task (
    task_id VARCHAR(64) PRIMARY KEY,
    idempotency_key_hash CHAR(64) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    dataset_id VARCHAR(128) NOT NULL,
    app_id VARCHAR(32) NOT NULL,
    cutoff_date DATE NOT NULL,
    horizon_days INT NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    dataset_source VARCHAR(16),
    python_status VARCHAR(16),
    python_run_id VARCHAR(128),
    error_code VARCHAR(64),
    error_message VARCHAR(512),
    dependency_error_code VARCHAR(64),
    dependency_error_message VARCHAR(512),
    attempt_count INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    deadline_at_ms BIGINT NOT NULL,
    next_attempt_at_ms BIGINT NOT NULL
);

CREATE TABLE prediction_run (
    run_id VARCHAR(128) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL UNIQUE REFERENCES prediction_task(task_id),
    prediction_source VARCHAR(16) NOT NULL,
    snapshot_json CLOB NOT NULL,
    created_at_ms BIGINT NOT NULL
);
