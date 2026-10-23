package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.yukino.cdn.prediction.PredictionProblem;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import static com.yukino.cdn.operations.OperationsSupport.id;

record AlertEvent(
        String sourceEventId,
        String eventKind,
        String appId,
        String severity,
        String incidentType,
        String objectKey,
        String title,
        Instant occurredAt,
        String observedValue,
        String threshold,
        String summary,
        JsonNode evidence,
        String detailUrl,
        String incidentId,
        JsonNode raw
) {
    static AlertEvent parse(JsonNode body) {
        if (body == null || !body.isObject())
            throw problem("INVALID_ALERT_EVENT", "告警事件须为 JSON 对象。");
        String kind = required(body, "eventKind", 16);
        if (!kind.equals("ALERT") && !kind.equals("RECOVERY"))
            throw problem("INVALID_ALERT_EVENT", "eventKind 须为 ALERT 或 RECOVERY。");
        String appId = id(required(body, "appId", 32));
        Instant occurredAt;
        try { occurredAt = Instant.parse(required(body, "occurredAt", 64)); }
        catch (DateTimeParseException invalid) {
            throw problem("INVALID_ALERT_EVENT", "occurredAt 须为带时区的 ISO-8601 时间。");
        }
        if ("RECOVERY".equals(kind)) {
            return new AlertEvent(required(body, "sourceEventId", 128), kind, appId, "",
                    "", "", "", occurredAt, "", "", "", null, "",
                    required(body, "incidentId", 64), body.deepCopy());
        }
        String severity = required(body, "severity", 8);
        if (!severity.equals("P1") && !severity.equals("P2") && !severity.equals("P3"))
            throw problem("INVALID_ALERT_EVENT", "severity 须为 P1、P2 或 P3。");
        JsonNode evidence = body.path("evidence");
        if (!evidence.isObject()) throw problem("INVALID_ALERT_EVENT", "evidence 须为 JSON 对象。");
        String detailUrl = required(body, "detailUrl", 512);
        if (!detailUrl.startsWith("/") || detailUrl.startsWith("//")
                || detailUrl.chars().anyMatch(c -> c < 32 || c == 127))
            throw problem("INVALID_ALERT_EVENT", "detailUrl 须为站内相对路径。");
        return new AlertEvent(
                required(body, "sourceEventId", 128), kind, appId, severity,
                required(body, "incidentType", 64), required(body, "objectKey", 512),
                required(body, "title", 256), occurredAt, required(body, "observedValue", 128),
                required(body, "threshold", 128), required(body, "summary", 1024), evidence.deepCopy(),
                detailUrl, null, body.deepCopy());
    }

    private static String required(JsonNode body, String field, int maxLength) {
        JsonNode value = body.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength
                || value.asText().chars().anyMatch(c -> c < 32 || c == 127))
            throw problem("INVALID_ALERT_EVENT", field + " 缺失或格式不合法。");
        return value.asText();
    }

    private static PredictionProblem problem(String code, String message) {
        return new PredictionProblem(422, code, message);
    }
}
