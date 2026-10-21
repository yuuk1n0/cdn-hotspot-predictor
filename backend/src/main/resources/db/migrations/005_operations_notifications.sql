-- MANUAL ADDITIVE migration: execute only after explicit confirmation of target MySQL.
-- Creates three new tables. No ALTER, DROP, DELETE, seed replay, or automatic startup execution.
CREATE TABLE app_config (
    app_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    settings_json LONGTEXT NOT NULL,
    version INT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE compression_record (
    id VARCHAR(64) NOT NULL,
    app_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    file_key LONGTEXT NOT NULL,
    file_key_hash CHAR(64) NOT NULL,
    evidence_source VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL,
    record_json LONGTEXT NOT NULL,
    version INT NOT NULL,
    idempotency_key_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_compression_identity (app_id,file_key_hash,evidence_source),
    UNIQUE KEY uk_compression_idempotency (idempotency_key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_delivery (
    id VARCHAR(64) NOT NULL,
    app_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    run_id VARCHAR(128),
    type VARCHAR(16) NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    evidence_source VARCHAR(8) NOT NULL,
    destination_id VARCHAR(128) NOT NULL,
    business_key_hash CHAR(64) NOT NULL,
    idempotency_key_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    record_json LONGTEXT NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at_ms BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    sent_at_ms BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_business (business_key_hash),
    UNIQUE KEY uk_notification_idempotency (idempotency_key_hash),
    KEY idx_notification_due (status,next_attempt_at_ms),
    KEY idx_notification_app (app_id,created_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
