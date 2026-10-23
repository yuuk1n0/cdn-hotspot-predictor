package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

/** Single-instance durable orchestration; Python predicts, Java validates and publishes Top50. */
public class PredictionWorker {
    private final PredictionRepository repository;
    private final PredictorClient python;
    private final PredictionSettings settings;
    private final Clock clock;

    public PredictionWorker(PredictionRepository repository, PredictorClient python, PredictionSettings settings, Clock clock) {
        this.repository = repository; this.python = python; this.settings = settings; this.clock = clock;
    }

    public void tick() {
        for (PredictionTask task : repository.due(clock.instant())) process(task);
    }

    private void process(PredictionTask task) {
        if (expired(task)) { fail(task, "TASK_TIMEOUT", "超过预测任务总时限；不会继续派发或发布迟到结果。"); return; }
        task.status = "RUNNING"; task.stage = "VALIDATING"; task.attemptCount++;
        save(task);
        try {
            validateDataset(task, python.dataset(task.request.datasetId()));
            if (expired(task)) { fail(task, "TASK_TIMEOUT", "数据集校验期间超过任务总时限。"); return; }
            // Query first even after a lost POST response or a Java restart; only 404 permits re-dispatch.
            var found = python.task(task.taskId);
            if (expired(task)) { fail(task, "TASK_TIMEOUT", "查询 Python 任务期间超过任务总时限。"); return; }
            PredictorClient.PythonTask result = found.isPresent() ? found.get() : python.submit(task.taskId, task.request);
            if (expired(task)) { fail(task, "TASK_TIMEOUT", "Python 响应到达时任务已超时，不发布迟到结果。"); return; }
            validatePythonTask(task.taskId, result);
            if (task.pythonRunId != null && !task.pythonRunId.equals(result.runId())) {
                throw new PredictionProblem(422, "INVALID_RESULT", "同一 Python 成功任务的 runId 或终态发生改变。");
            }
            task.pythonStatus = result.status(); task.pythonRunId = result.runId();
            if ("FAILED".equals(result.status())) {
                fail(task, result.error().code(), result.error().message());
            } else if ("SUCCEEDED".equals(result.status())) {
                task.stage = "AGGREGATING";
                save(task);
                JsonNode snapshot = PredictionResult.build(python.items(task.pythonRunId), task);
                if (expired(task)) { fail(task, "TASK_TIMEOUT", "获取或校验结果期间超过任务总时限，不发布迟到榜单。"); return; }
                repository.publish(task, snapshot, clock);
            } else {
                task.dependencyError = null; task.retryCount = 0;
                task.stage = "COMPUTING";
                task.nextAttemptAt = clock.instant().plus(settings.pollInterval());
                save(task);
            }
        } catch (PredictionProblem problem) {
            handle(task, problem);
        } catch (DataAccessException | TransactionException storageFailure) {
            handle(task, PredictionProblem.retryable("RESULT_STORAGE_UNAVAILABLE", "榜单持久化暂不可用，未确认发布；将查询原任务和原 runId 重试。"));
        }
    }

    private void handle(PredictionTask task, PredictionProblem problem) {
        if (expired(task)) { fail(task, "TASK_TIMEOUT", "依赖调用或榜单发布期间超过预测任务总时限。"); }
        else if (!problem.retryable()) { fail(task, problem.code(), problem.getMessage()); }
        else {
            task.stage = "WAITING_DEPENDENCY"; task.dependencyError = problem.error(); task.retryCount++;
            long delay = Math.min(settings.maxRetryDelay().toMillis(), settings.pollInterval().toMillis() * (1L << Math.min(task.retryCount - 1, 10)));
            Instant retryAt = clock.instant().plusMillis(delay);
            task.nextAttemptAt = retryAt.isAfter(task.deadlineAt) ? task.deadlineAt : retryAt;
            save(task);
        }
    }

    private void validateDataset(PredictionTask task, JsonNode data) {
        if (data == null || !data.path("datasetId").isTextual() || !task.request.datasetId().equals(data.path("datasetId").textValue())
                || !Set.of("REAL", "FAKE").contains(data.path("datasetSource").asText())) {
            throw new PredictionProblem(422, "INVALID_DATASET", "Python 数据集编号或来源元数据无效。");
        }
        if (!"READY".equals(data.path("status").asText())) throw new PredictionProblem(409, "DATASET_NOT_READY", "数据集未就绪。");
        if (!"FILE_DAILY".equals(data.path("datasetType").asText())) throw new PredictionProblem(422, "DATASET_TYPE_UNSUPPORTED", "仅支持 FILE_DAILY 文件日流量。");
        if (!"B".equals(data.path("sourceUnit").asText())) throw new PredictionProblem(422, "UNIT_UNCONFIRMED", "数据集流量单位未确认为 B。");
        boolean appMatches = false;
        for (JsonNode id : data.path("appIds")) if (id.isTextual() && task.request.appId().equals(id.textValue())) appMatches = true;
        if (!appMatches) throw new PredictionProblem(422, "APP_MISMATCH", "数据集不包含请求的 App ID。");
        JsonNode range = data.path("appDateRanges").path(task.request.appId());
        try {
            LocalDate start = LocalDate.parse(range.path("start").asText()), end = LocalDate.parse(range.path("end").asText());
            if (start.isAfter(end)) throw new DateTimeParseException("invalid range", "", 0);
            if (task.request.cutoffDate().isBefore(start) || task.request.cutoffDate().isAfter(end)) {
                throw new PredictionProblem(422, "INVALID_CUTOFF_DATE", "截止日超出该 App 的已观测日期范围。");
            }
        } catch (DateTimeParseException exception) {
            throw new PredictionProblem(422, "INVALID_DATASET", "缺少有效的 App 日期范围。");
        }
        task.datasetSource = data.path("datasetSource").textValue();
    }

    static void validatePythonTask(String expectedId, PredictorClient.PythonTask result) {
        if (result == null || !expectedId.equals(result.taskId()) || result.status() == null
                || !Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED").contains(result.status())
                || ("SUCCEEDED".equals(result.status()) && (result.runId() == null || result.runId().isBlank() || result.runId().length() > 128))
                || ("FAILED".equals(result.status()) && result.error() == null)
                || (!"SUCCEEDED".equals(result.status()) && result.runId() != null)
                || (!"FAILED".equals(result.status()) && result.error() != null)) {
            throw new PredictionProblem(422, "INVALID_PYTHON_RESPONSE", "Python 任务编号、状态或结果引用不符合契约。");
        }
    }

    private boolean expired(PredictionTask task) { return !clock.instant().isBefore(task.deadlineAt); }
    private void save(PredictionTask task) { task.updatedAt = clock.instant(); repository.save(task); }
    private void fail(PredictionTask task, String code, String message) {
        task.status = "FAILED"; task.stage = "FAILED"; task.error = new PredictionProblem.Error(code, message); save(task);
    }
}
