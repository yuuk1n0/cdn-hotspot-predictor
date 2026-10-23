package com.yukino.cdn.compression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.operations.OperationsRepository;
import com.yukino.cdn.operations.OperationsService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;

import static com.yukino.cdn.operations.OperationsSupport.*;

@Service
public class CompressionRegistrationService {
    private final OperationsRepository store;
    private final OperationsService operations;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CompressionRegistrationService(OperationsRepository store, OperationsService operations,
                                          ObjectMapper mapper) {
        this(store, operations, mapper, Clock.systemUTC());
    }

    CompressionRegistrationService(OperationsRepository store, OperationsService operations,
                                   ObjectMapper mapper, Clock clock) {
        this.store = store; this.operations = operations; this.mapper = mapper; this.clock = clock;
    }

    public record Created(ObjectNode record, boolean repeated) { }

    public Created create(JsonNode body, String key) {
        if (key == null || key.isBlank() || key.length() > 200) throw problem(422, "INVALID_REQUEST", "Idempotency-Key须为1至200字符。");
        if (body == null || !body.isObject()) throw problem(422, "INVALID_REQUEST", "登记须为JSON对象。");
        checkFields(body, Set.of("runId", "appId", "fileKey", "evidenceSource", "status", "originalSizeBytes", "originalSizeSource",
                "originalSizeEvidence", "compressedFileKey", "compressedVersion", "compressedSizeBytes", "quality", "handler", "remark", "compressedAt"));
        String requestHash = fingerprint(body);
        var existing = store.idempotent(hash(key), requestHash);
        if (existing != null) return new Created(existing, true);
        String app = id(text(body, "appId", 32)), runId = text(body, "runId", 128), file = text(body, "fileKey", 4096);
        String evidenceSource = source(text(body, "evidenceSource", 8));
        if (!"TEST".equals(evidenceSource))
            throw problem(422, "EVIDENCE_SOURCE_UNSUPPORTED", "仅支持人工TEST压缩登记。");
        operations.run(app, runId, null, 0);
        ObjectNode record = mapper.createObjectNode();
        for (String field : List.of("runId", "appId", "fileKey", "evidenceSource", "status", "handler", "remark"))
            if (body.has(field)) record.set(field, body.get(field));
        if (!Set.of("PROCESSING", "COMPRESSED").contains(record.path("status").asText()))
            throw problem(422, "INVALID_STATUS", "处理状态须为PROCESSING或COMPRESSED。");
        text(record, "handler", 128);
        if (!record.has("remark")) record.put("remark", "");
        if (!record.path("remark").isTextual() || record.path("remark").asText().length() > 2000)
            throw problem(422, "INVALID_REQUEST", "备注须为不超过2000字符的文本。");
        if (record.path("status").asText().equals("COMPRESSED")) {
            copyEvidence(body, record);
            complete(record, true);
        } else {
            record.putNull("compressionRatio").putNull("qualityPassed").putNull("compressionThresholdPassed")
                    .put("effectiveCompressionStatus", "PENDING_OBSERVATION");
        }
        String now = clock.instant().toString();
        record.put("id", "cmp_" + UUID.randomUUID()).put("version", 1).put("createdAt", now).put("updatedAt", now);
        return new Created(store.insert(record, hash(key), requestHash, clock.instant()), false);
    }

