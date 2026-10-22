-- MANUAL ADDITIVE migration. Apply only after inspecting information_schema and taking a backup.
-- This script is not executed by Spring startup and does not touch legacy or prediction tables.
-- Dataset versions coexist; production reads must bind one READY dataset_id explicitly.

CREATE TABLE data_import_task (
    import_task_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    idempotency_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_dataset_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    app_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    source_type VARCHAR(32) NOT NULL,
    dataset_source VARCHAR(16) NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    processed_rows BIGINT NOT NULL DEFAULT 0,
    total_rows BIGINT NOT NULL DEFAULT 0,
    valid_rows BIGINT NOT NULL DEFAULT 0,
    invalid_rows BIGINT NOT NULL DEFAULT 0,
    duplicate_rows BIGINT NOT NULL DEFAULT 0,
    aggregated_rows BIGINT NOT NULL DEFAULT 0,
    quality_report_json LONGTEXT NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1024) NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    completed_at_ms BIGINT NULL,
    PRIMARY KEY (import_task_id),
    UNIQUE KEY uk_data_import_task_idempotency (idempotency_key_hash),
    KEY idx_data_import_task_status (status, updated_at_ms),
    KEY idx_data_import_task_dataset (target_dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE imported_dataset (
    dataset_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    fingerprint_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    app_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    dataset_type VARCHAR(32) NOT NULL,
    dataset_source VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    source_unit VARCHAR(16) NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    identity_mapping_version VARCHAR(64) NULL,
    total_rows BIGINT NOT NULL DEFAULT 0,
    valid_rows BIGINT NOT NULL DEFAULT 0,
    invalid_rows BIGINT NOT NULL DEFAULT 0,
    duplicate_rows BIGINT NOT NULL DEFAULT 0,
    aggregated_rows BIGINT NOT NULL DEFAULT 0,
    metadata_json LONGTEXT NOT NULL,
    quality_report_json LONGTEXT NULL,
    created_at_ms BIGINT NOT NULL,
    ready_at_ms BIGINT NULL,
    PRIMARY KEY (dataset_id),
    UNIQUE KEY uk_imported_dataset_fingerprint (fingerprint_hash),
    KEY idx_imported_dataset_lookup (app_id, dataset_type, dataset_source, status, created_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE file_daily_observation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dataset_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    app_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    observed_date DATE NOT NULL,
    file_key LONGTEXT NOT NULL,
    file_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    traffic_bytes DECIMAL(65,0) NOT NULL,
    parse_status VARCHAR(16) NOT NULL,
    source_identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_row_count BIGINT NOT NULL DEFAULT 1,
    source_identity_count BIGINT NOT NULL DEFAULT 1,
    aggregation_rule VARCHAR(32) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_daily_identity (dataset_id, app_id, observed_date, file_key_hash),
    KEY idx_file_daily_prediction (dataset_id, app_id, observed_date),
    CONSTRAINT fk_file_daily_dataset FOREIGN KEY (dataset_id)
        REFERENCES imported_dataset (dataset_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE app_daily_total (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dataset_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    app_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    observed_date DATE NOT NULL,
    raw_traffic_value DECIMAL(38,18) NOT NULL,
    total_traffic_bytes DECIMAL(65,9) NOT NULL,
    source_unit VARCHAR(16) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_daily_total_identity (dataset_id, app_id, observed_date),
    KEY idx_app_daily_total_window (dataset_id, app_id, observed_date),
    CONSTRAINT fk_app_daily_total_dataset FOREIGN KEY (dataset_id)
        REFERENCES imported_dataset (dataset_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
