package com.yukino.cdn.prediction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
