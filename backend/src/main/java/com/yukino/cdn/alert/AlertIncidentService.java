package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static com.yukino.cdn.operations.OperationsSupport.problem;

@Service
public class AlertIncidentService {
    private final AlertIncidentRepository repository;
    private final AlertRouteService routes;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AlertDispatcher dispatcher;

    AlertIncidentService(AlertIncidentRepository repository, AlertRouteService routes,
                         ObjectMapper mapper, Clock clock) {
        this(repository, routes, mapper, clock, null);
    }

    @Autowired
    public AlertIncidentService(AlertIncidentRepository repository, AlertRouteService routes,
                                ObjectMapper mapper, Clock clock, AlertDispatcher dispatcher) {
        this.repository = repository;
        this.routes = routes;
        this.mapper = mapper;
        this.clock = clock;
        this.dispatcher = dispatcher;
    }

    @Transactional
    public ObjectNode ingest(JsonNode body) {
        AlertEvent event = AlertEvent.parse(body);
        var prior = repository.event(event.appId(), event.sourceEventId());
        if (prior.isPresent()) return receipt(prior.get().path("incidentId").asText(), true);
        if ("RECOVERY".equals(event.eventKind())) return ingestRecovery(event);

        ObjectNode route = routes.get(event.appId());
        long windowSeconds = Math.max(1, route.path("activeWindowMinutes").asLong(60)) * 60;
        long bucket = event.occurredAt().getEpochSecond() / windowSeconds;
        String fingerprint = hash(event.appId() + "\n" + event.incidentType()
                + "\n" + event.objectKey() + "\n" + bucket);
        boolean routable = route.path("alertsEnabled").asBoolean()
                && !route.path("ownerOpenIds").isEmpty()
                && !route.path("fixedChatId").asText().isBlank();

        var active = repository.active(fingerprint);
        ObjectNode incident;
        if (active.isPresent()) {
            incident = active.get();
            incident.put("lastSeenAt", later(incident.path("lastSeenAt").asText(), event.occurredAt()).toString())
                    .put("occurrenceCount", incident.path("occurrenceCount").asInt() + 1)
                    .set("details", eventDetails(event, incident.path("occurrenceCount").asInt() + 1));
            incident = repository.update(incident, incident.path("version").asInt());
        } else {
            Instant firstSeen = event.occurredAt();
            int ackMinutes = Math.max(1, route.path("ackDeadlineMinutes").asInt(5));
            incident = mapper.createObjectNode()
                    .put("incidentId", "inc_" + UUID.randomUUID())
                    .put("appId", event.appId())
                    .put("severity", event.severity())
                    .put("incidentType", event.incidentType())
                    .put("objectKey", event.objectKey())
                    .put("fingerprint", fingerprint)
                    .put("activeFingerprint", fingerprint)
                    .put("status", "OPEN")
                    .put("routingStatus", routable ? "PENDING" : "ROUTING_FAILED")
                    .put("routingError", routable ? null : "ALERT_ROUTE_UNAVAILABLE")
                    .put("firstSeenAt", firstSeen.toString())
                    .put("lastSeenAt", firstSeen.toString())
                    .put("occurrenceCount", 1)
                    .put("ackDeadlineAt", firstSeen.plus(Duration.ofMinutes(ackMinutes)).toString())
                    .put("reminderCount", 0)
                    .put("nextReminderAt", firstSeen.plus(Duration.ofMinutes(ackMinutes)).toString())
                    .put("version", 1);
            incident.set("details", eventDetails(event, 1));
            repository.insert(incident);
        }
        attachEvent(event, incident.path("incidentId").asText());
        if (dispatcher != null) {
            if (active.isPresent()) dispatcher.update(incident);
            else dispatcher.open(incident, route);
        }
        return receipt(incident.path("incidentId").asText(), false);
    }

    @Transactional
    public ObjectNode recover(String incidentId, String actor) {
        ObjectNode incident = repository.get(incidentId);
        if ("RECOVERED".equals(incident.path("status").asText())) return incident;
        incident.put("status", "RECOVERED")
                .put("recoveredAt", clock.instant().toString())
                .putNull("activeFingerprint")
                .putNull("nextReminderAt");
        ObjectNode details = (ObjectNode) incident.path("details");
        details.put("recoveredBy", actor == null ? "" : actor);
        incident = repository.update(incident, incident.path("version").asInt());
        if (dispatcher != null) dispatcher.recover(incident);
        return incident;
    }

    @Transactional
    public ObjectNode ack(String incidentId, String openId, int expectedVersion) {
        ObjectNode incident = authorizedIncident(incidentId, openId);
        if ("ACKED".equals(incident.path("status").asText())
                && openId.equals(incident.path("acknowledgedBy").asText()))
            return incident;
        requireVersion(incident, expectedVersion);
        if ("RECOVERED".equals(incident.path("status").asText()))
            throw problem(409, "INCIDENT_ALREADY_RECOVERED", "事故已恢复，不能认领。");
        incident.put("status", "ACKED")
                .put("acknowledgedBy", openId)
                .put("acknowledgedAt", clock.instant().toString())
                .putNull("nextReminderAt");
        incident = repository.update(incident, expectedVersion);
        if (dispatcher != null) dispatcher.update(incident);
        return repository.get(incidentId);
    }

