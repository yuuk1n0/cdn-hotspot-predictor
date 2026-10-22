package com.yukino.cdn.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.operations.OperationsService;
import com.yukino.cdn.prediction.PredictionProblem;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class NotificationService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final NotificationRepository repository;
    private final OperationsService operations;
    private final NotificationSettings settings;
    private final NotificationTransport transport;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired public NotificationService(NotificationRepository repository, OperationsService operations,
                                         NotificationSettings settings, NotificationTransport transport, ObjectMapper mapper) {
        this(repository, operations, settings, transport, mapper, Clock.systemUTC());
    }
    public NotificationService(NotificationRepository repository, OperationsService operations,
                               NotificationSettings settings, NotificationTransport transport, ObjectMapper mapper, Clock clock) {
        this.repository = repository; this.operations = operations; this.settings = settings;
        this.transport = transport; this.mapper = mapper; this.clock = clock;
    }
    @PostConstruct void recoverAtStartup() {
        try { recoverInterrupted(); } catch (org.springframework.dao.DataAccessException ignored) {
            // Migration may still be pending; a missing table must not prevent application startup.
        }
    }
    public void recoverInterrupted() { repository.recover(clock.instant()); }
    public JsonNode destinations() { return settings.publicDestinations(); }
    public ObjectNode overdue(String appId, String runId, String evidenceSource) {
        require(appId, "appId"); require(runId, "runId"); source(evidenceSource);
        return operations.overdue(appId, runId, evidenceSource);
    }
    public ObjectNode preview(String appId, String runId, String type, String purpose, String evidenceSource) {
        require(appId, "appId"); require(runId, "runId"); type(type); purpose(purpose); source(evidenceSource);
        ObjectNode facts = facts(appId, runId, type, evidenceSource);
        boolean eligible = eligible(facts, type, purpose, evidenceSource);
        var items = facts.path("items");
        ObjectNode result = mapper.createObjectNode();
        result.put("appId", appId).put("runId", runId).put("type", type).put("purpose", purpose)
                .put("evidenceSource", evidenceSource).put("businessDate", facts.path("predictionStartDate").asText())
                .put("predictionStartDate", facts.path("predictionStartDate").asText())
                .put("predictionEndDate", facts.path("predictionEndDate").asText())
                .put("horizonDays", facts.path("horizonDays").asInt())
                .put("modelVersion", facts.path("modelVersion").asText())
                .put("generatedAt", facts.path("generatedAt").asText())
                .put("publishedAt", facts.path("publishedAt").asText())
                .put("datasetSource", facts.path("datasetSource").asText())
                .put("predictionSource", facts.path("predictionSource").asText())
                .put("itemCount", items.size()).put("canSend", eligible && !items.isEmpty());
        if ("OVERDUE".equals(type)) {
            result.put("asOf", facts.path("asOf").asText());
            result.put("handlingHours", facts.path("handlingHours").asInt());
        }
        result.set("items", items.deepCopy());
        var reasons = result.putArray("reasonCodes");
        if (!eligible) reasons.add("SOURCE_NOT_ELIGIBLE");
        if (items.isEmpty()) reasons.add("NO_PENDING_ITEMS");
        result.put("text", render(result));
        return result;
    }
    private ObjectNode facts(String appId, String runId, String type, String evidenceSource) {
        ObjectNode facts = "OVERDUE".equals(type) ? operations.overdue(appId, runId, evidenceSource)
                : operations.pending(appId, runId, evidenceSource);
        if (!appId.equals(facts.path("appId").asText()) || !runId.equals(facts.path("runId").asText()))
            throw problem(422, "APP_MISMATCH", "运行范围不匹配。");
        return facts;
    }
    private boolean eligible(JsonNode facts, String type, String purpose, String evidenceSource) {
        if ("DEMO".equals(purpose)) return true;
        String app = facts.path("appId").asText();
        boolean sources = "REAL".equals(evidenceSource) && "REAL".equals(facts.path("datasetSource").asText())
                && "MODEL".equals(facts.path("predictionSource").asText());
        if (!sources) return false;
        if ("OVERDUE".equals(type)) return true;
        var config = operations.config(app);
        return today().toString().equals(facts.path("predictionStartDate").asText())
                && config.path("dailyModelVersion").asText().equals(facts.path("modelVersion").asText());
    }
    private String render(ObjectNode preview) {
        StringBuilder text = new StringBuilder();
        text.append(preview.path("purpose").asText()).append(" ")
                .append("OVERDUE".equals(preview.path("type").asText()) ? "逾期提醒" : "每日待处理清单")
                .append("\n业务日期：").append(preview.path("businessDate").asText())
                .append("；跨度：").append(preview.path("horizonDays").asInt()).append("天")
                .append("；模型：").append(preview.path("modelVersion").asText())
                .append("；来源：").append(preview.path("datasetSource").asText()).append("/")
                .append(preview.path("predictionSource").asText()).append("/")
                .append(preview.path("evidenceSource").asText())
                .append("\n共").append(preview.path("itemCount").asInt()).append("项");
        if ("OVERDUE".equals(preview.path("type").asText()))
            text.append("\n检查时间：").append(preview.path("asOf").asText())
                    .append("；处理时限：").append(preview.path("handlingHours").asInt()).append("小时");
        var items = preview.path("items");
        for (int i = 0; i < Math.min(10, items.size()); i++) {
            var item = items.get(i);
            text.append("\n").append(i + 1).append(". ").append(item.path("fileKey").asText())
                    .append(" 预测字节：").append(item.path("predictedBytes").asText("未知"));
            if (item.hasNonNull("dueAt")) text.append(" 截止：").append(item.path("dueAt").asText());
        }
        text.append("\n详情：/predictions/").append(preview.path("runId").asText());
        return text.toString();
    }
    public ObjectNode submit(JsonNode body, String key) {
        require(key, "Idempotency-Key");
        String app = required(body, "appId"), run = required(body, "runId"), type = required(body, "type");
        String purpose = required(body, "purpose"), source = required(body, "evidenceSource");
        String destinationId = required(body, "destinationId");
        type(type); purpose(purpose); source(source);
        if ("BUSINESS".equals(purpose) && !"REAL".equals(source))
            throw problem(409, "SOURCE_NOT_ELIGIBLE", "正式消息需要真实处理证据。");
        var destination = settings.destination(destinationId);
        if ("DEMO".equals(purpose) && !destination.demoOnly())
            throw problem(409, "DEMO_DESTINATION_REQUIRED", "演示消息仅可发送到测试目标。");
        if ("BUSINESS".equals(purpose) && destination.demoOnly())
            throw problem(409, "BUSINESS_DESTINATION_REQUIRED", "正式消息目标须为业务群。");
        String requestHash = hash(String.join("\n", app, run, type, purpose, source, destinationId));
        String idemHash = hash(key);
        var previous = repository.byIdempotency(idemHash);
        if (previous.isPresent()) {
            if (!requestHash.equals(repository.requestHash(idemHash).orElse("")))
                throw problem(409, "IDEMPOTENCY_CONFLICT", "幂等键已用于其他请求。");
            return previous.get();
        }
        var preview = preview(app, run, type, purpose, source);
        if (!eligible(preview, type, purpose, source)) throw problem(409, "SOURCE_NOT_ELIGIBLE", "数据来源不满足正式发送条件。");
        if (!preview.path("canSend").asBoolean()) throw problem(409, "NO_PENDING_ITEMS", "没有可发送的待处理项。");
        String businessHash = businessHash(app, run, preview.path("businessDate").asText(), type, purpose, source, destinationId);
        var existing = repository.byBusiness(businessHash);
        if (existing.isPresent()) return existing.get();
        ObjectNode record = newRecord(app, run, type, purpose, source, destinationId, preview, "PENDING", null);
        if (!repository.insert(record, businessHash, idemHash, requestHash, clock.instant())) {
            var repeated = repository.byIdempotency(idemHash);
            if (repeated.isPresent() && !requestHash.equals(repository.requestHash(idemHash).orElse("")))
                throw problem(409, "IDEMPOTENCY_CONFLICT", "幂等键已用于其他请求。");
            return repeated.or(() -> repository.byBusiness(businessHash)).orElseThrow();
        }
        return repository.find(record.path("notificationId").asText()).orElseThrow();
    }
    private ObjectNode newRecord(String app, String run, String type, String purpose, String source,
                                 String destination, ObjectNode preview, String status, String error) {
        var row = mapper.createObjectNode();
        row.put("notificationId", "ntf_" + UUID.randomUUID()).put("appId", app);
        if (run == null) row.putNull("runId"); else row.put("runId", run);
        row.put("type", type).put("purpose", purpose).put("evidenceSource", source)
                .put("destinationId", destination).put("businessDate", preview.path("businessDate").asText())
                .put("status", status).put("attemptCount", 0).put("createdAt", clock.instant().toString());
        if (error == null) row.putNull("lastError"); else row.put("lastError", error);
        if (preview.has("text")) row.put("text", preview.path("text").asText());
        row.putNull("sentAt"); row.putNull("nextAttemptAt");
        return row;
    }
    public void processDue() {
        Instant now = clock.instant();
        for (String id : repository.due(now)) {
            if (!repository.claim(id, now)) continue;
            var record = repository.find(id).orElseThrow();
            ObjectNode fresh;
            try {
                fresh = preview(record.path("appId").asText(), record.path("runId").asText(),
                        record.path("type").asText(), record.path("purpose").asText(), record.path("evidenceSource").asText());
            } catch (PredictionProblem error) {
                complete(record, "SKIPPED", error.code(), null, null);
                continue;
            } catch (Exception error) {
                complete(record, "SKIPPED", "SOURCE_UNAVAILABLE", null, null);
                continue;
            }
            if (!fresh.path("canSend").asBoolean()) {
                complete(record, "SKIPPED", fresh.path("reasonCodes").path(0).asText("NO_PENDING_ITEMS"), null, null);
                continue;
            }
            record.put("text", fresh.path("text").asText());
            NotificationSettings.Destination destination;
            try { destination = settings.destination(record.path("destinationId").asText()); }
            catch (PredictionProblem error) {
                complete(record, "SKIPPED", error.code(), null, null);
                continue;
            }
            try {
                var outcome = transport.send(destination, fresh.path("text").asText());
                if ("SENT".equals(outcome.status())) complete(record, "SENT", null, null, now);
                else if ("UNKNOWN".equals(outcome.status())) complete(record, "UNKNOWN", outcome.code(), null, null);
                else if (outcome.retryable() && record.path("attemptCount").asInt() < 4) {
                    long minutes = switch (record.path("attemptCount").asInt()) { case 1 -> 1; case 2 -> 5; default -> 15; };
                    complete(record, "PENDING", outcome.code(), now.plus(Duration.ofMinutes(minutes)), null);
                } else complete(record, "FAILED", outcome.code(), null, null);
            } catch (Exception error) {
                complete(record, "UNKNOWN", "DELIVERY_UNCONFIRMED", null, null);
            }
        }
    }
    private void complete(ObjectNode record, String status, String error, Instant next, Instant sent) {
        record.put("status", status);
        if (error == null) record.putNull("lastError"); else record.put("lastError", error);
        repository.finish(record, clock.instant(), next, sent);
    }
    public ObjectNode list(String appId, String runId, String type, int page, int pageSize) {
        require(appId, "appId");
        if (page < 1 || pageSize < 1 || pageSize > 100) throw problem(422, "INVALID_PAGINATION", "分页参数无效。");
        if (type != null) type(type);
        var result = mapper.createObjectNode().put("page", page).put("pageSize", pageSize)
                .put("total", repository.count(appId, runId, type));
        var items = result.putArray("items");
        repository.list(appId, runId, type, pageSize, (page - 1) * pageSize).forEach(items::add);
        return result;
    }
    public ObjectNode monitor(String appId) {
        require(appId, "appId");
        var counts = repository.statusCounts(appId);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        var result = mapper.createObjectNode().put("appId", appId).put("generatedAt", clock.instant().toString())
                .put("total", total);
        var statuses = result.putObject("statusCounts");
        for (String status : java.util.List.of("PENDING", "SENDING", "SENT", "FAILED", "UNKNOWN", "SKIPPED"))
            statuses.put(status, counts.getOrDefault(status, 0));
        result.set("destinations", settings.publicDestinations());
        var problems = result.putArray("recentProblems");
        repository.recentProblems(appId, 5).forEach(problems::add);
        var reasons = result.putArray("reasonCodes");
        if (settings.publicDestinations().isEmpty()) reasons.add("NO_DESTINATION_CONFIGURED");
        if (total == 0) reasons.add("NO_DELIVERY_RECORD");
        if (statuses.path("FAILED").asInt() > 0) reasons.add("DELIVERY_FAILED");
        if (statuses.path("UNKNOWN").asInt() > 0) reasons.add("DELIVERY_UNCONFIRMED");
        return result.put("monitorMode", "DELIVERY_RECORDS_ONLY");
    }
    public void automatic(String type) {
        type(type);
        for (String app : operations.configuredApps()) {
            var config = operations.config(app);
            if (!config.path("notificationsEnabled").asBoolean(false)) continue;
            String destination = config.path("destinationId").asText();
            if ("OVERDUE".equals(type)) {
                for (String runId : new java.util.LinkedHashSet<>(operations.reminderRuns(app))) {
                    try {
                        var due = operations.overdue(app, runId, "REAL");
                        if (due.path("items").isEmpty()) continue;
                        var body = mapper.createObjectNode().put("appId", app).put("runId", runId)
                                .put("type", "OVERDUE").put("purpose", "BUSINESS")
                                .put("evidenceSource", "REAL").put("destinationId", destination);
                        submit(body, "auto:" + businessHash(app, runId, due.path("predictionStartDate").asText(),
                                "OVERDUE", "BUSINESS", "REAL", destination));
                    } catch (PredictionProblem ignored) {
                        // A particular run may have become unavailable; the next run is independent.
                    }
                }
                continue;
            }
            try {
                var run = operations.run(app, null, today(), 1);
                if (!eligible(run, type, "BUSINESS", "REAL")) { skipped(app, type, destination, "SOURCE_NOT_ELIGIBLE"); continue; }
                var body = mapper.createObjectNode().put("appId", app).put("runId", run.path("runId").asText())
                        .put("type", type).put("purpose", "BUSINESS").put("evidenceSource", "REAL")
                        .put("destinationId", destination);
                submit(body, "auto:" + businessHash(app, run.path("runId").asText(), today().toString(), type,
                        "BUSINESS", "REAL", destination));
            } catch (PredictionProblem error) {
                skipped(app, type, destination, error.code().equals("NO_CURRENT_RUN") ? "NO_CURRENT_RUN" : error.code());
            }
        }
    }
    private void skipped(String app, String type, String destination, String reason) {
        var preview = mapper.createObjectNode().put("businessDate", today().toString());
        var record = newRecord(app, null, type, "BUSINESS", "REAL", destination, preview, "SKIPPED", reason);
        String business = businessHash(app, "", today().toString(), type, "BUSINESS", "REAL", destination);
        repository.insert(record, business, hash("auto-skip:" + business), hash(business), clock.instant());
    }
    private String businessHash(String app, String run, String date, String type, String purpose, String source, String dest) {
        return hash(String.join("\n", app, run, date, type, purpose, source, dest,
                "OVERDUE".equals(type) ? today().toString() : ""));
    }
    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), SHANGHAI); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static PredictionProblem problem(int status, String code, String message) { return new PredictionProblem(status, code, message); }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw problem(422, "INVALID_REQUEST", name + " 不能为空。");
    }
    private static String required(JsonNode body, String field) {
        String value = body.path(field).asText(""); require(value, field); return value;
    }
    private static void type(String value) { if (!"DAILY".equals(value) && !"OVERDUE".equals(value)) throw problem(422,"INVALID_TYPE","通知类型无效。"); }
    private static void purpose(String value) { if (!"DEMO".equals(value) && !"BUSINESS".equals(value)) throw problem(422,"INVALID_PURPOSE","通知用途无效。"); }
    private static void source(String value) { if (!"REAL".equals(value) && !"TEST".equals(value)) throw problem(422,"INVALID_EVIDENCE_SOURCE","证据来源无效。"); }
}
