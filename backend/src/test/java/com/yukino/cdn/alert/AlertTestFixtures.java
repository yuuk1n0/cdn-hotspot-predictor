package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class AlertTestFixtures {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlertTestFixtures() { }

    static ObjectNode alert(String sourceEventId, String eventKind, String appId,
                            String incidentType, String objectKey, String occurredAt) {
        return MAPPER.createObjectNode()
                .put("sourceEventId", sourceEventId)
                .put("eventKind", eventKind)
                .put("appId", appId)
                .put("severity", "P1")
                .put("incidentType", incidentType)
                .put("objectKey", objectKey)
                .put("title", "CSV 导入连续失败")
                .put("occurredAt", occurredAt)
                .put("observedValue", "3 次")
                .put("threshold", "1 次")
                .put("summary", "最近 10 分钟内连续失败 3 次")
                .put("detailUrl", "/imports?taskId=imp_1")
                .set("evidence", MAPPER.createObjectNode()
                        .put("importTaskId", "imp_1")
                        .putNull("runId")
                        .put("errorCode", "INVALID_CSV_HEADER"));
    }

    static ObjectNode recovery(String sourceEventId, String incidentId, String appId, String occurredAt) {
        return MAPPER.createObjectNode()
                .put("sourceEventId", sourceEventId)
                .put("eventKind", "RECOVERY")
                .put("incidentId", incidentId)
                .put("appId", appId)
                .put("occurredAt", occurredAt);
    }
}