    @Transactional
    public ObjectNode processing(String incidentId, String openId, int expectedVersion) {
        ObjectNode incident = authorizedIncident(incidentId, openId);
        if ("PROCESSING".equals(incident.path("status").asText())) return incident;
        requireVersion(incident, expectedVersion);
        if ("RECOVERED".equals(incident.path("status").asText()))
            throw problem(409, "INCIDENT_ALREADY_RECOVERED", "事故已恢复，不能更新处理状态。");
        incident.put("status", "PROCESSING")
                .put("acknowledgedBy", openId)
                .put("acknowledgedAt", incident.hasNonNull("acknowledgedAt")
                        ? incident.path("acknowledgedAt").asText() : clock.instant().toString())
                .putNull("nextReminderAt");
        incident = repository.update(incident, expectedVersion);
        if (dispatcher != null) dispatcher.update(incident);
        return repository.get(incidentId);
    }

    @Transactional
    public ObjectNode recoverAsUser(String incidentId, String openId) {
        authorizedIncident(incidentId, openId);
        return recover(incidentId, openId);
    }

    public ObjectNode get(String incidentId) {
        return repository.get(incidentId);
    }

    public ObjectNode list(String appId, String status, String severity, int page, int pageSize) {
        com.yukino.cdn.operations.OperationsSupport.id(appId);
        if (page < 1 || pageSize < 1 || pageSize > 100)
            throw problem(422, "INVALID_PAGINATION", "分页参数无效。");
        if (status != null && !java.util.Set.of("OPEN", "ACKED", "PROCESSING", "RECOVERED").contains(status))
            throw problem(422, "INVALID_INCIDENT_STATUS", "事故状态筛选无效。");
        if (severity != null && !java.util.Set.of("P1", "P2", "P3").contains(severity))
            throw problem(422, "INVALID_SEVERITY", "事故等级筛选无效。");
        var result = mapper.createObjectNode().put("page", page).put("pageSize", pageSize)
                .put("total", repository.count(appId, status, severity));
        var items = result.putArray("items");
        repository.list(appId, status, severity, pageSize, (page - 1) * pageSize).forEach(items::add);
        return result;
    }

    private ObjectNode ingestRecovery(AlertEvent event) {
        ObjectNode incident = repository.get(event.incidentId());
        if (!event.appId().equals(incident.path("appId").asText()))
            throw problem(422, "APP_MISMATCH", "恢复事件与事故 AppID 不匹配。");
        if ("RECOVERED".equals(incident.path("status").asText())) {
            attachEvent(event, incident.path("incidentId").asText());
            return receipt(incident.path("incidentId").asText(), true);
        }
        incident.put("status", "RECOVERED")
                .put("recoveredAt", event.occurredAt().toString())
                .putNull("activeFingerprint")
                .putNull("nextReminderAt");
        ((ObjectNode) incident.path("details")).put("recoveredBy", "ALERT_SOURCE");
        incident = repository.update(incident, incident.path("version").asInt());
        attachEvent(event, incident.path("incidentId").asText());
        if (dispatcher != null) dispatcher.recover(incident);
        return receipt(incident.path("incidentId").asText(), false);
    }

    private ObjectNode authorizedIncident(String incidentId, String openId) {
        if (openId == null || !openId.matches("[A-Za-z0-9_-]{1,128}"))
            throw problem(422, "INVALID_OPERATOR", "飞书操作人 ID 不合法。");
        ObjectNode incident = repository.get(incidentId);
        ObjectNode route = routes.get(incident.path("appId").asText());
        boolean owner = contains(route.path("ownerOpenIds"), openId);
        boolean backup = contains(route.path("backupOpenIds"), openId);
        if (!owner && !backup)
            throw problem(403, "OPERATOR_NOT_RESPONSIBLE", "当前用户不是该事故负责人或备份负责人。");
        return incident;
    }

    private static boolean contains(JsonNode values, String openId) {
        for (var value : values) if (openId.equals(value.asText())) return true;
        return false;
    }

    private static void requireVersion(ObjectNode incident, int expectedVersion) {
        if (incident.path("version").asInt() != expectedVersion)
            throw problem(409, "INCIDENT_VERSION_CONFLICT", "事故已变化，请刷新后重试。");
    }

    private void attachEvent(AlertEvent event, String incidentId) {
        repository.insertEvent("aev_" + UUID.randomUUID(), event, incidentId, clock.instant());
    }

    private ObjectNode receipt(String incidentId, boolean duplicate) {
        ObjectNode incident = repository.get(incidentId);
        return mapper.createObjectNode()
                .put("incidentId", incidentId)
                .put("status", incident.path("status").asText())
                .put("routingStatus", incident.path("routingStatus").asText())
                .put("duplicate", duplicate);
    }

    private ObjectNode eventDetails(AlertEvent event, int occurrenceCount) {
        ObjectNode details = mapper.createObjectNode()
                .put("title", event.title())
                .put("observedValue", event.observedValue())
                .put("threshold", event.threshold())
                .put("summary", event.summary())
                .put("detailUrl", event.detailUrl())
                .put("sourceEventId", event.sourceEventId())
                .put("occurrenceCount", occurrenceCount);
        details.set("evidence", event.evidence().deepCopy());
        return details;
    }

    private static Instant later(String current, Instant candidate) {
        Instant value = Instant.parse(current);
        return candidate.isAfter(value) ? candidate : value;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
