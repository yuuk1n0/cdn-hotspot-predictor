package com.yukino.cdn.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class NotificationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public NotificationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public boolean insert(ObjectNode record, String businessHash, String idempotencyHash, String requestHash, Instant now) {
        var p = new MapSqlParameterSource()
                .addValue("id", record.path("notificationId").asText()).addValue("app", record.path("appId").asText())
                .addValue("run", record.path("runId").isNull() ? null : record.path("runId").asText())
                .addValue("type", record.path("type").asText()).addValue("purpose", record.path("purpose").asText())
                .addValue("source", record.path("evidenceSource").asText()).addValue("dest", record.path("destinationId").asText())
                .addValue("business", businessHash).addValue("idem", idempotencyHash).addValue("request", requestHash)
                .addValue("status", record.path("status").asText()).addValue("json", record.toString())
                .addValue("now", now.toEpochMilli());
        try {
            jdbc.update("""
                    INSERT INTO notification_delivery (id,app_id,run_id,type,purpose,evidence_source,destination_id,
                    business_key_hash,idempotency_key_hash,request_hash,status,record_json,attempt_count,
                    next_attempt_at_ms,created_at_ms,updated_at_ms,sent_at_ms)
                    VALUES (:id,:app,:run,:type,:purpose,:source,:dest,:business,:idem,:request,:status,:json,0,:now,:now,:now,NULL)
                    """, p);
            return true;
        } catch (DuplicateKeyException duplicate) { return false; }
    }

    public Optional<ObjectNode> find(String id) { return one("id", id); }
    public Optional<ObjectNode> byBusiness(String hash) { return one("business_key_hash", hash); }
    public Optional<ObjectNode> byIdempotency(String hash) { return one("idempotency_key_hash", hash); }
    public Optional<String> requestHash(String idemHash) {
        var rows = jdbc.queryForList("SELECT request_hash FROM notification_delivery WHERE idempotency_key_hash=:hash",
                java.util.Map.of("hash", idemHash), String.class);
        return rows.stream().findFirst();
    }
    private Optional<ObjectNode> one(String column, String value) {
        var rows = jdbc.query("SELECT * FROM notification_delivery WHERE " + column + "=:value",
                java.util.Map.of("value", value), (rs, i) -> read(rs.getString("record_json"), rs.getString("status"),
                        rs.getInt("attempt_count"), rs.getLong("next_attempt_at_ms"), rs.getLong("sent_at_ms"), rs.wasNull()));
        return rows.stream().findFirst();
    }
    private ObjectNode read(String json, String status, int attempts, long next, long sent, boolean sentNull) {
        try {
            var row = (ObjectNode) mapper.readTree(json);
            row.put("status", status).put("attemptCount", attempts);
            if ("PENDING".equals(status)) row.put("nextAttemptAt", Instant.ofEpochMilli(next).toString());
            else row.putNull("nextAttemptAt");
            if (sentNull) row.putNull("sentAt"); else row.put("sentAt", Instant.ofEpochMilli(sent).toString());
            return row;
        } catch (Exception error) { throw new IllegalStateException("Invalid notification record", error); }
    }
    public boolean claim(String id, Instant now) {
        return jdbc.update("""
                UPDATE notification_delivery SET status='SENDING',attempt_count=attempt_count+1,updated_at_ms=:now
                WHERE id=:id AND status='PENDING' AND next_attempt_at_ms<=:now
                """, java.util.Map.of("id", id, "now", now.toEpochMilli())) == 1;
    }
    public List<String> due(Instant now) {
        return jdbc.queryForList("SELECT id FROM notification_delivery WHERE status='PENDING' AND next_attempt_at_ms<=:now ORDER BY created_at_ms,id",
                java.util.Map.of("now", now.toEpochMilli()), String.class);
    }
    public void finish(ObjectNode record, Instant now, Instant next, Instant sent) {
        jdbc.update("""
                UPDATE notification_delivery SET status=:status,record_json=:json,next_attempt_at_ms=:next,
                updated_at_ms=:now,sent_at_ms=:sent WHERE id=:id
                """, new MapSqlParameterSource().addValue("status", record.path("status").asText())
                .addValue("json", record.toString()).addValue("next", next == null ? 0 : next.toEpochMilli())
                .addValue("now", now.toEpochMilli()).addValue("sent", sent == null ? null : sent.toEpochMilli())
                .addValue("id", record.path("notificationId").asText()));
    }
    public void recover(Instant now) {
        var ids = jdbc.queryForList("SELECT id FROM notification_delivery WHERE status='SENDING'", java.util.Map.of(), String.class);
        for (String id : ids) {
            var row = find(id).orElseThrow();
            row.put("status", "UNKNOWN").put("lastError", "DELIVERY_UNCONFIRMED");
            finish(row, now, null, null);
        }
    }
    public List<ObjectNode> list(String appId, String runId, String type, int limit, int offset) {
        var p = new MapSqlParameterSource().addValue("app", appId).addValue("run", runId)
                .addValue("type", type).addValue("limit", limit).addValue("offset", offset);
        return jdbc.query("""
                SELECT * FROM notification_delivery WHERE app_id=:app
                AND (:run IS NULL OR run_id=:run) AND (:type IS NULL OR type=:type)
                ORDER BY created_at_ms DESC,id DESC LIMIT :limit OFFSET :offset
                """, p, (rs, i) -> read(rs.getString("record_json"), rs.getString("status"), rs.getInt("attempt_count"),
                rs.getLong("next_attempt_at_ms"), rs.getLong("sent_at_ms"), rs.wasNull()));
    }
    public int count(String appId, String runId, String type) {
        var p = new MapSqlParameterSource().addValue("app", appId).addValue("run", runId).addValue("type", type);
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_delivery WHERE app_id=:app
                AND (:run IS NULL OR run_id=:run) AND (:type IS NULL OR type=:type)
                """, p, Integer.class);
    }
}
