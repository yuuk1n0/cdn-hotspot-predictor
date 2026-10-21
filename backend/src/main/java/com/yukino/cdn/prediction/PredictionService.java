package com.yukino.cdn.prediction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PredictionService {
    private final PredictionRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PredictionSettings settings;

    public PredictionService(PredictionRepository repository, ObjectMapper mapper, Clock clock, PredictionSettings settings) {
        this.repository = repository; this.mapper = mapper; this.clock = clock; this.settings = settings;
    }

    public Accepted submit(PredictionRequest request, String key) {
        if (key == null || key.isBlank() || key.length() > 200) throw PredictionRequest.invalid("Idempotency-Key 必须为 1 至 200 字符的非空键。");
        String requestHash;
        try { requestHash = hash(mapper.writeValueAsString(request)); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
        String keyHash = hash(key);
        var existing = repository.findByKey(keyHash);
        if (existing.isPresent()) return existing(existing.get(), requestHash);
        PredictionTask task = new PredictionTask();
        task.taskId = "task_" + UUID.randomUUID(); task.keyHash = keyHash; task.requestHash = requestHash; task.request = request;
        task.status = "PENDING"; task.stage = "WAITING";
        task.createdAt = task.updatedAt = task.nextAttemptAt = clock.instant();
        task.deadlineAt = task.createdAt.plus(settings.taskTimeout());
        try { repository.insert(task); }
        catch (DuplicateKeyException exception) {
            return existing(repository.findByKey(keyHash).orElseThrow(() -> exception), requestHash);
        }
        return new Accepted(task.taskId, task.status);
    }

    public PredictionTask.View get(String taskId) {
        return repository.find(taskId).orElseThrow(() -> new PredictionProblem(404, "TASK_NOT_FOUND", "预测任务不存在。")).view();
    }

    public JsonNode result(String runId) {
        String json = repository.snapshot(runId).orElseThrow(() -> new PredictionProblem(404, "RUN_NOT_FOUND", "已成功发布的业务榜单不存在。"));
        try { return mapper.readTree(json); }
        catch (JsonProcessingException exception) { throw new PredictionProblem(503, "RESULT_STORAGE_UNAVAILABLE", "已保存的榜单快照无法读取，未返回替代数据。"); }
    }

    /** Current database read; old immutable snapshots are never rewritten with operational state. */
    public java.util.List<com.fasterxml.jackson.databind.node.ObjectNode> published(String appId, String runId, java.time.LocalDate businessDate, int horizonDays) {
        var results = new java.util.ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
        for (var value : repository.published(appId, runId, businessDate, horizonDays)) {
            try {
                JsonNode parsed = mapper.readTree(value.json());
                if (!parsed.isObject() || !appId.equals(parsed.path("appId").asText())) continue;
                var item = (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
                item.put("publishedAt", value.publishedAt().toString()).put("businessDate", item.path("predictionStartDate").asText());
                results.add(item);
            } catch (java.io.IOException invalid) { throw new PredictionProblem(503, "RESULT_STORAGE_UNAVAILABLE", "已保存的运行无法读取。"); }
        }
        return results;
    }

    private Accepted existing(PredictionTask task, String hash) {
        if (!task.requestHash.equals(hash)) throw new PredictionProblem(409, "IDEMPOTENCY_CONFLICT", "同一幂等键已用于不同预测请求。");
        return new Accepted(task.taskId, task.status);
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Accepted(String taskId, String status) {}
}
