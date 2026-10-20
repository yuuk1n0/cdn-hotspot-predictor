-- MANUAL, ADDITIVE migration. Apply only after explicit approval, after migration 003.
-- Never automatically executed on application startup. No seeds/deletes/ALTERs to existing tables.
CREATE TABLE prediction_run (
    run_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    prediction_source VARCHAR(16) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_prediction_run_task (task_id),
    CONSTRAINT fk_prediction_run_task FOREIGN KEY (task_id) REFERENCES prediction_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
