package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

import static com.yukino.cdn.operations.OperationsSupport.hash;

@Repository
public class AlertDeliveryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AlertDeliveryRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(ObjectNode incident, String phase, String chatId, String messageId,
                       String status, String errorCode, Instant now) {
        String incidentId = incident.path("incidentId").asText();
        String businessKey = hash(String.join("|", incidentId, phase,
                Integer.toString(incident.path("occurrenceCount").asInt()),
                Integer.toString(incident.path("version").asInt())));
        ObjectNode record = mapper.createObjectNode()
                .put("notificationId", "ntf_" + UUID.randomUUID())
                .put("incidentId", incidentId)
                .put("appId", incident.path("appId").asText())
                .put("alertPhase", phase)
                .put("chatId", chatId)
                .put("feishuMessageId", messageId)
                .put("status", status)
                .put("createdAt", now.toString());
        if (messageId == null) record.putNull("feishuMessageId");
        if (errorCode == null) record.putNull("lastError"); else record.put("lastError", errorCode);
        var p = new MapSqlParameterSource()
                .addValue("id", record.path("notificationId").asText())
                .addValue("app", incident.path("appId").asText())
                .addValue("type", "ALERT")
                .addValue("purpose", "BUSINESS")
                .addValue("source", "REAL")
                .addValue("dest", chatId == null || chatId.isBlank() ? "feishu-openapi" : chatId)
                .addValue("business", businessKey)
                .addValue("idem", hash("idem|" + businessKey))
                .addValue("request", businessKey)
                .addValue("status", status)
                .addValue("json", record.toString())
                .addValue("incident", incidentId)
                .addValue("phase", phase)
                .addValue("chat", chatId)
                .addValue("message", messageId)
                .addValue("now", now.toEpochMilli())
                .addValue("sent", "SENT".equals(status) ? now.toEpochMilli() : null);
        try {
            jdbc.update("""
                    INSERT INTO notification_delivery (
                    id,app_id,run_id,type,purpose,evidence_source,destination_id,business_key_hash,
                    idempotency_key_hash,request_hash,status,record_json,attempt_count,next_attempt_at_ms,
                    created_at_ms,updated_at_ms,sent_at_ms,incident_id,alert_phase,chat_id,feishu_message_id)
                    VALUES (:id,:app,NULL,:type,:purpose,:source,:dest,:business,:idem,:request,:status,:json,
                    1,0,:now,:now,:sent,:incident,:phase,:chat,:message)
                    """, p);
        } catch (DuplicateKeyException ignored) {
            // The same incident phase was already recorded; do not duplicate the audit row.
        }
    }
}
