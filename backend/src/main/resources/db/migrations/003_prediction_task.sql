-- MANUAL, ADDITIVE migration. Requires explicit approval before applying to MySQL.
-- Existing project uses schema.sql/data.sql and has no versioned migration runner.
-- This script is intentionally NOT included in Spring SQL initialization.
-- Adds one table only; does not modify, delete or seed top50_prediction.
CREATE TABLE prediction_task (
    task_id VARCHAR(64) NOT NULL,
    idempotency_key_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    dataset_id VARCHAR(128) NOT NULL,
    app_id VARCHAR(32) NOT NULL,
    cutoff_date DATE NOT NULL,
    horizon_days INT NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    dataset_source VARCHAR(16) NULL,
    python_status VARCHAR(16) NULL,
    python_run_id VARCHAR(128) NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(512) NULL,
    dependency_error_code VARCHAR(64) NULL,
    dependency_error_message VARCHAR(512) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    deadline_at_ms BIGINT NOT NULL,
    next_attempt_at_ms BIGINT NOT NULL,
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_prediction_task_idempotency (idempotency_key_hash),
    KEY idx_prediction_task_due (status, next_attempt_at_ms, deadline_at_ms)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