    public ObjectNode update(String recordId, JsonNode body) {
        if (body == null || !body.isObject()) throw problem(422, "INVALID_REQUEST", "更新须为JSON对象。");
        checkFields(body, Set.of("appId", "version", "status", "handler", "remark", "originalSizeBytes", "originalSizeSource",
                "originalSizeEvidence", "compressedFileKey", "compressedVersion", "compressedSizeBytes", "quality", "compressedAt"));
        String app = id(text(body, "appId", 32));
        if (!body.path("version").isIntegralNumber() || !body.path("version").canConvertToInt() || body.path("version").intValue() < 1)
            throw problem(422, "INVALID_REQUEST", "更新须提供正整数version。");
        var prior = store.record(recordId);
        if (!app.equals(prior.path("appId").asText())) throw problem(422, "APP_MISMATCH", "登记记录所属App不一致。");
        int version = body.path("version").intValue();
        if (prior.path("version").asInt() != version) throw problem(409, "VERSION_CONFLICT", "登记记录已变化，请刷新。");
        boolean completed = "COMPRESSED".equals(prior.path("status").asText());
        String status = body.has("status") ? text(body, "status", 16) : prior.path("status").asText();
        if (!status.equals("COMPRESSED") && !(status.equals("PROCESSING") && !completed))
            throw problem(409, "INVALID_STATUS_TRANSITION", "已完成的压缩不能退回处理中。");
        if (completed && List.of("originalSizeBytes", "originalSizeSource", "originalSizeEvidence", "compressedSizeBytes", "compressedFileKey", "compressedVersion", "compressedAt")
                .stream().anyMatch(body::has)) throw problem(409, "INVALID_STATUS_TRANSITION", "已完成的文件版本和大小不可改写。");
        var record = prior.deepCopy();
        for (String field : List.of("handler", "remark")) if (body.has(field)) record.set(field, body.get(field));
        text(record, "handler", 128);
        if (!record.path("remark").isTextual() || record.path("remark").asText().length() > 2000)
            throw problem(422, "INVALID_REQUEST", "备注须为不超过2000字符的文本。");
        record.put("status", status);
        if (status.equals("COMPRESSED")) {
            if (completed && record.path("observations").size() > 0 && body.has("quality"))
                throw problem(409, "INVALID_STATUS_TRANSITION", "已有观测的质量证据不可直接改写。");
            copyEvidence(body, record);
            complete(record, !completed);
        }
        return store.update(record, version, clock.instant());
    }

    public ObjectNode list(String appId, String runId, String fileKey, String evidenceSource, String status, int page, int pageSize) {
        String app = id(appId);
        if (evidenceSource != null) source(evidenceSource);
        if (status != null && !Set.of("PROCESSING", "COMPRESSED").contains(status)) throw problem(422, "INVALID_STATUS", "处理状态不合法。");
        if (page < 1 || pageSize < 1 || pageSize > 100) throw problem(422, "INVALID_PAGINATION", "分页须为page>=1、pageSize为1至100。");
        if (fileKey != null && (fileKey.isBlank() || fileKey.length() > 4096)) throw problem(422, "INVALID_FILE_KEY", "文件标识不合法。");
        if (runId != null && (runId.isBlank() || runId.length() > 128)) throw problem(422, "INVALID_RUN_ID", "运行编号不合法。");
        List<ObjectNode> rows = new ArrayList<>();
        for (String scope : evidenceSource == null ? List.of("REAL", "TEST") : List.of(evidenceSource)) rows.addAll(store.records(app, scope));
        rows.removeIf(row -> runId != null && !runId.equals(row.path("runId").asText())
                || fileKey != null && !fileKey.equals(row.path("fileKey").asText())
                || status != null && !status.equals(row.path("status").asText()));
        rows.sort(Comparator.comparing((ObjectNode row) -> row.path("updatedAt").asText()).reversed().thenComparing(row -> row.path("id").asText()));
        var result = mapper.createObjectNode().put("page", page).put("pageSize", pageSize).put("total", rows.size());
        var items = result.putArray("items");
        long from = (long) (page - 1) * pageSize;
        for (long index = from; index < Math.min(from + pageSize, rows.size()); index++) items.add(rows.get((int) index));
        return result;
    }

    private void complete(ObjectNode record, boolean requireManualEvidence) {
        if (requireManualEvidence && (!"TEST".equals(record.path("evidenceSource").asText())
                || !"MANUAL_TEST".equals(record.path("originalSizeSource").asText())))
            throw problem(422, "MISSING_EVIDENCE", "原大小须使用人工TEST证据并注明MANUAL_TEST来源。");
        if (requireManualEvidence) text(record, "originalSizeEvidence", 2000);
        CompressionEvidence.complete(record, clock).fields().forEachRemaining(entry -> record.set(entry.getKey(), entry.getValue()));
    }

    private static void copyEvidence(JsonNode input, ObjectNode target) {
        for (String field : List.of("originalSizeBytes", "originalSizeSource", "originalSizeEvidence", "compressedFileKey",
                "compressedVersion", "compressedSizeBytes", "quality", "compressedAt")) if (input.has(field)) target.set(field, input.get(field));
    }

    private static void checkFields(JsonNode input, Set<String> allowed) {
        input.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw problem(422, "INVALID_REQUEST", "登记字段不受支持。"); });
    }
}
