package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.yukino.cdn.operations.OperationsSupport.problem;

@Repository
public class AlertIncidentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AlertIncidentRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<ObjectNode> event(String appId, String sourceEventId) {
        var rows = jdbc.query("""
                SELECT incident_id,event_kind FROM alert_event
                WHERE app_id=:app AND source_event_id=:source
                """, Map.of("app", appId, "source", sourceEventId),
                (rs, i) -> mapper.createObjectNode()
                        .put("incidentId", rs.getString("incident_id"))
                        .put("eventKind", rs.getString("event_kind")));
        return rows.stream().findFirst();
    }

    public Optional<ObjectNode> active(String fingerprint) {
        var rows = jdbc.query("SELECT * FROM alert_incident WHERE active_fingerprint=:fingerprint",
                Map.of("fingerprint", fingerprint), this::read);
        return rows.stream().findFirst();
    }

    public Optional<ObjectNode> find(String incidentId) {
        var rows = jdbc.query("SELECT * FROM alert_incident WHERE id=:id",
                Map.of("id", incidentId), this::read);
        return rows.stream().findFirst();
    }

    public ObjectNode get(String incidentId) {
        return find(incidentId)
                .orElseThrow(() -> problem(404, "INCIDENT_NOT_FOUND", "事故不存在。"));
    }

    public void insert(ObjectNode incident) {
        jdbc.update("""
                INSERT INTO alert_incident (
                id,app_id,severity,incident_type,object_key,fingerprint,active_fingerprint,status,
                routing_status,routing_error,chat_mode,chat_id,opening_message_id,first_seen_at_ms,
                last_seen_at_ms,occurrence_count,ack_deadline_at_ms,acknowledged_at_ms,acknowledged_by,
                recovered_at_ms,reminder_count,next_reminder_at_ms,escalated_at_ms,details_json,version)
                VALUES (:id,:app,:severity,:type,:object,:fingerprint,:active,:status,:routingStatus,
                :routingError,:chatMode,:chatId,:messageId,:firstSeen,:lastSeen,:occurrences,:ackDeadline,
                :acknowledgedAt,:acknowledgedBy,:recoveredAt,:reminderCount,:nextReminder,:escalatedAt,
                :details,:version)
                """, parameters(incident));
    }

    public ObjectNode update(ObjectNode incident, int expectedVersion) {
        ObjectNode saved = incident.deepCopy().put("version", expectedVersion + 1);
        var p = parameters(saved).addValue("expectedVersion", expectedVersion);
        int updated = jdbc.update("""
                UPDATE alert_incident SET severity=:severity,status=:status,routing_status=:routingStatus,
                routing_error=:routingError,chat_mode=:chatMode,chat_id=:chatId,opening_message_id=:messageId,
                active_fingerprint=:active,last_seen_at_ms=:lastSeen,occurrence_count=:occurrences,
                acknowledged_at_ms=:acknowledgedAt,acknowledged_by=:acknowledgedBy,recovered_at_ms=:recoveredAt,
                reminder_count=:reminderCount,next_reminder_at_ms=:nextReminder,escalated_at_ms=:escalatedAt,
                details_json=:details,version=:version
                WHERE id=:id AND version=:expectedVersion
                """, p);
        if (updated != 1) throw problem(409, "INCIDENT_VERSION_CONFLICT", "事故已变化，请刷新后重试。");
        return get(saved.path("incidentId").asText());
    }

    public List<ObjectNode> list(String appId, String status, String severity, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM alert_incident
                WHERE app_id=:app
                  AND (:status IS NULL OR status=:status)
                  AND (:severity IS NULL OR severity=:severity)
                ORDER BY first_seen_at_ms DESC,id DESC LIMIT :limit OFFSET :offset
                """, filters(appId, status, severity)
                .addValue("limit", limit).addValue("offset", offset), this::read);
    }

    public int count(String appId, String status, String severity) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM alert_incident
                WHERE app_id=:app
                  AND (:status IS NULL OR status=:status)
                  AND (:severity IS NULL OR severity=:severity)
                """, filters(appId, status, severity), Integer.class);
    }

    public List<ObjectNode> dueReminders(Instant now) {
        return jdbc.query("""
                SELECT * FROM alert_incident
                WHERE status='OPEN' AND acknowledged_at_ms IS NULL
                  AND next_reminder_at_ms IS NOT NULL AND next_reminder_at_ms<=:now
                ORDER BY next_reminder_at_ms,id LIMIT 100
                """, Map.of("now", now.toEpochMilli()), this::read);
    }

    public boolean claimReminder(String incidentId, int expectedVersion, Instant next, Instant now) {
        return jdbc.update("""
                UPDATE alert_incident
                SET reminder_count=reminder_count+1,
                    escalated_at_ms=COALESCE(escalated_at_ms,:now),
                    next_reminder_at_ms=:next,
                    version=version+1
                WHERE id=:id AND version=:version AND status='OPEN' AND acknowledged_at_ms IS NULL
                """, new MapSqlParameterSource().addValue("id", incidentId)
                .addValue("version", expectedVersion).addValue("next", next.toEpochMilli())
                .addValue("now", now.toEpochMilli())) == 1;
    }

    public void stopReminder(String incidentId, int expectedVersion) {
        jdbc.update("""
                UPDATE alert_incident SET next_reminder_at_ms=NULL,version=version+1
                WHERE id=:id AND version=:version AND status='OPEN'
                """, Map.of("id", incidentId, "version", expectedVersion));
    }

    public boolean insertEvent(String id, AlertEvent event, String incidentId, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO alert_event (id,app_id,source_event_id,event_kind,incident_id,payload_json,received_at_ms)
                    VALUES (:id,:app,:source,:kind,:incident,:payload,:now)
                    """, new MapSqlParameterSource()
                    .addValue("id", id).addValue("app", event.appId()).addValue("source", event.sourceEventId())
                    .addValue("kind", event.eventKind()).addValue("incident", incidentId)
                    .addValue("payload", event.raw().toString()).addValue("now", now.toEpochMilli()));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private MapSqlParameterSource parameters(ObjectNode row) {
        return new MapSqlParameterSource()
                .addValue("id", row.path("incidentId").asText())
                .addValue("app", row.path("appId").asText())
                .addValue("severity", row.path("severity").asText())
                .addValue("type", row.path("incidentType").asText())
                .addValue("object", row.path("objectKey").asText())
                .addValue("fingerprint", row.path("fingerprint").asText())
                .addValue("active", textOrNull(row, "activeFingerprint"))
                .addValue("status", row.path("status").asText())
                .addValue("routingStatus", row.path("routingStatus").asText())
                .addValue("routingError", textOrNull(row, "routingError"))
                .addValue("chatMode", textOrNull(row, "chatMode"))
                .addValue("chatId", textOrNull(row, "chatId"))
                .addValue("messageId", textOrNull(row, "openingMessageId"))
                .addValue("firstSeen", instant(row, "firstSeenAt"))
                .addValue("lastSeen", instant(row, "lastSeenAt"))
                .addValue("occurrences", row.path("occurrenceCount").asInt())
                .addValue("ackDeadline", instant(row, "ackDeadlineAt"))
                .addValue("acknowledgedAt", instantOrNull(row, "acknowledgedAt"))
                .addValue("acknowledgedBy", textOrNull(row, "acknowledgedBy"))
                .addValue("recoveredAt", instantOrNull(row, "recoveredAt"))
                .addValue("reminderCount", row.path("reminderCount").asInt())
                .addValue("nextReminder", instantOrNull(row, "nextReminderAt"))
                .addValue("escalatedAt", instantOrNull(row, "escalatedAt"))
                .addValue("details", row.path("details").toString())
                .addValue("version", row.path("version").asInt());
    }

    private static MapSqlParameterSource filters(String appId, String status, String severity) {
        return new MapSqlParameterSource()
                .addValue("app", appId)
                .addValue("status", status)
                .addValue("severity", severity);
    }

    private ObjectNode read(ResultSet rs, int rowNum) throws SQLException {
        try {
            ObjectNode details = (ObjectNode) mapper.readTree(rs.getString("details_json"));
            ObjectNode result = details.deepCopy();
            result.set("details", details);
            result.put("incidentId", rs.getString("id"));
            result.put("appId", rs.getString("app_id"));
            result.put("severity", rs.getString("severity"));
            result.put("incidentType", rs.getString("incident_type"));
            result.put("objectKey", rs.getString("object_key"));
            result.put("fingerprint", rs.getString("fingerprint"));
            putNullable(result, "activeFingerprint", rs.getString("active_fingerprint"));
            result.put("status", rs.getString("status"));
            result.put("routingStatus", rs.getString("routing_status"));
            putNullable(result, "routingError", rs.getString("routing_error"));
            putNullable(result, "chatMode", rs.getString("chat_mode"));
            putNullable(result, "chatId", rs.getString("chat_id"));
            putNullable(result, "openingMessageId", rs.getString("opening_message_id"));
            result.put("firstSeenAt", Instant.ofEpochMilli(rs.getLong("first_seen_at_ms")).toString());
            result.put("lastSeenAt", Instant.ofEpochMilli(rs.getLong("last_seen_at_ms")).toString());
            result.put("occurrenceCount", rs.getInt("occurrence_count"));
            result.put("ackDeadlineAt", Instant.ofEpochMilli(rs.getLong("ack_deadline_at_ms")).toString());
            putNullableInstant(result, "acknowledgedAt", rs.getObject("acknowledged_at_ms"));
            putNullable(result, "acknowledgedBy", rs.getString("acknowledged_by"));
            putNullableInstant(result, "recoveredAt", rs.getObject("recovered_at_ms"));
            result.put("reminderCount", rs.getInt("reminder_count"));
            putNullableInstant(result, "nextReminderAt", rs.getObject("next_reminder_at_ms"));
            putNullableInstant(result, "escalatedAt", rs.getObject("escalated_at_ms"));
            result.put("version", rs.getInt("version"));
            return result;
        } catch (Exception invalid) {
            throw new IllegalStateException("Alert incident storage is invalid", invalid);
        }
    }

    private static String textOrNull(ObjectNode row, String field) {
        return row.hasNonNull(field) ? row.path(field).asText() : null;
    }

    private static long instant(ObjectNode row, String field) {
        return Instant.parse(row.path(field).asText()).toEpochMilli();
    }

    private static Long instantOrNull(ObjectNode row, String field) {
        return row.hasNonNull(field) ? instant(row, field) : null;
    }

    private static void putNullable(ObjectNode target, String field, String value) {
        if (value == null) target.putNull(field); else target.put(field, value);
    }

    private static void putNullableInstant(ObjectNode target, String field, Object value) {
        if (value == null) target.putNull(field);
        else target.put(field, Instant.ofEpochMilli(((Number) value).longValue()).toString());
    }
}
