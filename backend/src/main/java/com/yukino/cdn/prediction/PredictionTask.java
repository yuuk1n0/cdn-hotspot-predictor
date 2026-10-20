package com.yukino.cdn.prediction;

import java.time.Instant;
import java.time.LocalDate;

/** Python references stay private until the matching business snapshot commits. */
final class PredictionTask {
    String taskId, keyHash, requestHash;
    PredictionRequest request;
    String status, stage, datasetSource, pythonStatus, pythonRunId;
    String businessRunId, predictionSource;
    PredictionProblem.Error error, dependencyError;
    int attemptCount, retryCount;
    Instant createdAt, updatedAt, deadlineAt, nextAttemptAt;

    View view() {
        return new View(taskId, status, stage, request.datasetId(), request.appId(), request.cutoffDate(),
                request.horizonDays(), request.modelVersion(), datasetSource,
                "SUCCEEDED".equals(status) ? predictionSource : null, "SUCCEEDED".equals(status) ? businessRunId : null,
                error, dependencyError, attemptCount, createdAt, updatedAt, deadlineAt, nextAttemptAt);
    }

    public record View(String taskId, String status, String stage, String datasetId, String appId, LocalDate cutoffDate,
                       int horizonDays, String modelVersion, String datasetSource, String predictionSource, String runId,
                       PredictionProblem.Error error, PredictionProblem.Error lastDependencyError, int attemptCount,
                       Instant createdAt, Instant updatedAt, Instant deadlineAt, Instant nextAttemptAt) {}
}
