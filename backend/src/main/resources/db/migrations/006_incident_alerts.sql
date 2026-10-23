-- MANUAL ADDITIVE migration: execute only after explicit confirmation of target MySQL.
-- Creates two new tables and adds nullable alert audit columns. No seed or data rewrite.
CREATE TABLE alert_event (
    id VARCHAR(64) NOT NULL,
    app_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_event_id VARCHAR(128) NOT NULL,
    event_kind VARCHAR(16) NOT NULL,
    incident_id VARCHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    received_at_ms BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_event_source (app_id, source_event_id),
    KEY idx_alert_event_incident (incident_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE alert_incident (
    id VARCHAR(64) NOT NULL,
    app_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    severity VARCHAR(8) NOT NULL,
    incident_type VARCHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    active_fingerprint VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    routing_status VARCHAR(16) NOT NULL,
    routing_error VARCHAR(64),
    chat_mode VARCHAR(24),
    chat_id VARCHAR(128),
    opening_message_id VARCHAR(128),
    first_seen_at_ms BIGINT NOT NULL,
    last_seen_at_ms BIGINT NOT NULL,
    occurrence_count INT NOT NULL,
    ack_deadline_at_ms BIGINT NOT NULL,
    acknowledged_at_ms BIGINT,
    acknowledged_by VARCHAR(128),
    recovered_at_ms BIGINT,
    reminder_count INT NOT NULL,
    next_reminder_at_ms BIGINT,
    escalated_at_ms BIGINT,
    details_json LONGTEXT NOT NULL,
    version INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_active (active_fingerprint),
    KEY idx_alert_incident_app_status (app_id, status, first_seen_at_ms),
    KEY idx_alert_incident_reminder (status, next_reminder_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE notification_delivery
    ADD COLUMN incident_id VARCHAR(64),
    ADD COLUMN alert_phase VARCHAR(32),
    ADD COLUMN chat_id VARCHAR(128),
    ADD COLUMN feishu_message_id VARCHAR(128),
    ADD KEY idx_notification_incident (incident_id, created_at_ms);
