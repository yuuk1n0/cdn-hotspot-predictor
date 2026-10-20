package com.yukino.cdn.prediction;

import java.time.Instant;
import java.time.LocalDate;

/** Internal durable state. pythonRunId is never published as a business runId in step 3. */
final class PredictionTask {
    String taskId, keyHash, requestHash;
    PredictionRequest request;
    String status, stage, datasetSource, pythonStatus, pythonRunId;
    PredictionProblem.Error error, dependencyError;
    int attemptCount, retryCount;
    Instant createdAt, updatedAt, deadlineAt, nextAttemptAt;

    View view() {
        return new View(taskId, status, stage, request.datasetId(), request.appId(), request.cutoffDate(),
                request.horizonDays(), request.modelVersion(), datasetSource, null, null,
                error, dependencyError, attemptCount, createdAt, updatedAt, deadlineAt, nextAttemptAt);
    }

    public record View(String taskId, String status, String stage, String datasetId, String appId, LocalDate cutoffDate,
                       int horizonDays, String modelVersion, String datasetSource, String predictionSource, String runId,
                       PredictionProblem.Error error, PredictionProblem.Error lastDependencyError, int attemptCount,
                       Instant createdAt, Instant updatedAt, Instant deadlineAt, Instant nextAttemptAt) {}
}
