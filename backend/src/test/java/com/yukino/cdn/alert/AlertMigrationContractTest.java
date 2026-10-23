package com.yukino.cdn.alert;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertMigrationContractTest {
    @Test
    void migrationDefinesIdempotentEventsAndActiveIncidentUniqueness() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migrations/006_incident_alerts.sql"));
        assertTrue(sql.contains("CREATE TABLE alert_event"));
        assertTrue(sql.contains("UNIQUE KEY uk_alert_event_source (app_id, source_event_id)"));
        assertTrue(sql.contains("CREATE TABLE alert_incident"));
        assertTrue(sql.contains("active_fingerprint VARCHAR(64)"));
        assertTrue(sql.contains("UNIQUE KEY uk_alert_active (active_fingerprint)"));
        assertTrue(sql.contains("ADD COLUMN incident_id VARCHAR(64)"));
        assertTrue(sql.contains("ADD COLUMN alert_phase VARCHAR(32)"));
        assertTrue(sql.contains("ADD COLUMN chat_id VARCHAR(128)"));
        assertTrue(sql.contains("ADD COLUMN feishu_message_id VARCHAR(128)"));
    }
}
