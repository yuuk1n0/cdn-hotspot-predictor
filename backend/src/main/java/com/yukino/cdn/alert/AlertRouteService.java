package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.operations.OperationsRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import static com.yukino.cdn.operations.OperationsSupport.id;
import static com.yukino.cdn.operations.OperationsSupport.problem;

@Service
public class AlertRouteService {
    private static final Set<String> EDITABLE_FIELDS = Set.of(
            "expectedVersion", "alertsEnabled", "businessName", "ownerOpenIds", "backupOpenIds",
            "fixedChatId", "testChatId", "p1AutoCreateGroup", "ackDeadlineMinutes",
            "remindIntervalMinutes", "maxReminderCount", "activeWindowMinutes");
    private final OperationsRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AlertRouteService(OperationsRepository repository, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    public ObjectNode get(String appId) {
        id(appId);
        var stored = repository.config(appId);
        if (stored == null) return defaults(appId);
        var result = defaults(appId);
        stored.fields().forEachRemaining(field -> result.set(field.getKey(), field.getValue().deepCopy()));
        return result;
    }

    public List<ObjectNode> list() {
        return repository.apps().stream().map(this::get).toList();
    }

    public ArrayNode listJson() {
        var result = mapper.createArrayNode();
        list().forEach(result::add);
        return result;
    }

    public ObjectNode update(String appId, JsonNode body) {
        id(appId);
        int expectedVersion = expectedVersion(body);
        body.fieldNames().forEachRemaining(field -> {
            if (!EDITABLE_FIELDS.contains(field))
                throw problem(422, "INVALID_CONFIG", "告警配置不允许修改字段 " + field + "。");
        });
        var current = get(appId);
        if (current.path("version").asInt() != expectedVersion)
            throw problem(409, "VERSION_CONFLICT", "配置已变化，请刷新后再修改。");
        for (String field : List.of(
                "alertsEnabled", "businessName", "ownerOpenIds", "backupOpenIds", "fixedChatId",
                "testChatId", "p1AutoCreateGroup", "ackDeadlineMinutes", "remindIntervalMinutes",
                "maxReminderCount", "activeWindowMinutes")) {
            if (body.has(field)) current.set(field, body.get(field).deepCopy());
        }
        validateTypes(current);
        if (current.path("alertsEnabled").asBoolean()
                && (current.path("ownerOpenIds").isEmpty() || current.path("fixedChatId").asText().isBlank()))
            throw problem(422, "INVALID_CONFIG", "启用告警须配置负责人和固定应急群。");
        if (current.path("alertsEnabled").asBoolean() && current.path("businessName").asText().isBlank())
            throw problem(422, "INVALID_CONFIG", "启用告警须配置业务名称。");
        if (current.path("alertsEnabled").asBoolean() && current.path("p1AutoCreateGroup").asBoolean()
                && current.path("backupOpenIds").isEmpty())
            throw problem(422, "INVALID_CONFIG", "P1 自动建群须配置备份负责人。");
        integerInRange(current, "ackDeadlineMinutes", 1, 1440);
        integerInRange(current, "remindIntervalMinutes", 1, 1440);
        integerInRange(current, "maxReminderCount", 1, 50);
        integerInRange(current, "activeWindowMinutes", 1, 10080);
        return repository.saveConfig(appId, current, expectedVersion, clock.instant());
    }

    private void validateTypes(ObjectNode route) {
        requireBoolean(route, "alertsEnabled");
        requireBoolean(route, "p1AutoCreateGroup");
        requireText(route, "businessName", 128, true);
        requireText(route, "fixedChatId", 128, true);
        requireText(route, "testChatId", 128, true);
        requireIds(route, "ownerOpenIds");
        requireIds(route, "backupOpenIds");
        requireIdValue(route.path("fixedChatId").asText(), "fixedChatId", true);
        requireIdValue(route.path("testChatId").asText(), "testChatId", true);
    }

    private void requireBoolean(ObjectNode route, String field) {
        if (!route.path(field).isBoolean())
            throw problem(422, "INVALID_CONFIG", field + " 须为 boolean。");
    }

    private void requireText(ObjectNode route, String field, int maxLength, boolean allowBlank) {
        var value = route.path(field);
        if (!value.isTextual() || value.asText().length() > maxLength
                || (!allowBlank && value.asText().isBlank())
                || value.asText().chars().anyMatch(c -> c < 32 || c == 127))
            throw problem(422, "INVALID_CONFIG", field + " 须为有效字符串。");
    }

    private void requireIds(ObjectNode route, String field) {
        var values = route.path(field);
        if (!values.isArray())
            throw problem(422, "INVALID_CONFIG", field + " 须为飞书用户 ID 数组。");
        for (var value : values) {
            if (!value.isTextual()) throw problem(422, "INVALID_CONFIG", field + " 须只包含字符串。");
            requireIdValue(value.asText(), field, false);
        }
    }

    private void requireIdValue(String value, String field, boolean allowBlank) {
        if ((!allowBlank && value.isBlank()) || (!value.isBlank() && !value.matches("[A-Za-z0-9_-]{1,128}")))
            throw problem(422, "INVALID_CONFIG", field + " 包含不合法的飞书 ID。");
    }

    private int expectedVersion(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("expectedVersion").isIntegralNumber()
                || !body.path("expectedVersion").canConvertToInt() || body.path("expectedVersion").asInt() < 0)
            throw problem(422, "INVALID_CONFIG", "须提供非负整数 expectedVersion。");
        return body.path("expectedVersion").asInt();
    }

    private void integerInRange(ObjectNode route, String field, int minimum, int maximum) {
        var value = route.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.asInt() < minimum || value.asInt() > maximum)
            throw problem(422, "INVALID_CONFIG", field + " 须为 " + minimum + " 至 " + maximum + " 的整数。");
    }

    private ObjectNode defaults(String appId) {
        var result = mapper.createObjectNode()
                .put("appId", appId)
                .put("version", 0)
                .put("alertsEnabled", false)
                .put("businessName", "")
                .put("fixedChatId", "")
                .put("testChatId", "")
                .put("p1AutoCreateGroup", true)
                .put("ackDeadlineMinutes", 5)
                .put("remindIntervalMinutes", 15)
                .put("maxReminderCount", 4)
                .put("activeWindowMinutes", 60);
        result.putArray("ownerOpenIds");
        result.putArray("backupOpenIds");
        return result;
    }
}
